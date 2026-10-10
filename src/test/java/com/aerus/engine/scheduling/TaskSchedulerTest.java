package com.aerus.engine.scheduling;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskSchedulerTest {

    @Test
    void testSchedulerFCFS() throws InterruptedException {
        TaskScheduler scheduler = new TaskScheduler(SchedulingPolicy.FCFS);
        scheduler.start();

        AtomicInteger counter = new AtomicInteger(0);
        Task t1 = new Task(1, "Job1", 10, 1, () -> counter.addAndGet(1));
        Task t2 = new Task(2, "Job2", 20, 2, () -> counter.addAndGet(2));

        scheduler.submit(t1);
        scheduler.submit(t2);

        Thread.sleep(100); // Allow tasks to finish
        scheduler.stop();

        assertEquals(3, counter.get(), "Both tasks should have executed");
    }
}

