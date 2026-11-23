package com.aerus.engine.scheduling;

import com.aerus.common.AerusException;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Real Admission Control & Deadlock Avoidance Resource Manager using Banker's Algorithm.
 * Tracks real database resources across active transactions:
 * 1. ResourceType.FRAMES (BufferPool pinned frames)
 * 2. ResourceType.LOCK_SLOTS (Lock table entries held by transaction)
 * 3. ResourceType.SESSIONS (Active concurrent transaction slots)
 *
 * Runs the safety algorithm O(P^2 * R) on live transaction states.
 * If a request would cause an unsafe state, the transaction safely waits in a FIFO queue.
 */
public class ResourceManager {

    public enum ResourceType {
        FRAMES,     // buffer pool frames
        LOCK_SLOTS, // lock entries
        SESSIONS    // concurrent transactions
    }

    public static final int NUM_RESOURCES = ResourceType.values().length;

    private final int[] totalResources;
    private final int[] availableResources;

    // Per-transaction tracking: txnId -> arrays of size NUM_RESOURCES
    private final Map<Long, int[]> maximumClaims = new HashMap<>();
    private final Map<Long, int[]> allocations = new HashMap<>();
    private final Map<Long, int[]> needs = new HashMap<>();

    private final ReentrantLock lock = new ReentrantLock(true);
    private final Condition stateChanged = lock.newCondition();

    private long resourceWaitTimeoutMillis = 5000;

    // Metrics
    private long totalGrants = 0;
    private long totalWaits = 0;
    private long totalDenials = 0;
    private long totalTimeouts = 0;

    public ResourceManager(int totalFrames, int totalLockSlots, int totalSessions) {
        this.totalResources = new int[]{totalFrames, totalLockSlots, totalSessions};
        this.availableResources = new int[]{totalFrames, totalLockSlots, totalSessions};
    }

    public void setWaitTimeoutMillis(long timeoutMillis) {
        this.resourceWaitTimeoutMillis = timeoutMillis;
    }

    /**
     * Registers a transaction with its maximum claim.
     */
    public void registerTransaction(long txnId, int maxFrames, int maxLocks) {
        lock.lock();
        try {
            int[] claim = new int[]{maxFrames, maxLocks, 1}; // sessions claim = 1
            for (int r = 0; r < NUM_RESOURCES; r++) {
                if (claim[r] > totalResources[r]) {
                    throw new AerusException(String.format("ERROR[CLAIM_TOO_LARGE]: Claim %d exceeds total capacity %d for resource %s",
                            claim[r], totalResources[r], ResourceType.values()[r]));
                }
            }

            maximumClaims.put(txnId, claim);
            allocations.put(txnId, new int[NUM_RESOURCES]);
            needs.put(txnId, Arrays.copyOf(claim, NUM_RESOURCES));

            // Immediately allocate session slot = 1
            requestInternal(txnId, ResourceType.SESSIONS, 1, resourceWaitTimeoutMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AerusException("Interrupted while registering transaction " + txnId, e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Request n units of resource for txnId.
     */
    public void request(long txnId, ResourceType resource, int n) throws InterruptedException {
        lock.lock();
        try {
            requestInternal(txnId, resource, n, resourceWaitTimeoutMillis);
        } finally {
            lock.unlock();
        }
    }

    private void requestInternal(long txnId, ResourceType resource, int n, long timeoutMillis) throws InterruptedException {
        int rIdx = resource.ordinal();
        int[] need = needs.get(txnId);
        int[] alloc = allocations.get(txnId);

        if (need == null || alloc == null) {
            throw new AerusException("Transaction " + txnId + " not registered in ResourceManager.");
        }

        if (n > need[rIdx]) {
            totalDenials++;
            throw new AerusException(String.format("ERROR[CLAIM_EXCEEDED]: Txn %d requested %d of %s, but declared need is only %d",
                    txnId, n, resource, need[rIdx]));
        }

        long deadline = System.currentTimeMillis() + timeoutMillis;

        while (true) {
            // Check if available right now
            if (n <= availableResources[rIdx]) {
                // Tentatively allocate
                availableResources[rIdx] -= n;
                alloc[rIdx] += n;
                need[rIdx] -= n;

                // Test Banker's Safety Algorithm
                if (isSafe()) {
                    totalGrants++;
                    return; // Safe state confirmed!
                } else {
                    // Rollback tentative allocation
                    availableResources[rIdx] += n;
                    alloc[rIdx] -= n;
                    need[rIdx] += n;
                }
            }

            totalWaits++;
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                totalTimeouts++;
                throw new AerusException(String.format("ERROR[RESOURCE_TIMEOUT]: Txn %d timed out waiting for %d of %s",
                        txnId, n, resource));
            }

            stateChanged.await(remaining, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Release n units of resource for txnId.
     */
    public void release(long txnId, ResourceType resource, int n) {
        lock.lock();
        try {
            int rIdx = resource.ordinal();
            int[] alloc = allocations.get(txnId);
            int[] need = needs.get(txnId);

            if (alloc == null || need == null) {
                return;
            }

            if (n > alloc[rIdx]) {
                throw new AerusException(String.format("Illegal release: txn %d released %d of %s but holds only %d",
                        txnId, n, resource, alloc[rIdx]));
            }

            alloc[rIdx] -= n;
            need[rIdx] += n;
            availableResources[rIdx] += n;

            // Wake up waiting transactions to re-evaluate safety
            stateChanged.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Unregisters transaction and releases all remaining allocated resources.
     */
    public void unregisterTransaction(long txnId) {
        lock.lock();
        try {
            int[] alloc = allocations.remove(txnId);
            maximumClaims.remove(txnId);
            needs.remove(txnId);

            if (alloc != null) {
                for (int r = 0; r < NUM_RESOURCES; r++) {
                    availableResources[r] += alloc[r];
                }
            }
            stateChanged.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Banker's Safety Algorithm:
     * Simulates remaining processes to verify if there exists at least one safe execution sequence.
     */
    public boolean isSafe() {
        int numProcesses = allocations.size();
        if (numProcesses == 0) return true;

        int[] work = Arrays.copyOf(availableResources, NUM_RESOURCES);
        Map<Long, Boolean> finish = new HashMap<>();
        for (Long pid : allocations.keySet()) {
            finish.put(pid, false);
        }

        int count = 0;
        while (count < numProcesses) {
            boolean found = false;
            for (Map.Entry<Long, int[]> entry : allocations.entrySet()) {
                long pid = entry.getKey();
                if (!finish.get(pid)) {
                    int[] pNeed = needs.get(pid);
                    boolean canFinish = true;
                    for (int r = 0; r < NUM_RESOURCES; r++) {
                        if (pNeed[r] > work[r]) {
                            canFinish = false;
                            break;
                        }
                    }

                    if (canFinish) {
                        for (int r = 0; r < NUM_RESOURCES; r++) {
                            work[r] += entry.getValue()[r];
                        }
                        finish.put(pid, true);
                        found = true;
                        count++;
                    }
                }
            }
            if (!found) {
                return false; // System is in an unsafe state
            }
        }
        return true;
    }

    public synchronized String getResourceReport() {
        lock.lock();
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("=== LIVE RESOURCE MANAGER (BANKER'S ALGORITHM) ===\n");
            sb.append(String.format("Totals    : Frames=%d, LockSlots=%d, Sessions=%d\n",
                    totalResources[0], totalResources[1], totalResources[2]));
            sb.append(String.format("Available : Frames=%d, LockSlots=%d, Sessions=%d\n",
                    availableResources[0], availableResources[1], availableResources[2]));
            sb.append(String.format("System State : %s\n", isSafe() ? "SAFE" : "UNSAFE"));
            sb.append("Active Transactions (" + allocations.size() + "):\n");
            for (Long tid : allocations.keySet()) {
                int[] m = maximumClaims.get(tid);
                int[] a = allocations.get(tid);
                int[] n = needs.get(tid);
                sb.append(String.format("  Txn %d -> Max[%d, %d, %d] | Alloc[%d, %d, %d] | Need[%d, %d, %d]\n",
                        tid, m[0], m[1], m[2], a[0], a[1], a[2], n[0], n[1], n[2]));
            }
            return sb.toString();
        } finally {
            lock.unlock();
        }
    }

    public int[] getAvailableResources() {
        lock.lock();
        try {
            return Arrays.copyOf(availableResources, NUM_RESOURCES);
        } finally {
            lock.unlock();
        }
    }

    public long getTotalGrants() { return totalGrants; }
    public long getTotalWaits() { return totalWaits; }
    public long getTotalDenials() { return totalDenials; }
    public long getTotalTimeouts() { return totalTimeouts; }
}

