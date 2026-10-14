package com.aerus.txn;

import com.aerus.common.Constants;

/**
 * Represents a single database transaction.
 *
 * Tracks the transaction id, its current state, and the LSN chain used by
 * ARIES for per-transaction undo (prevLsn → backward chain).  The WAL and
 * recovery layers use this to build CLRs and walk the undo chain.
 *
 * <p>Full implementation comes in Stage 4; this skeleton provides just
 * enough for the interfaces to compile.
 */
public class Transaction {

    /** Possible states of a transaction. */
    public enum State { ACTIVE, COMMITTING, ABORTING, COMMITTED, ABORTED }

    private final long txnId;
    private State state;

    /** LSN of the most recent log record written by this transaction. */
    private long lastLsn;

    /** LSN of the most recent savepoint (or INVALID_LSN if none). */
    private long savepointLsn;

    public Transaction(long txnId) {
        this.txnId = txnId;
        this.state = State.ACTIVE;
        this.lastLsn = Constants.INVALID_LSN;
        this.savepointLsn = Constants.INVALID_LSN;
    }

    public long getTxnId()       { return txnId; }
    public State getState()      { return state; }
    public void setState(State s){ this.state = s; }
    public long getLastLsn()     { return lastLsn; }
    public void setLastLsn(long l){ this.lastLsn = l; }
    public long getSavepointLsn(){ return savepointLsn; }
    public void setSavepointLsn(long l){ this.savepointLsn = l; }

    @Override
    public String toString() {
        return "Txn{id=" + txnId + ", state=" + state + ", lastLsn=" + lastLsn + '}';
    }
}

