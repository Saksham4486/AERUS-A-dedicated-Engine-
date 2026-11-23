package com.aerus.txn;

import com.aerus.common.DeadlockException;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Strict Two-Phase Locking (2PL) LockManager supporting Shared (S) and Exclusive (X) modes.
 * Implements:
 * 1. Lock Queue with FIFO fairness and S->X upgrade capability.
 * 2. Wait-For Graph Deadlock Detection (detects cycles and aborts the youngest transaction).
 * 3. Lock timeout support.
 */
public class LockManager {

    public enum LockMode {
        SHARED,
        EXCLUSIVE
    }

    public static class LockRequest {
        public final long txnId;
        public final LockMode lockMode;
        public boolean isGranted = false;

        public LockRequest(long txnId, LockMode lockMode) {
            this.txnId = txnId;
            this.lockMode = lockMode;
        }
    }

    public static class LockHead {
        public final String resourceId;
        public final List<LockRequest> requestQueue = new ArrayList<>();
        public LockMode currentMode = null;

        public LockHead(String resourceId) {
            this.resourceId = resourceId;
        }

        public boolean isGranted(long txnId) {
            for (LockRequest req : requestQueue) {
                if (req.txnId == txnId && req.isGranted) {
                    return true;
                }
            }
            return false;
        }

        public LockMode getGrantedMode(long txnId) {
            for (LockRequest req : requestQueue) {
                if (req.txnId == txnId && req.isGranted) {
                    return req.lockMode;
                }
            }
            return null;
        }
    }

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition stateChanged = lock.newCondition();

    // Map: resourceId -> LockHead
    private final Map<String, LockHead> lockTable = new HashMap<>();

    // Map: txnId -> Set of resourceIds locked by this transaction
    private final Map<Long, Set<String>> txnLockedResources = new HashMap<>();

    // Wait-For Graph: txnId -> Set of txnIds it is directly waiting on
    private final Map<Long, Set<Long>> waitForGraph = new HashMap<>();

    // Deadlock aborted transactions: txnId -> reason
    private final Set<Long> abortedTxns = new HashSet<>();

    private long lockTimeoutMillis = 5000;

    public LockManager() {
    }

    public LockManager(long lockTimeoutMillis) {
        this.lockTimeoutMillis = lockTimeoutMillis;
    }

    public void setLockTimeoutMillis(long timeoutMillis) {
        this.lockTimeoutMillis = timeoutMillis;
    }

    /**
     * Acquire a lock on resourceId for txnId in the specified mode (SHARED or EXCLUSIVE).
     */
    public boolean acquire(long txnId, String resourceId, LockMode mode) throws InterruptedException {
        lock.lock();
        try {
            if (abortedTxns.contains(txnId)) {
                throw new DeadlockException(txnId, "Transaction already chosen as deadlock victim.");
            }

            LockHead head = lockTable.computeIfAbsent(resourceId, LockHead::new);

            // Check if already held
            LockMode heldMode = head.getGrantedMode(txnId);
            if (heldMode != null) {
                if (heldMode == mode || heldMode == LockMode.EXCLUSIVE) {
                    return true; // Already holds sufficient lock
                }
                // Upgrade from SHARED to EXCLUSIVE
                return upgradeLock(txnId, head);
            }

            // New request
            LockRequest request = new LockRequest(txnId, mode);
            head.requestQueue.add(request);

            long deadline = System.currentTimeMillis() + lockTimeoutMillis;

            while (!tryGrant(head, request)) {
                // Update Wait-For Graph
                updateWaitForGraph(txnId, head);

                // Run deadlock detection (cycle detection in wait-for graph)
                checkForDeadlock();

                if (abortedTxns.contains(txnId)) {
                    head.requestQueue.remove(request);
                    cleanEmptyHead(resourceId);
                    cleanTxnFromWaitGraph(txnId);
                    stateChanged.signalAll();
                    throw new DeadlockException(txnId, "Deadlock cycle detected; transaction chosen as victim.");
                }

                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    head.requestQueue.remove(request);
                    cleanEmptyHead(resourceId);
                    cleanTxnFromWaitGraph(txnId);
                    stateChanged.signalAll();
                    return false; // Timeout
                }

                stateChanged.await(remaining, TimeUnit.MILLISECONDS);

                if (abortedTxns.contains(txnId)) {
                    head.requestQueue.remove(request);
                    cleanEmptyHead(resourceId);
                    cleanTxnFromWaitGraph(txnId);
                    stateChanged.signalAll();
                    throw new DeadlockException(txnId, "Deadlock cycle detected; transaction chosen as victim.");
                }
            }

            // Successfully granted!
            cleanTxnFromWaitGraph(txnId);
            txnLockedResources.computeIfAbsent(txnId, k -> new HashSet<>()).add(resourceId);
            return true;
        } finally {
            lock.unlock();
        }
    }

    private boolean upgradeLock(long txnId, LockHead head) throws InterruptedException {
        // Can upgrade immediately if this transaction is the only one holding SHARED lock
        int grantedCount = 0;
        for (LockRequest req : head.requestQueue) {
            if (req.isGranted) grantedCount++;
        }

        if (grantedCount == 1) {
            // Find existing request and promote to EXCLUSIVE
            for (LockRequest req : head.requestQueue) {
                if (req.txnId == txnId && req.isGranted) {
                    head.requestQueue.remove(req);
                    LockRequest newReq = new LockRequest(txnId, LockMode.EXCLUSIVE);
                    newReq.isGranted = true;
                    head.requestQueue.add(0, newReq);
                    head.currentMode = LockMode.EXCLUSIVE;
                    return true;
                }
            }
        }

        // Otherwise must wait for other shared holders to release
        return false;
    }

    private boolean tryGrant(LockHead head, LockRequest req) {
        if (req.isGranted) return true;

        if (head.currentMode == null) {
            // Unlocked resource: grant immediately
            req.isGranted = true;
            head.currentMode = req.lockMode;
            return true;
        }

        if (head.currentMode == LockMode.SHARED && req.lockMode == LockMode.SHARED) {
            // Can share if no earlier request is EXCLUSIVE (FIFO fairness)
            for (LockRequest r : head.requestQueue) {
                if (r == req) break;
                if (!r.isGranted && r.lockMode == LockMode.EXCLUSIVE) {
                    return false; // Blocked by queued exclusive request
                }
            }
            req.isGranted = true;
            return true;
        }

        return false;
    }

    private void updateWaitForGraph(long waitingTxnId, LockHead head) {
        Set<Long> waitingOn = waitForGraph.computeIfAbsent(waitingTxnId, k -> new HashSet<>());
        waitingOn.clear();
        for (LockRequest req : head.requestQueue) {
            if (req.isGranted && req.txnId != waitingTxnId) {
                waitingOn.add(req.txnId);
            }
        }
    }

    private void cleanTxnFromWaitGraph(long txnId) {
        waitForGraph.remove(txnId);
        for (Set<Long> waits : waitForGraph.values()) {
            waits.remove(txnId);
        }
    }

    /**
     * Cycle detection in Wait-For graph using DFS.
     * If a cycle is detected, aborts the youngest transaction (highest txnId).
     */
    private void checkForDeadlock() {
        Set<Long> visited = new HashSet<>();
        Set<Long> recStack = new HashSet<>();
        List<Long> cycle = new ArrayList<>();

        for (Long txnId : waitForGraph.keySet()) {
            if (!visited.contains(txnId)) {
                if (findCycle(txnId, visited, recStack, cycle)) {
                    // Deadlock cycle found! Pick youngest transaction (max txnId) in the cycle as victim
                    long victim = Collections.max(cycle);
                    abortedTxns.add(victim);
                    cleanTxnFromWaitGraph(victim);
                    stateChanged.signalAll();
                    break;
                }
            }
        }
    }

    private boolean findCycle(Long current, Set<Long> visited, Set<Long> recStack, List<Long> cycle) {
        visited.add(current);
        recStack.add(current);

        Set<Long> neighbors = waitForGraph.get(current);
        if (neighbors != null) {
            for (Long neighbor : neighbors) {
                if (!visited.contains(neighbor)) {
                    if (findCycle(neighbor, visited, recStack, cycle)) {
                        cycle.add(current);
                        return true;
                    }
                } else if (recStack.contains(neighbor)) {
                    cycle.add(neighbor);
                    cycle.add(current);
                    return true;
                }
            }
        }

        recStack.remove(current);
        return false;
    }

    /**
     * Release all locks held by a transaction (called at commit or abort for Strict 2PL).
     */
    public void releaseAll(long txnId) {
        lock.lock();
        try {
            Set<String> resources = txnLockedResources.remove(txnId);
            if (resources != null) {
                for (String res : resources) {
                    LockHead head = lockTable.get(res);
                    if (head != null) {
                        head.requestQueue.removeIf(req -> req.txnId == txnId);
                        recomputeHeadMode(head);
                        cleanEmptyHead(res);
                    }
                }
            }
            abortedTxns.remove(txnId);
            cleanTxnFromWaitGraph(txnId);
            stateChanged.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private void recomputeHeadMode(LockHead head) {
        boolean anyExclusive = false;
        boolean anyShared = false;
        for (LockRequest req : head.requestQueue) {
            if (req.isGranted) {
                if (req.lockMode == LockMode.EXCLUSIVE) anyExclusive = true;
                if (req.lockMode == LockMode.SHARED) anyShared = true;
            }
        }
        if (anyExclusive) head.currentMode = LockMode.EXCLUSIVE;
        else if (anyShared) head.currentMode = LockMode.SHARED;
        else head.currentMode = null;
    }

    private void cleanEmptyHead(String resourceId) {
        LockHead head = lockTable.get(resourceId);
        if (head != null && head.requestQueue.isEmpty()) {
            lockTable.remove(resourceId);
        }
    }

    public int getLockCount(long txnId) {
        lock.lock();
        try {
            Set<String> res = txnLockedResources.get(txnId);
            return res == null ? 0 : res.size();
        } finally {
            lock.unlock();
        }
    }

    public boolean isAborted(long txnId) {
        lock.lock();
        try {
            return abortedTxns.contains(txnId);
        } finally {
            lock.unlock();
        }
    }
}

