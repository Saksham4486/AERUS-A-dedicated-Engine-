package com.aerus.index;

import com.aerus.buffer.BufferPool;
import com.aerus.buffer.BufferPoolManager;
import com.aerus.buffer.LruReplacer;
import com.aerus.storage.FileDiskManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

public class BTreeTest {

    @TempDir
    Path tempDir;

    private FileDiskManager diskManager;
    private BufferPool bufferPool;
    private BTree bTree;

    @BeforeEach
    public void setUp() throws IOException {
        Path dbPath = tempDir.resolve("test_btree.db");
        diskManager = new FileDiskManager(dbPath);
        bufferPool = new BufferPoolManager(50, diskManager, new LruReplacer(50));
        bTree = new BTree(bufferPool, -1);
    }

    @AfterEach
    public void tearDown() throws IOException {
        bufferPool.flushAll();
        diskManager.close();
    }

    @Test
    public void testBasicPutGetDelete() throws IOException {
        assertNull(bTree.get(10));

        bTree.put(10, 100);
        bTree.put(20, 200);
        bTree.put(5, 50);

        assertEquals(100L, bTree.get(10));
        assertEquals(200L, bTree.get(20));
        assertEquals(50L, bTree.get(5));
        assertNull(bTree.get(15));

        // Update existing key
        bTree.put(10, 999);
        assertEquals(999L, bTree.get(10));

        // Delete
        assertTrue(bTree.delete(20));
        assertNull(bTree.get(20));
        assertFalse(bTree.delete(20));
    }

    @Test
    public void testSplitsAndRandomData() throws IOException {
        TreeMap<Long, Long> model = new TreeMap<>();
        Random rng = new Random(42);

        // Insert 1000 items (forcing multiple splits in leaves and internal nodes)
        for (int i = 0; i < 1000; i++) {
            long key = rng.nextInt(10000);
            long val = key * 3;
            model.put(key, val);
            bTree.put(key, val);
        }

        // Verify all exist and match model
        for (var entry : model.entrySet()) {
            assertEquals(entry.getValue(), bTree.get(entry.getKey()));
        }

        // Range scan verification
        List<BTree.Entry> scanResults = bTree.scan(1000, 3000);
        var subMap = model.subMap(1000L, true, 3000L, true);
        assertEquals(subMap.size(), scanResults.size());
        int idx = 0;
        for (var entry : subMap.entrySet()) {
            assertEquals(entry.getKey(), scanResults.get(idx).key);
            assertEquals(entry.getValue(), scanResults.get(idx).value);
            idx++;
        }
    }
}

