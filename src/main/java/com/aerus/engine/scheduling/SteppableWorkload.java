package com.aerus.engine.scheduling;

/**
 * Functional interface for steppable task work.
 * Takes a time quantum (slice in milliseconds) and returns DONE or YIELDED.
 */
@FunctionalInterface
public interface SteppableWorkload {
    TaskStatus step(int sliceMillis);
}
