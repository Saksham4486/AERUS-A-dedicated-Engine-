package com.aerus.engine;

import com.aerus.buffer.BufferPool;
import com.aerus.common.DeadlockException;
import com.aerus.engine.scheduling.ResourceManager;
import com.aerus.index.BTree;
import com.aerus.txn.LockManager;
import com.aerus.txn.Transaction;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Execution Engine connecting:
 * - Multi-database management (CREATE DATABASE, USE, SHOW DATABASES, IMPORT DATABASE)
 * - Query parser and executor (CRUD + Transactions)
 * - Strict 2PL LockManager with Wait-For Graph cycle detection
 * - Live Banker's Algorithm ResourceManager (Real admission control on Frames, LockSlots, Sessions)
 */
public class ExecutionEngine {

    private DatabaseManager databaseManager;
    private BTree bTree;
    private BufferPool bufferPool;
    private final LockManager lockManager;
    private final ResourceManager resourceManager;

    private final AtomicLong txnIdGenerator = new AtomicLong(1);

    // Active transactions: txnId -> Transaction
    private final Map<Long, Transaction> activeTxns = new HashMap<>();

    // Keep track of operations for rollback (transaction undo log)
    private static class UndoOp {
        final long key;
        final Long prevValue; // null if inserted newly

        UndoOp(long key, Long prevValue) {
            this.key = key;
            this.prevValue = prevValue;
        }
    }
    private final Map<Long, java.util.List<UndoOp>> txnUndoLogs = new HashMap<>();

    public ExecutionEngine(BufferPool bufferPool, BTree bTree) {
        this(bufferPool, bTree, new LockManager(), new ResourceManager(50, 100, 20));
    }

    public ExecutionEngine(BufferPool bufferPool, BTree bTree, LockManager lockManager, ResourceManager resourceManager) {
        this.bufferPool = bufferPool;
        this.bTree = bTree;
        this.lockManager = lockManager;
        this.resourceManager = resourceManager;
    }

    public ExecutionEngine(DatabaseManager dbManager, LockManager lockManager, ResourceManager resourceManager) {
        this.databaseManager = dbManager;
        this.lockManager = lockManager;
        this.resourceManager = resourceManager;
        updateActiveDatabase();
    }

    private void updateActiveDatabase() {
        if (databaseManager != null) {
            DatabaseManager.DatabaseContext ctx = databaseManager.getCurrentDatabase();
            if (ctx != null) {
                this.bufferPool = ctx.bufferPool;
                this.bTree = ctx.bTree;
            }
        }
    }

    public LockManager getLockManager() {
        return lockManager;
    }

    public ResourceManager getResourceManager() {
        return resourceManager;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public void setDatabaseManager(DatabaseManager dbManager) {
        this.databaseManager = dbManager;
        updateActiveDatabase();
    }

    /**
     * Executes a user query string.
     */
    public String executeQuery(String query, Long activeTxnId) throws IOException {
        if (query == null || query.trim().isEmpty()) {
            return "Empty query.";
        }
        String trimmed = query.trim();
        String[] tokens = trimmed.split("\\s+");
        String cmd = tokens[0].toUpperCase();

        // Check if transaction was already aborted by deadlock detection
        if (activeTxnId != null && lockManager.isAborted(activeTxnId)) {
            performRollback(activeTxnId);
            return "ERROR[DEADLOCK_ABORTED]: Transaction " + activeTxnId + " was chosen as deadlock victim and rolled back.";
        }

        try {
            switch (cmd) {
                case "CREATE": {
                    if (tokens.length >= 3 && tokens[1].equalsIgnoreCase("DATABASE")) {
                        String dbName = tokens[2];
                        if (databaseManager == null) {
                            databaseManager = new DatabaseManager(Paths.get("data"));
                        }
                        databaseManager.createDatabase(dbName);
                        return "SUCCESS: Database '" + dbName + "' created.";
                    }
                    return "Syntax error: CREATE DATABASE <name>";
                }

                case "USE": {
                    if (tokens.length >= 2) {
                        String dbName = tokens[1];
                        if (activeTxnId != null && activeTxns.containsKey(activeTxnId)) {
                            return "ERROR: Cannot switch database while transaction " + activeTxnId + " is active.";
                        }
                        if (databaseManager == null) {
                            databaseManager = new DatabaseManager(Paths.get("data"));
                        }
                        databaseManager.useDatabase(dbName);
                        updateActiveDatabase();
                        return "SUCCESS: Using database '" + dbName + "'.";
                    }
                    return "Syntax error: USE <database_name>";
                }

                case "SHOW": {
                    if (tokens.length >= 2) {
                        if (tokens[1].equalsIgnoreCase("DATABASES")) {
                            if (databaseManager == null) {
                                databaseManager = new DatabaseManager(Paths.get("data"));
                            }
                            Set<String> dbs = databaseManager.listDatabases();
                            StringBuilder sb = new StringBuilder("DATABASES (" + dbs.size() + "):\n");
                            String cur = databaseManager.getCurrentDbName();
                            for (String db : dbs) {
                                sb.append("  ").append(db).append(db.equals(cur) ? " (current)" : "").append("\n");
                            }
                            return sb.toString().trim();
                        } else if (tokens[1].equalsIgnoreCase("RESOURCES")) {
                            return resourceManager.getResourceReport();
                        }
                    }
                    return "Syntax error: SHOW DATABASES | SHOW RESOURCES";
                }

                case "IMPORT": {
                    // Syntax: IMPORT DATABASE <filepath> [INTO <dbname>] OR IMPORT <filepath>
                    if (tokens.length >= 2) {
                        String filePath;
                        String targetDb = null;
                        if (tokens[1].equalsIgnoreCase("DATABASE") && tokens.length >= 3) {
                            filePath = tokens[2];
                            if (tokens.length >= 5 && tokens[3].equalsIgnoreCase("INTO")) {
                                targetDb = tokens[4];
                            }
                        } else {
                            filePath = tokens[1];
                            if (tokens.length >= 4 && tokens[2].equalsIgnoreCase("INTO")) {
                                targetDb = tokens[3];
                            }
                        }
                        return importDatabase(filePath, targetDb);
                    }
                    return "Syntax error: IMPORT DATABASE <filepath> [INTO <dbname>]";
                }

                case "BEGIN":
                case "START": {
                    // Parse optional claim: BEGIN CLAIM frames=5 locks=10
                    int claimFrames = 10;
                    int claimLocks = 20;
                    if (tokens.length >= 3 && tokens[1].equalsIgnoreCase("CLAIM")) {
                        for (int i = 2; i < tokens.length; i++) {
                            String[] kv = tokens[i].split("=");
                            if (kv[0].equalsIgnoreCase("frames")) claimFrames = Integer.parseInt(kv[1]);
                            if (kv[0].equalsIgnoreCase("locks")) claimLocks = Integer.parseInt(kv[1]);
                        }
                    }

                    long tid = txnIdGenerator.getAndIncrement();
                    Transaction txn = new Transaction(tid);
                    activeTxns.put(tid, txn);
                    txnUndoLogs.put(tid, new java.util.ArrayList<>());

                    // Register with Banker's Algorithm ResourceManager (Real Admission Control)
                    resourceManager.registerTransaction(tid, claimFrames, claimLocks);

                    return "TXN_STARTED " + tid;
                }

                case "COMMIT": {
                    if (activeTxnId == null || !activeTxns.containsKey(activeTxnId)) {
                        return "ERROR: No active transaction to commit.";
                    }
                    Transaction txn = activeTxns.remove(activeTxnId);
                    txn.setState(Transaction.State.COMMITTED);
                    txnUndoLogs.remove(activeTxnId);

                    // Strict 2PL: Release all locks held by transaction
                    lockManager.releaseAll(activeTxnId);

                    // Banker's Algorithm: Release resources
                    resourceManager.unregisterTransaction(activeTxnId);

                    if (bufferPool != null) {
                        bufferPool.flushAll();
                    }
                    return "SUCCESS: Transaction " + activeTxnId + " committed, locks released, and pages flushed.";
                }

                case "ABORT":
                case "ROLLBACK": {
                    if (activeTxnId == null || !activeTxns.containsKey(activeTxnId)) {
                        return "ERROR: No active transaction to abort.";
                    }
                    performRollback(activeTxnId);
                    return "SUCCESS: Transaction " + activeTxnId + " aborted and rolled back.";
                }

                case "INSERT":
                case "PUT": {
                    long key;
                    long val;
                    if (tokens.length >= 3 && !tokens[1].equalsIgnoreCase("INTO")) {
                        key = Long.parseLong(tokens[1]);
                        val = Long.parseLong(tokens[2]);
                    } else {
                        int valIdx = trimmed.toUpperCase().indexOf("VALUES");
                        if (valIdx == -1) {
                            return "Syntax error. Expected: INSERT <key> <val> OR INSERT INTO ... VALUES (key, val)";
                        }
                        String paren = trimmed.substring(valIdx + 6).replaceAll("[()\\s]", "");
                        String[] parts = paren.split(",");
                        if (parts.length < 2) return "Syntax error: Expected (key, value)";
                        key = Long.parseLong(parts[0]);
                        val = Long.parseLong(parts[1]);
                    }

                    long effectiveTxnId = activeTxnId != null ? activeTxnId : 0L;
                    if (effectiveTxnId != 0L) {
                        acquireKeyLock(effectiveTxnId, key, LockManager.LockMode.EXCLUSIVE);
                    }

                    Long oldVal = bTree.get(key);
                    bTree.put(key, val);

                    if (activeTxnId != null && activeTxns.containsKey(activeTxnId)) {
                        txnUndoLogs.get(activeTxnId).add(new UndoOp(key, oldVal));
                    }
                    return "SUCCESS: Inserted (" + key + " => " + val + ")";
                }

                case "SELECT":
                case "GET": {
                    long key;
                    if (cmd.equals("GET")) {
                        if (tokens.length < 2) return "Syntax error: GET <key>";
                        key = Long.parseLong(tokens[1]);
                    } else {
                        int eqIdx = trimmed.indexOf("=");
                        if (eqIdx != -1) {
                            String numPart = trimmed.substring(eqIdx + 1).trim();
                            key = Long.parseLong(numPart.split("\\s+")[0]);
                        } else if (tokens.length >= 2 && tokens[1].matches("-?\\d+")) {
                            key = Long.parseLong(tokens[1]);
                        } else {
                            return "Syntax error. Expected: SELECT WHERE key = <val> OR GET <key>";
                        }
                    }

                    long effectiveTxnId = activeTxnId != null ? activeTxnId : 0L;
                    if (effectiveTxnId != 0L) {
                        acquireKeyLock(effectiveTxnId, key, LockManager.LockMode.SHARED);
                    }

                    Long val = bTree.get(key);
                    if (val == null) {
                        return "NOT_FOUND: Key " + key + " does not exist.";
                    }
                    return "ROW: [key=" + key + ", value=" + val + "]";
                }

                case "DELETE": {
                    long key;
                    int eqIdx = trimmed.indexOf("=");
                    if (eqIdx != -1) {
                        String numPart = trimmed.substring(eqIdx + 1).trim();
                        key = Long.parseLong(numPart.split("\\s+")[0]);
                    } else if (tokens.length >= 2 && tokens[1].matches("-?\\d+")) {
                        key = Long.parseLong(tokens[1]);
                    } else if (tokens.length >= 3 && tokens[2].matches("-?\\d+")) {
                        key = Long.parseLong(tokens[2]);
                    } else {
                        return "Syntax error: DELETE <key> OR DELETE WHERE key = <val>";
                    }

                    long effectiveTxnId = activeTxnId != null ? activeTxnId : 0L;
                    if (effectiveTxnId != 0L) {
                        acquireKeyLock(effectiveTxnId, key, LockManager.LockMode.EXCLUSIVE);
                    }

                    Long oldVal = bTree.get(key);
                    boolean deleted = bTree.delete(key);
                    if (deleted && activeTxnId != null && activeTxns.containsKey(activeTxnId)) {
                        txnUndoLogs.get(activeTxnId).add(new UndoOp(key, oldVal));
                    }
                    return deleted ? "SUCCESS: Deleted key " + key : "NOT_FOUND: Key " + key + " not found.";
                }

                case "SCAN": {
                    if (tokens.length < 3) return "Syntax error: SCAN <startKey> <endKey>";
                    long start = Long.parseLong(tokens[1]);
                    long end = Long.parseLong(tokens[2]);
                    List<BTree.Entry> entries = bTree.scan(start, end);
                    StringBuilder sb = new StringBuilder();
                    sb.append("SCAN RESULT (").append(entries.size()).append(" rows):\n");
                    for (BTree.Entry e : entries) {
                        sb.append("  ").append(e.toString()).append("\n");
                    }
                    return sb.toString().trim();
                }

                case "HELP": {
                    return "Supported Queries:\n" +
                            "  - CREATE DATABASE <name>\n" +
                            "  - USE <database_name>\n" +
                            "  - SHOW DATABASES\n" +
                            "  - IMPORT DATABASE <filepath> [INTO <dbname>]\n" +
                            "  - INSERT <key> <value>  (or INSERT INTO t VALUES (k, v))\n" +
                            "  - SELECT WHERE key = <k> (or GET <key>)\n" +
                            "  - DELETE <key> (or DELETE WHERE key = <k>)\n" +
                            "  - SCAN <startKey> <endKey>\n" +
                            "  - BEGIN [CLAIM frames=<n> locks=<m>]\n" +
                            "  - COMMIT\n" +
                            "  - ROLLBACK\n" +
                            "  - SHOW RESOURCES (Live Banker's Algorithm Allocation & Safety)\n";
                }

                default:
                    return "Unknown command '" + cmd + "'. Type HELP for syntax.";
            }
        } catch (DeadlockException de) {
            if (activeTxnId != null) {
                performRollback(activeTxnId);
            }
            throw de;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("Operation interrupted while waiting for lock/resources: " + ie.getMessage());
        }
    }

    /**
     * Imports data from a SQL or CSV script file into the database.
     * Supports line-by-line commands: INSERT/PUT or key,val CSV rows.
     */
    public String importDatabase(String filePath, String targetDb) throws IOException {
        File file = new File(filePath);
        if (!file.exists()) {
            throw new IOException("Import file not found: " + filePath);
        }

        if (targetDb != null && databaseManager != null) {
            databaseManager.createDatabaseIfNotExists(targetDb);
            databaseManager.useDatabase(targetDb);
            updateActiveDatabase();
        }

        int successCount = 0;
        int errorCount = 0;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("--")) {
                    continue; // skip comments / empty
                }

                try {
                    // Check if it's a CSV format: key,value
                    if (line.contains(",") && !line.toUpperCase().startsWith("INSERT")) {
                        String[] parts = line.split(",");
                        if (parts.length >= 2) {
                            long k = Long.parseLong(parts[0].trim());
                            long v = Long.parseLong(parts[1].trim());
                            bTree.put(k, v);
                            successCount++;
                            continue;
                        }
                    }

                    // Otherwise execute as SQL/engine command
                    String res = executeQuery(line, null);
                    if (res.startsWith("SUCCESS") || res.startsWith("ROW")) {
                        successCount++;
                    } else {
                        errorCount++;
                    }
                } catch (Exception ex) {
                    errorCount++;
                }
            }
        }

        if (bufferPool != null) {
            bufferPool.flushAll();
        }
        return String.format("SUCCESS: Imported file '%s' -> %d records imported successfully, %d errors.",
                file.getName(), successCount, errorCount);
    }

    private void acquireKeyLock(long txnId, long key, LockManager.LockMode mode) throws InterruptedException, IOException {
        resourceManager.request(txnId, ResourceManager.ResourceType.LOCK_SLOTS, 1);

        String resourceId = "key:" + key;
        boolean granted = lockManager.acquire(txnId, resourceId, mode);
        if (!granted) {
            resourceManager.release(txnId, ResourceManager.ResourceType.LOCK_SLOTS, 1);
            throw new IOException("Lock acquisition timeout on " + resourceId);
        }
    }

    private synchronized void performRollback(long txnId) throws IOException {
        Transaction txn = activeTxns.remove(txnId);
        if (txn != null) {
            txn.setState(Transaction.State.ABORTED);
        }
        List<UndoOp> ops = txnUndoLogs.remove(txnId);
        if (ops != null) {
            for (int i = ops.size() - 1; i >= 0; i--) {
                UndoOp op = ops.get(i);
                if (op.prevValue == null) {
                    bTree.delete(op.key);
                } else {
                    bTree.put(op.key, op.prevValue);
                }
            }
        }
        lockManager.releaseAll(txnId);
        resourceManager.unregisterTransaction(txnId);
    }
}
