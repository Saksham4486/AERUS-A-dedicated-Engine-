package com.aerus.engine.scheduling;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class BankersAlgorithmTest {

    @Test
    void testSafeState() {
        int[] available = {3, 3, 2};
        int[][] max = {
            {7, 5, 3},
            {3, 2, 2},
            {9, 0, 2},
            {2, 2, 2},
            {4, 3, 3}
        };
        int[][] allocation = {
            {0, 1, 0},
            {2, 0, 0},
            {3, 0, 2},
            {2, 1, 1},
            {0, 0, 2}
        };

        BankersAlgorithm banker = new BankersAlgorithm(available, max, allocation);
        assertTrue(banker.isSafeState(), "System should be in a safe state initially");

        // Process 1 requests {1, 0, 2}
        boolean granted = banker.requestResources(1, new int[]{1, 0, 2});
        assertTrue(granted, "Request should be granted safely");

        // Process 4 requests {3, 3, 0}
        boolean granted2 = banker.requestResources(4, new int[]{3, 3, 0});
        assertFalse(granted2, "Request should be denied due to safety limits");
    }
}

