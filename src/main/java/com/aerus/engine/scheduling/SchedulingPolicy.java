package com.aerus.engine.scheduling;

import java.util.Comparator;

public enum SchedulingPolicy {
    FCFS,             // First Come First Serve (FIFO Queue)
    SJF,              // Shortest Job First (Min Heap by execution cost)
    PRIORITY,         // Priority Scheduling (Min Heap by Priority)
    ROUND_ROBIN       // Round Robin (Time-sliced / Fair Share)
}

