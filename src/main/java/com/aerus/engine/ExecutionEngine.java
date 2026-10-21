package com.aerus.engine;

import com.aerus.buffer.BufferPool;
import com.aerus.index.BTree;
import com.aerus.txn.Transaction;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Execution Engine that parses and executes manual queries against the B+Tree and BufferPool.
 * Supports:
 * - INSERT / PUT <key> <val>
 * - SELECT / GET <key>
 * - DELETE <key>
 * - SCAN <startKey> <endKey>
 * - BEGIN / START TRANSACTION
 * - COMMIT
 * - ABORT / ROLLBACK
 */
public class ExecutionEngine {

    private final BTree bTree;
    private final BufferPool bufferPool;
    private final AtomicLong txnIdGenerator = new AtomicLong(1);

    // Active transactions: txnId -> Transaction
    private final Map<Long, Transaction> activeTxns = new HashMap<>();

    // Keep track of operations for rollback (simple transaction undo shadow)
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
        this.bufferPool = bufferPool;
        this.bTree = bTree;
    }

    /**
     * Executes a user command string and returns a human-readable result.
     * Supports both interactive single-statement queries and transactional workflows.
     */
    public synchronized String executeQuery(String query, Long activeTxnId) throws IOException {
        if (query == null || query.trim().isEmpty()) {
            return "Empty query.";
        }
        String trimmed = query.trim();
        String[] tokens = trimmed.split("\\s+");
        String cmd = tokens[0].toUpperCase();

        switch (cmd) {
            case "BEGIN":
            case "START": {
                long tid = txnIdGenerator.getAndIncrement();
                Transaction txn = new Transaction(tid);
                activeTxns.put(tid, txn);
                txnUndoLogs.put(tid, new java.util.ArrayList<>());
                return "TXN_STARTED " + tid;
            }

            case "COMMIT": {
                if (activeTxnId == null || !activeTxns.containsKey(activeTxnId)) {
                    return "ERROR: No active transaction to commit.";
                }
                Transaction txn = activeTxns.remove(activeTxnId);
                txn.setState(Transaction.State.COMMITTED);
                txnUndoLogs.remove(activeTxnId);
                bufferPool.flushAll();
                return "SUCCESS: Transaction " + activeTxnId + " committed and flushed.";
            }

            case "ABORT":
            case "ROLLBACK": {
                if (activeTxnId == null || !activeTxns.containsKey(activeTxnId)) {
                    return "ERROR: No active transaction to abort.";
                }
                Transaction txn = activeTxns.remove(activeTxnId);
                txn.setState(Transaction.State.ABORTED);
                // Undo changes made in this transaction
                List<UndoOp> ops = txnUndoLogs.remove(activeTxnId);
                if (ops != null) {
                    // Undo in reverse order
                    for (int i = ops.size() - 1; i >= 0; i--) {
                        UndoOp op = ops.get(i);
                        if (op.prevValue == null) {
                            bTree.delete(op.key);
                        } else {
                            bTree.put(op.key, op.prevValue);
                        }
                    }
                }
                return "SUCCESS: Transaction " + activeTxnId + " aborted and rolled back.";
            }

            case "INSERT":
            case "PUT": {
                // Syntax: INSERT INTO table VALUES (key, val) OR PUT key val
                long key;
                long val;
                if (tokens.length >= 3 && !tokens[1].equalsIgnoreCase("INTO")) {
                    key = Long.parseLong(tokens[1]);
                    val = Long.parseLong(tokens[2]);
                } else {
                    // parse "INSERT INTO ... VALUES (key, val)"
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

                Long oldVal = bTree.get(key);
                bTree.put(key, val);

                if (activeTxnId != null && activeTxns.containsKey(activeTxnId)) {
                    txnUndoLogs.get(activeTxnId).add(new UndoOp(key, oldVal));
                }
                return "SUCCESS: Inserted (" + key + " => " + val + ")";
            }

            case "SELECT":
            case "GET": {
                // Syntax: SELECT * FROM t WHERE key = X OR GET key
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

                Long val = bTree.get(key);
                if (val == null) {
                    return "NOT_FOUND: Key " + key + " does not exist.";
                }
                return "ROW: [key=" + key + ", value=" + val + "]";
            }

            case "DELETE": {
                // Syntax: DELETE FROM t WHERE key = X OR DELETE key
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

                Long oldVal = bTree.get(key);
                boolean deleted = bTree.delete(key);
                if (deleted && activeTxnId != null && activeTxns.containsKey(activeTxnId)) {
                    txnUndoLogs.get(activeTxnId).add(new UndoOp(key, oldVal));
                }
                return deleted ? "SUCCESS: Deleted key " + key : "NOT_FOUND: Key " + key + " not found.";
            }

            case "SCAN": {
                // Syntax: SCAN <startKey> <endKey>
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
                        "  - INSERT <key> <value>  (or INSERT INTO t VALUES (k, v))\n" +
                        "  - SELECT WHERE key = <k> (or GET <key>)\n" +
                        "  - DELETE <key> (or DELETE WHERE key = <k>)\n" +
                        "  - SCAN <startKey> <endKey>\n" +
                        "  - BEGIN\n" +
                        "  - COMMIT\n" +
                        "  - ROLLBACK\n";
            }

            default:
                return "Unknown command '" + cmd + "'. Type HELP for syntax.";
        }
    }
}

