package com.aerus.engine;

import com.aerus.buffer.BufferPool;
import com.aerus.buffer.BufferPoolManager;
import com.aerus.buffer.LruReplacer;
import com.aerus.index.BTree;
import com.aerus.storage.FileDiskManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class ExecutionEngineTest {

    @TempDir
    Path tempDir;

    private FileDiskManager diskManager;
    private BufferPool bufferPool;
    private BTree bTree;
    private ExecutionEngine engine;

    @BeforeEach
    public void setUp() throws IOException {
        Path dbPath = tempDir.resolve("test_engine.db");
        diskManager = new FileDiskManager(dbPath);
        bufferPool = new BufferPoolManager(20, diskManager, new LruReplacer(20));
        bTree = new BTree(bufferPool, -1);
        bTree.createIfEmpty();
        engine = new ExecutionEngine(bufferPool, bTree);
    }

    @AfterEach
    public void tearDown() throws IOException {
        bufferPool.flushAll();
        diskManager.close();
    }

    @Test
    public void testManualCrudQueries() throws IOException {
        // Insert
        String res = engine.executeQuery("INSERT 101 5000", null);
        assertTrue(res.contains("SUCCESS"));

        // Select
        res = engine.executeQuery("SELECT WHERE key = 101", null);
        assertTrue(res.contains("value=5000"));

        // Scan
        engine.executeQuery("INSERT 102 6000", null);
        engine.executeQuery("INSERT 103 7000", null);
        res = engine.executeQuery("SCAN 100 105", null);
        assertTrue(res.contains("101"));
        assertTrue(res.contains("102"));
        assertTrue(res.contains("103"));

        // Delete
        res = engine.executeQuery("DELETE 102", null);
        assertTrue(res.contains("SUCCESS"));
        res = engine.executeQuery("GET 102", null);
        assertTrue(res.contains("NOT_FOUND"));
    }

    @Test
    public void testTransactionalRollback() throws IOException {
        // Insert baseline
        engine.executeQuery("INSERT 42 1000", null);

        // Begin txn
        String startRes = engine.executeQuery("BEGIN", null);
        long txnId = Long.parseLong(startRes.split(" ")[1]);

        // Mutate within txn
        engine.executeQuery("INSERT 42 9999", txnId);
        engine.executeQuery("INSERT 43 8888", txnId);

        assertEquals(9999L, bTree.get(42));
        assertEquals(8888L, bTree.get(43));

        // Rollback txn
        String rollbackRes = engine.executeQuery("ROLLBACK", txnId);
        assertTrue(rollbackRes.contains("aborted"));

        // 42 should revert back to 1000, 43 should be gone
        assertEquals(1000L, bTree.get(42));
        assertNull(bTree.get(43));
    }
}

