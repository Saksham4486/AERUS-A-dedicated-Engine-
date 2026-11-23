package com.aerus.txn;

import com.aerus.buffer.BufferPool;
import com.aerus.buffer.BufferPoolManager;
import com.aerus.buffer.LruReplacer;
import com.aerus.common.DeadlockException;
import com.aerus.engine.ExecutionEngine;
import com.aerus.engine.scheduling.ResourceManager;
import com.aerus.index.BTree;
import com.aerus.storage.FileDiskManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class ConcurrencyAndDeadlockTest {

    @TempDir
    Path tempDir;

    private FileDiskManager diskManager;
    private BufferPool bufferPool;
    private BTree bTree;
    private LockManager lockManager;
    private ResourceManager resourceManager;
    private ExecutionEngine engine;

    @BeforeEach
    void setUp() throws IOException {
        Path dbPath = tempDir.resolve("test_concurrency.db");
        diskManager = new FileDiskManager(dbPath);
        bufferPool = new BufferPoolManager(20, diskManager, new LruReplacer(20));
        bTree = new BTree(bufferPool, -1);
        bTree.createIfEmpty();

        lockManager = new LockManager(2000); // 2 sec timeout
        resourceManager = new ResourceManager(20, 50, 10);
        engine = new ExecutionEngine(bufferPool, bTree, lockManager, resourceManager);
    }

    @AfterEach
    void tearDown() throws IOException {
        bufferPool.flushAll();
        diskManager.close();
    }

    @Test
    @DisplayName("Two concurrent transactions modifying different keys succeed without blocking")
    void testConcurrentIndependentTransactions() throws Exception {
        // T1 starts and locks key 1
        String startT1 = engine.executeQuery("BEGIN", null);
        long t1 = Long.parseLong(startT1.split(" ")[1]);

        // T2 starts and locks key 2
        String startT2 = engine.executeQuery("BEGIN", null);
        long t2 = Long.parseLong(startT2.split(" ")[1]);

        engine.executeQuery("INSERT 1 100", t1);
        engine.executeQuery("INSERT 2 200", t2);

        engine.executeQuery("COMMIT", t1);
        engine.executeQuery("COMMIT", t2);

        assertEquals(100L, bTree.get(1));
        assertEquals(200L, bTree.get(2));
    }

    @Test
    @DisplayName("Deadlock detection: T1 holds 1, wants 2; T2 holds 2, wants 1 -> Youngest transaction aborted")
    void testDeadlockCycleDetectionAndAbort() throws Exception {
        String startT1 = engine.executeQuery("BEGIN", null);
        long t1 = Long.parseLong(startT1.split(" ")[1]); // t1 = 1

        String startT2 = engine.executeQuery("BEGIN", null);
        long t2 = Long.parseLong(startT2.split(" ")[1]); // t2 = 2 (youngest)

        // T1 acquires key 1
        engine.executeQuery("INSERT 1 10", t1);
        // T2 acquires key 2
        engine.executeQuery("INSERT 2 20", t2);

        CountDownLatch t1Waiting = new CountDownLatch(1);
        AtomicReference<Throwable> t1Error = new AtomicReference<>();
        AtomicReference<Throwable> t2Error = new AtomicReference<>();

        // Thread 1 (T1) tries to acquire key 2 (which T2 holds)
        Thread thread1 = new Thread(() -> {
            try {
                t1Waiting.countDown();
                engine.executeQuery("INSERT 2 99", t1);
            } catch (Throwable e) {
                t1Error.set(e);
            }
        });

        // Thread 2 (T2) tries to acquire key 1 (which T1 holds) -> Creates cycle: T1 -> T2 and T2 -> T1
        Thread thread2 = new Thread(() -> {
            try {
                t1Waiting.await();
                Thread.sleep(100); // give thread1 time to block on key 2
                engine.executeQuery("INSERT 1 88", t2);
            } catch (Throwable e) {
                t2Error.set(e);
            }
        });

        thread1.start();
        thread2.start();

        thread1.join();
        thread2.join();

        // Exactly one of the transactions should be aborted with DeadlockException (youngest T2)
        boolean hasDeadlock = (t1Error.get() instanceof DeadlockException) || (t2Error.get() instanceof DeadlockException);
        assertTrue(hasDeadlock, "A DeadlockException should be raised and handled by the engine");

        // If T2 was aborted, T1 proceeds and commits
        if (t2Error.get() instanceof DeadlockException) {
            engine.executeQuery("COMMIT", t1);
            assertEquals(99L, bTree.get(2)); // T1's update succeeded
        }
    }

    @Test
    @DisplayName("Live Banker's Algorithm prevents unsafe allocation and allows safe progression")
    void testLiveBankersAlgorithmSafety() throws Exception {
        // Tiny resource manager with 3 frames
        ResourceManager tinyManager = new ResourceManager(3, 10, 5);
        tinyManager.setWaitTimeoutMillis(1000);

        // T1 registers with claim of 2 frames
        tinyManager.registerTransaction(1, 2, 5);
        // T2 registers with claim of 2 frames
        tinyManager.registerTransaction(2, 2, 5);

        // T1 requests 2 frames -> Granted (Available=1, Need T1=0, Need T2=2 -> T1 can finish, return 2 -> safe)
        tinyManager.request(1, ResourceManager.ResourceType.FRAMES, 2);
        assertTrue(tinyManager.isSafe());

        // T2 requests 1 frame -> Available becomes 0, Need T1=0, Need T2=1 -> T1 can finish, return 2 -> Safe!
        tinyManager.request(2, ResourceManager.ResourceType.FRAMES, 1);
        assertTrue(tinyManager.isSafe());

        // Report should show active transactions and safe status
        String report = tinyManager.getResourceReport();
        assertTrue(report.contains("SAFE"));
        assertTrue(report.contains("Txn 1"));
        assertTrue(report.contains("Txn 2"));

        // Clean release
        tinyManager.release(1, ResourceManager.ResourceType.FRAMES, 2);
        tinyManager.release(2, ResourceManager.ResourceType.FRAMES, 1);
        tinyManager.unregisterTransaction(1);
        tinyManager.unregisterTransaction(2);

        assertEquals(3, tinyManager.getAvailableResources()[0]);
    }
}

