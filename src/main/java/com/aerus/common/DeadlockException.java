package com.aerus.common;

/**
 * Thrown when a transaction is aborted due to a deadlock detection in the lock wait-for graph.
 */
public class DeadlockException extends AerusException {
    private final long txnId;

    public DeadlockException(long txnId, String message) {
        super(String.format("ERROR[DEADLOCK_ABORTED]: Transaction %d aborted: %s", txnId, message));
        this.txnId = txnId;
    }

    public long getTxnId() {
        return txnId;
    }
}

