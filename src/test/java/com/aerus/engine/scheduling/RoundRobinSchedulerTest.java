package com.aerus.engine.scheduling;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class RoundRobinSchedulerTest {

    @Test
    @DisplayName("Round Robin time slicing interleaves long-running tasks fairly")
    void testRoundRobinTimeSlicing() throws InterruptedException {
        // Time quantum = 20ms
        TaskScheduler scheduler = new TaskScheduler(SchedulingPolicy.ROUND_ROBIN, 20);
        scheduler.start();

        List<String> executionLog = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(2);

        // Task A needs 60ms (3 slices)
        Task taskA = new Task(1, "TaskA", 60, 1, new SteppableWorkload() {
            int remaining = 60;
            @Override
            public TaskStatus step(int sliceMillis) {
                int execute = Math.min(sliceMillis, remaining);
                remaining -= execute;
                executionLog.add("A-" + remaining);
                if (remaining <= 0) {
                    latch.countDown();
                    return TaskStatus.DONE;
                }
                return TaskStatus.YIELDED;
            }
        });

        // Task B needs 40ms (2 slices)
        Task taskB = new Task(2, "TaskB", 40, 1, new SteppableWorkload() {
            int remaining = 40;
            @Override
            public TaskStatus step(int sliceMillis) {
                int execute = Math.min(sliceMillis, remaining);
                remaining -= execute;
                executionLog.add("B-" + remaining);
                if (remaining <= 0) {
                    latch.countDown();
                    return TaskStatus.DONE;
                }
                return TaskStatus.YIELDED;
            }
        });

        scheduler.submit(taskA);
        scheduler.submit(taskB);

        assertTrue(latch.await(3, TimeUnit.SECONDS), "Both tasks should complete");
        scheduler.stop();

        // Round Robin interleaving: A should run slice 1, then B runs slice 1, then A slice 2, etc.
        // First execution of A leaves remaining 40, B leaves remaining 20
        assertEquals("A-40", executionLog.get(0));
        assertEquals("B-20", executionLog.get(1));
        assertEquals("A-20", executionLog.get(2));
        assertEquals("B-0", executionLog.get(3));
        assertEquals("A-0", executionLog.get(4));

        assertTrue(scheduler.getTotalContextSwitches() >= 5);
        assertEquals(2, scheduler.getCompletedTasks());
    }
}
