package com.aerus.txn;

import com.aerus.common.Constants;


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

