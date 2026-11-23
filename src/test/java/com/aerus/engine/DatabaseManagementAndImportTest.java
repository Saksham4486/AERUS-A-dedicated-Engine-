package com.aerus.engine;

import com.aerus.buffer.BufferPool;
import com.aerus.buffer.BufferPoolManager;
import com.aerus.buffer.LruReplacer;
import com.aerus.engine.scheduling.ResourceManager;
import com.aerus.index.BTree;
import com.aerus.storage.FileDiskManager;
import com.aerus.txn.LockManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class DatabaseManagementAndImportTest {

    @TempDir
    Path tempDir;

    private DatabaseManager dbManager;
    private LockManager lockManager;
    private ResourceManager resourceManager;
    private ExecutionEngine engine;

    @BeforeEach
    void setUp() throws IOException {
        dbManager = new DatabaseManager(tempDir.resolve("aerus_data"));
        lockManager = new LockManager(2000);
        resourceManager = new ResourceManager(20, 50, 10);
        engine = new ExecutionEngine(dbManager, lockManager, resourceManager);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (dbManager != null) {
            dbManager.close();
        }
    }

    @Test
    @DisplayName("CREATE DATABASE and USE DATABASE commands")
    void testCreateAndUseDatabase() throws IOException {
        String res = engine.executeQuery("CREATE DATABASE sales", null);
        assertTrue(res.contains("SUCCESS: Database 'sales' created"));

        res = engine.executeQuery("SHOW DATABASES", null);
        assertTrue(res.contains("sales"));
        assertTrue(res.contains("main"));

        res = engine.executeQuery("USE sales", null);
        assertTrue(res.contains("Using database 'sales'"));
        assertEquals("sales", dbManager.getCurrentDbName());

        // Insert into sales database
        engine.executeQuery("INSERT 10 500", null);
        res = engine.executeQuery("GET 10", null);
        assertTrue(res.contains("500"));

        // Switch to main database
        engine.executeQuery("USE main", null);
        res = engine.executeQuery("GET 10", null);
        assertTrue(res.contains("NOT_FOUND")); // key 10 was inserted into sales, not main
    }

    @Test
    @DisplayName("IMPORT DATABASE from file with mixed CSV and SQL statements")
    void testImportDatabase() throws IOException {
        // Create an import file
        File importFile = tempDir.resolve("dump.sql").toFile();
        try (FileWriter writer = new FileWriter(importFile)) {
            writer.write("# Database Import Dump\n");
            writer.write("INSERT 100 1000\n");
            writer.write("INSERT 200 2000\n");
            writer.write("300,3000\n"); // CSV format
            writer.write("400,4000\n");
        }

        String res = engine.executeQuery("IMPORT DATABASE " + importFile.getAbsolutePath() + " INTO imported_db", null);
        assertTrue(res.contains("SUCCESS: Imported file"));
        assertTrue(res.contains("4 records imported successfully"));

        // Check data in imported_db
        res = engine.executeQuery("GET 100", null);
        assertTrue(res.contains("1000"));
        res = engine.executeQuery("GET 300", null);
        assertTrue(res.contains("3000"));
    }
}
