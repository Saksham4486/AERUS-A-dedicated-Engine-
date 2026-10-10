package com.aerus.engine.scheduling;

import java.util.Comparator;
import java.util.LinkedList;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * CPU Scheduling Simulator integrated into the Execution Engine.
 * Supports FCFS, SJF, Priority, and basic Round Robin logic mapping.
 */
public class TaskScheduler {

    private final SchedulingPolicy policy;
    private final Queue<Task> readyQueue;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition taskAvailable = lock.newCondition();
    private final AtomicBoolean isRunning = new AtomicBoolean(true);
    private Thread workerThread;

    public TaskScheduler(SchedulingPolicy policy) {
        this.policy = policy;
        switch (policy) {
            case SJF:
                this.readyQueue = new PriorityQueue<>(Comparator.comparingInt(Task::getExecutionCost));
                break;
            case PRIORITY:
                this.readyQueue = new PriorityQueue<>(); // Uses Task's compareTo (priority)
                break;
            case FCFS:
            case ROUND_ROBIN:
            default:
                this.readyQueue = new LinkedList<>(); // FIFO
                break;
        }
    }

    public void start() {
        workerThread = new Thread(this::dispatch);
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
        // Simulates complete execution for non-preemptive scheduling (FCFS, SJF, Priority)
        System.out.println("Executing Task [" + task.getName() + "] using " + policy);
        task.execute();
    }

    private void executeRoundRobin(Task task) {
        // In a real OS, RR is preemptive. Since this is an application-level
        // engine thread pool, we simulate a time slice by running the task 
        // (assume it voluntarily yields or breaks work up if possible).
        // For demonstration of the model, we execute it directly, but in a 
        // full distributed model, the task would have a 'step()' method.
        System.out.println("Executing Time-Slice for Task [" + task.getName() + "] using ROUND_ROBIN");
        task.execute(); 
        
        // If task was partially completed (stateful task), we would re-submit:
        // if (!task.isFinished()) { submit(task); }
    }
}

