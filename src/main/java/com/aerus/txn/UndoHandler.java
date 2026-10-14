package com.aerus.txn;

import com.aerus.wal.LogRecord;

/**
 * Callback used by the recovery / rollback code to undo a logical operation.
 *
 * Physical undo (byte-range restore) is handled generically by the WAL layer.
 * Logical undo (e.g. "remove the key I just inserted") requires knowledge of
 * the data structure, so the structure registers itself as the UndoHandler.
 *
 * <p>Currently only the B+Tree implements this (Stage 6).
 */
public interface UndoHandler {

    /**
     * Perform the inverse of the logical operation described in {@code rec}.
     *
     * @param txn the transaction performing the undo
     * @param rec the UPDATE record whose logical effect must be reversed
     */
    void undoLogical(Transaction txn, LogRecord rec);
}

