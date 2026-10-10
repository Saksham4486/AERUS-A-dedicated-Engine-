package com.aerus.engine.scheduling;

/**
 * Banker's Algorithm for Resource Allocation and Deadlock Avoidance.
 * Maps OS resource allocation concepts directly to the database lock manager.
 */
public class BankersAlgorithm {

    private final int[] available;
    private final int[][] maximum;
    private final int[][] allocation;
    private final int[][] need;

    private final int numProcesses;
    private final int numResources;

    public BankersAlgorithm(int[] availableResources, int[][] maximumClaim, int[][] currentAllocation) {
        this.numProcesses = maximumClaim.length;
        this.numResources = availableResources.length;
        
        this.available = new int[numResources];
        System.arraycopy(availableResources, 0, this.available, 0, numResources);

        this.maximum = maximumClaim;
        this.allocation = currentAllocation;
        this.need = new int[numProcesses][numResources];

        // Calculate the Need matrix: Need[i][j] = Max[i][j] - Allocation[i][j]
        for (int i = 0; i < numProcesses; i++) {
            for (int j = 0; j < numResources; j++) {
                this.need[i][j] = maximum[i][j] - allocation[i][j];
            }
        }
    }

    /**
     * Checks if the system is in a safe state.
     * @return true if safe, false if granting requests might lead to deadlock.
     */
    public boolean isSafeState() {
        int[] work = new int[numResources];
        System.arraycopy(available, 0, work, 0, numResources);

        boolean[] finish = new boolean[numProcesses];
        int count = 0;

        while (count < numProcesses) {
            boolean foundProcess = false;
            for (int p = 0; p < numProcesses; p++) {
                if (!finish[p]) {
                    boolean canAllocate = true;
                    for (int r = 0; r < numResources; r++) {
                        if (need[p][r] > work[r]) {
                            canAllocate = false;
                            break;
                        }
                    }

                    if (canAllocate) {
                        for (int r = 0; r < numResources; r++) {
                            work[r] += allocation[p][r];
                        }
                        finish[p] = true;
                        foundProcess = true;
                        count++;
                    }
                }
            }
            if (!foundProcess) {
                return false; // System is not safe
            }
        }
        return true;
    }

    /**
     * Request resources for a specific process.
     */
    public synchronized boolean requestResources(int processId, int[] request) {
        // Step 1: Check if Request <= Need
        for (int r = 0; r < numResources; r++) {
            if (request[r] > need[processId][r]) {
                throw new IllegalArgumentException("Process exceeded maximum claim");
            }
        }

        // Step 2: Check if Request <= Available
        for (int r = 0; r < numResources; r++) {
            if (request[r] > available[r]) {
                return false; // Resources not available right now, must wait
            }
        }

        // Step 3: Pretend to allocate and check safety
        for (int r = 0; r < numResources; r++) {
            available[r] -= request[r];
            allocation[processId][r] += request[r];
            need[processId][r] -= request[r];
        }

        if (isSafeState()) {
            return true; // Allocation is safe and confirmed
        } else {
            // Rollback as it leads to unsafe state
            for (int r = 0; r < numResources; r++) {
                available[r] += request[r];
                allocation[processId][r] -= request[r];
                need[processId][r] += request[r];
            }
            return false;
        }
    }
}

