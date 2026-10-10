package com.aerus.engine.scheduling;

/**
 * Represents a discrete task/transaction to be executed.
 */
public class Task implements Comparable<Task> {
    private final int id;
    private final String name;
    private final int executionCost; // estimated time or burst cost
    private final int priority;      // lower number = higher priority
    private final Runnable workload;

    public Task(int id, String name, int executionCost, int priority, Runnable workload) {
        this.id = id;
        this.name = name;
        this.executionCost = executionCost;
        this.priority = priority;
        this.workload = workload;
    }

    public int getId() { return id; }
    public String getName() { return name; }
    public int getExecutionCost() { return executionCost; }
    public int getPriority() { return priority; }
    
    public void execute() {
        workload.run();
    }

    @Override
    public int compareTo(Task other) {
        return Integer.compare(this.priority, other.priority);
    }
}

