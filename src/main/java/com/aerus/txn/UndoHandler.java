package com.aerus.txn;

import com.aerus.wal.LogRecord;


public interface UndoHandler {

    /**
   
     * @param txn the transaction performing the undo
     * @param rec the UPDATE record whose logical effect must be reversed
     */
    void undoLogical(Transaction txn, LogRecord rec);
}

