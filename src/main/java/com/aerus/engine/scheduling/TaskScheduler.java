package com.aerus.engine.scheduling;

import java.util.Comparator;
import java.util.LinkedList;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Task Scheduler supporting multiple CPU scheduling algorithms:
 * - ROUND_ROBIN (Default major scheduling algorithm with preemption and configurable quantum)
 * - FCFS (First-Come, First-Served)
 * - SJF (Shortest Job First)
 * - PRIORITY (Priority-based scheduling)
 */
public class TaskScheduler {

    public static final int DEFAULT_TIME_QUANTUM_MS = 20;

    private volatile SchedulingPolicy policy;
    private final int timeQuantumMs;

    private final Queue<Task> readyQueue;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition taskAvailable = lock.newCondition();
    private final AtomicBoolean isRunning = new AtomicBoolean(true);
    private Thread workerThread;

    // Execution metrics
    private final AtomicInteger completedTasks = new AtomicInteger(0);
    private final AtomicInteger totalContextSwitches = new AtomicInteger(0);

    public TaskScheduler() {
        this(SchedulingPolicy.ROUND_ROBIN, DEFAULT_TIME_QUANTUM_MS);
    }

    public TaskScheduler(SchedulingPolicy policy) {
        this(policy, DEFAULT_TIME_QUANTUM_MS);
    }

    public TaskScheduler(SchedulingPolicy policy, int timeQuantumMs) {
        this.policy = policy;
        this.timeQuantumMs = timeQuantumMs;
        switch (policy) {
            case SJF:
                this.readyQueue = new PriorityQueue<>(Comparator.comparingInt(Task::getExecutionCost));
                break;
            case PRIORITY:
                this.readyQueue = new PriorityQueue<>(); // Uses Task.compareTo (priority)
                break;
            case FCFS:
            case ROUND_ROBIN:
            default:
                this.readyQueue = new LinkedList<>(); // FIFO
                break;
        }
    }

    public SchedulingPolicy getPolicy() {
        return policy;
    }

    public void setPolicy(SchedulingPolicy newPolicy) {
        lock.lock();
        try {
            this.policy = newPolicy;
        } finally {
            lock.unlock();
        }
    }

    public int getTimeQuantumMs() {
        return timeQuantumMs;
    }

    public int getCompletedTasks() {
        return completedTasks.get();
    }

    public int getTotalContextSwitches() {
        return totalContextSwitches.get();
    }

    public void start() {
        workerThread = new Thread(this::dispatch);
        workerThread.setName("AERUS-TaskSchedulerWorker");
        workerThread.setDaemon(true);
        workerThread.start();
    }

    public void stop() {
        isRunning.set(false);
        lock.lock();
        try {
            taskAvailable.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public void submit(Task task) {
        lock.lock();
        try {
            readyQueue.offer(task);
            taskAvailable.signal();
        } finally {
            lock.unlock();
        }
    }

    public int getQueueSize() {
        lock.lock();
        try {
            return readyQueue.size();
        } finally {
            lock.unlock();
        }
    }

    private void dispatch() {
        while (isRunning.get()) {
            Task task = null;
            lock.lock();
            try {
                while (readyQueue.isEmpty() && isRunning.get()) {
                    taskAvailable.await();
                }
                if (!isRunning.get()) return;

                task = readyQueue.poll();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } finally {
                lock.unlock();
            }

            if (task != null) {
                if (policy == SchedulingPolicy.ROUND_ROBIN) {
                    executeRoundRobin(task);
                } else {
                    executeStandard(task);
                }
            }
        }
    }

    private void executeStandard(Task task) {
        task.execute();
        completedTasks.incrementAndGet();
    }

    private void executeRoundRobin(Task task) {
        TaskStatus status = task.step(timeQuantumMs);
        totalContextSwitches.incrementAndGet();

        if (status == TaskStatus.DONE) {
            completedTasks.incrementAndGet();
        } else {
            // Task yielded (still has work left) -> Re-queue at the back of the queue
            lock.lock();
            try {
                readyQueue.offer(task);
                taskAvailable.signal();
            } finally {
                lock.unlock();
            }
        }
    }
}
