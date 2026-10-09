package com.aerus.wal;

import com.aerus.common.Constants;


public class LogRecord {

    /** ARIES log record types. */
    public enum Type {
        BEGIN,
        COMMIT,
        ABORT,
        END,
        UPDATE,
        CLR,
        NTA_END,
        CKPT_BEGIN,
        CKPT_END
    }

    private long lsn;
    private Type type;
    private long txnId;
    private long prevLsn;

    // ── UPDATE / CLR fields (filled in Stage 4) ─────────────────────────

    private int pageId;
    private int offset;
    private byte[] beforeImage;
    private byte[] afterImage;

    // ── CLR-specific ────────────────────────────────────────────────────

    /** For CLRs: the undoNext pointer — where to continue the undo chain. */
    private long undoNext;

    // ── Logical undo info (filled in Stage 6) ───────────────────────────

    /** Logical undo type (e.g. IDX_INSERT, IDX_DELETE). */
    private int logicalType;
    private long key;
    private long value;

    public LogRecord() {
        this.lsn = Constants.INVALID_LSN;
        this.prevLsn = Constants.INVALID_LSN;
        this.undoNext = Constants.INVALID_LSN;
    }

    // ── accessors ───────────────────────────────────────────────────────

    public long getLsn()          { return lsn; }
    public void setLsn(long l)    { this.lsn = l; }
    public Type getType()         { return type; }
    public void setType(Type t)   { this.type = t; }
    public long getTxnId()        { return txnId; }
    public void setTxnId(long id) { this.txnId = id; }
    public long getPrevLsn()      { return prevLsn; }
    public void setPrevLsn(long l){ this.prevLsn = l; }

    public int getPageId()                { return pageId; }
    public void setPageId(int id)         { this.pageId = id; }
    public int getOffset()                { return offset; }
    public void setOffset(int off)        { this.offset = off; }
    public byte[] getBeforeImage()        { return beforeImage; }
    public void setBeforeImage(byte[] bi) { this.beforeImage = bi; }
    public byte[] getAfterImage()         { return afterImage; }
    public void setAfterImage(byte[] ai)  { this.afterImage = ai; }

    public long getUndoNext()             { return undoNext; }
    public void setUndoNext(long l)       { this.undoNext = l; }

    public int getLogicalType()           { return logicalType; }
    public void setLogicalType(int lt)    { this.logicalType = lt; }
    public long getKey()                  { return key; }
    public void setKey(long k)            { this.key = k; }
    public long getValue()                { return value; }
    public void setValue(long v)          { this.value = v; }

    @Override
    public String toString() {
        return "LogRecord{lsn=" + lsn + ", type=" + type
                + ", txn=" + txnId + ", prevLsn=" + prevLsn + '}';
    }
}

