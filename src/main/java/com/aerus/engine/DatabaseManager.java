package com.aerus.engine;

import com.aerus.buffer.BufferPool;
import com.aerus.buffer.BufferPoolManager;
import com.aerus.buffer.LruReplacer;
import com.aerus.index.BTree;
import com.aerus.storage.FileDiskManager;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Manages multiple database instances under a root data directory.
 * Each database has its own folder containing data.aerus and B+Tree root.
 */
public class DatabaseManager implements Closeable {

    private final Path dataDir;
    private final Map<String, DatabaseContext> openDatabases = new HashMap<>();
    private String currentDbName;

    public static class DatabaseContext implements Closeable {
        public final String name;
        public final Path dbPath;
        public final FileDiskManager diskManager;
        public final BufferPoolManager bufferPool;
        public final BTree bTree;

        public DatabaseContext(String name, Path dbPath, FileDiskManager diskManager, BufferPoolManager bufferPool, BTree bTree) {
            this.name = name;
            this.dbPath = dbPath;
            this.diskManager = diskManager;
            this.bufferPool = bufferPool;
            this.bTree = bTree;
        }

        @Override
        public void close() throws IOException {
            bufferPool.flushAll();
            diskManager.close();
        }
    }

    public DatabaseManager(Path dataDir) throws IOException {
        this.dataDir = dataDir;
        if (!Files.exists(dataDir)) {
            Files.createDirectories(dataDir);
        }
        // Initialize default "main" database
        createDatabaseIfNotExists("main");
        useDatabase("main");
    }

    public synchronized String getCurrentDbName() {
        return currentDbName;
    }

    public synchronized DatabaseContext getCurrentDatabase() {
        return openDatabases.get(currentDbName);
    }

    /**
     * Creates a new database directory and file structure.
     */
    public synchronized void createDatabase(String name) throws IOException {
        validateDbName(name);
        Path dbDir = dataDir.resolve(name);
        if (Files.exists(dbDir)) {
            throw new IOException("Database '" + name + "' already exists.");
        }
        Files.createDirectories(dbDir);
        Path file = dbDir.resolve("data.aerus");
        FileDiskManager dm = new FileDiskManager(file);
        BufferPoolManager bp = new BufferPoolManager(20, dm, new LruReplacer(20));
        BTree tree = new BTree(bp, -1);
        tree.createIfEmpty();
        dm.setRootPageId(tree.getRootPageId());
        dm.sync();
        DatabaseContext ctx = new DatabaseContext(name, file, dm, bp, tree);
        openDatabases.put(name, ctx);
    }

    public synchronized void createDatabaseIfNotExists(String name) throws IOException {
        Path dbDir = dataDir.resolve(name);
        if (!Files.exists(dbDir)) {
            createDatabase(name);
        } else if (!openDatabases.containsKey(name)) {
            loadDatabase(name);
        }
    }

    public synchronized void useDatabase(String name) throws IOException {
        validateDbName(name);
        Path dbDir = dataDir.resolve(name);
        if (!Files.exists(dbDir)) {
            throw new IOException("Database '" + name + "' does not exist.");
        }
        if (!openDatabases.containsKey(name)) {
            loadDatabase(name);
        }
        this.currentDbName = name;
    }

    private void loadDatabase(String name) throws IOException {
        Path file = dataDir.resolve(name).resolve("data.aerus");
        FileDiskManager dm = new FileDiskManager(file);
        BufferPoolManager bp = new BufferPoolManager(20, dm, new LruReplacer(20));
        int rootId = dm.getRootPageId();
        BTree tree = new BTree(bp, rootId);
        if (rootId == com.aerus.common.Constants.INVALID_PAGE_ID) {
            tree.createIfEmpty();
            dm.setRootPageId(tree.getRootPageId());
        }
        openDatabases.put(name, new DatabaseContext(name, file, dm, bp, tree));
    }

    public synchronized Set<String> listDatabases() {
        String[] dirs = dataDir.toFile().list((dir, name) -> dir.toPath().resolve(name).toFile().isDirectory());
        if (dirs == null) return Collections.emptySet();
        return Set.of(dirs);
    }

    private void validateDbName(String name) {
        if (name == null || !name.matches("^[A-Za-z][A-Za-z0-9_]{0,31}$")) {
            throw new IllegalArgumentException("Invalid database name '" + name + "'. Must match [A-Za-z][A-Za-z0-9_]{0,31}");
        }
    }

    @Override
    public synchronized void close() throws IOException {
        for (DatabaseContext ctx : openDatabases.values()) {
            ctx.close();
        }
        openDatabases.clear();
    }
}
