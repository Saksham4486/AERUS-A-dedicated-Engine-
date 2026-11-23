package com.aerus.engine.scheduling;

/**
 * Represents a discrete task/query/transaction to be executed by TaskScheduler.
 * Supports both standard Runnables and steppable time-sliced workloads for Round Robin.
 */
public class Task implements Comparable<Task> {
    private final int id;
    private final String name;
    private int executionCost; // estimated cost or remaining burst
    private int priority;      // lower number = higher priority
    private final Runnable workload;
    private final SteppableWorkload steppableWorkload;

    private int timeExecuted = 0;
    private int contextSwitches = 0;
    private final long submitTime;
    private long startTime = -1;
    private long finishTime = -1;

    public Task(int id, String name, int executionCost, int priority, Runnable workload) {
        this.id = id;
        this.name = name;
        this.executionCost = executionCost;
        this.priority = priority;
        this.workload = workload;
        this.steppableWorkload = null;
        this.submitTime = System.currentTimeMillis();
    }

    public Task(int id, String name, int executionCost, int priority, SteppableWorkload steppableWorkload) {
        this.id = id;
        this.name = name;
        this.executionCost = executionCost;
        this.priority = priority;
        this.workload = null;
        this.steppableWorkload = steppableWorkload;
        this.submitTime = System.currentTimeMillis();
    }

    public int getId() { return id; }
    public String getName() { return name; }
    public int getExecutionCost() { return executionCost; }
    public void setExecutionCost(int cost) { this.executionCost = cost; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }

    public long getSubmitTime() { return submitTime; }
    public long getStartTime() { return startTime; }
    public long getFinishTime() { return finishTime; }
    public int getContextSwitches() { return contextSwitches; }
    public int getTimeExecuted() { return timeExecuted; }

    public void markStarted() {
        if (startTime == -1) {
            startTime = System.currentTimeMillis();
        }
    }

    public void markFinished() {
        finishTime = System.currentTimeMillis();
    }

    /**
     * Executes the task completely (used by non-preemptive policies: FCFS, SJF, Priority).
     */
    public void execute() {
        markStarted();
        if (workload != null) {
            workload.run();
        } else if (steppableWorkload != null) {
            steppableWorkload.step(Integer.MAX_VALUE);
        }
        markFinished();
    }

    /**
     * Executes a time-slice (quantum in milliseconds) for Round Robin scheduling.
     * Returns DONE if work is finished, or YIELDED if it needs another time slice.
     */
    public TaskStatus step(int sliceMillis) {
        markStarted();
        contextSwitches++;
        if (steppableWorkload != null) {
            TaskStatus status = steppableWorkload.step(sliceMillis);
            if (status == TaskStatus.DONE) {
                markFinished();
            }
            return status;
        }

        // Standard workload with executionCost
        int executeNow = Math.min(sliceMillis, executionCost);
        try {
            if (executeNow > 0) {
                Thread.sleep(executeNow);
            }
        } catch (InterruptedException ignored) {}

        executionCost -= executeNow;
        timeExecuted += executeNow;

        if (executionCost <= 0) {
            if (workload != null) {
                workload.run();
            }
            markFinished();
            return TaskStatus.DONE;
        } else {
            return TaskStatus.YIELDED;
        }
    }

    @Override
    public int compareTo(Task other) {
        return Integer.compare(this.priority, other.priority);
    }
}
