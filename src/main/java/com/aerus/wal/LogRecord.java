package com.aerus.wal;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import com.aerus.common.Constants;

/**
 * In-memory representation of an ARIES/AERUS WAL log record.
 * Supports binary serialization and deserialization.
 */
public class LogRecord {

    // Exactly 9 original ARIES types in LogRecord.Type (LogType contains extended types)
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
    private LogType logType;
    private long txnId;
    private long prevLsn;

    // UPDATE / CLR / FPI fields
    private int pageId;
    private int offset;
    private byte[] beforeImage;
    private byte[] afterImage;

    // CLR-specific
    private long undoNext;

    // Logical undo
    private UndoOp undoOp = UndoOp.PHYSICAL;
    private int logicalType;
    private long key;
    private long value;

    // Checkpoint tables
    // Active Transaction Table: txnId -> lastLsn
    private Map<Long, Long> att;
    // Dirty Page Table: pageId -> recLsn
    private Map<Integer, Long> dpt;

    public LogRecord() {
        this.lsn = Constants.INVALID_LSN;
        this.prevLsn = Constants.INVALID_LSN;
        this.undoNext = Constants.INVALID_LSN;
    }

    public LogRecord(LogType logType, long txnId, long prevLsn) {
        this();
        this.logType = logType;
        this.txnId = txnId;
        this.prevLsn = prevLsn;
    }

    public long getLsn()          { return lsn; }
    public void setLsn(long l)    { this.lsn = l; }

    public LogType getLogType()   { return logType; }
    public void setLogType(LogType t) { this.logType = t; }

    /** Legacy accessor maintaining compatibility with existing LogRecord.Type */
    public Type getType() {
        if (logType == null) return null;
        return Type.valueOf(logType.name());
    }
    public void setType(Type t) {
        if (t == null) {
            this.logType = null;
        } else {
            this.logType = LogType.valueOf(t.name());
        }
    }

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

    public UndoOp getUndoOp()             { return undoOp; }
    public void setUndoOp(UndoOp op)      { this.undoOp = op; }

    public int getLogicalType()           { return logicalType; }
    public void setLogicalType(int lt)    { this.logicalType = lt; }
    public long getKey()                  { return key; }
    public void setKey(long k)            { this.key = k; }
    public long getValue()                { return value; }
    public void setValue(long v)          { this.value = v; }

    public Map<Long, Long> getAtt()       { return att; }
    public void setAtt(Map<Long, Long> att) { this.att = att; }
    public Map<Integer, Long> getDpt()    { return dpt; }
    public void setDpt(Map<Integer, Long> dpt) { this.dpt = dpt; }

    /**
     * Serializes this record into a compact byte array.
     */
    public byte[] serialize() {
        // Compute payload size
        int size = 8 + 1 + 8 + 8; // lsn, type ordinal, txnId, prevLsn
        size += 4 + 4 + 8; // pageId, offset, undoNext
        size += 1 + 4 + 8 + 8; // undoOp ordinal, logicalType, key, value

        // beforeImage length + bytes
        size += 4 + (beforeImage == null ? 0 : beforeImage.length);
        // afterImage length + bytes
        size += 4 + (afterImage == null ? 0 : afterImage.length);

        // ATT size + entries (txnId: long, lastLsn: long = 16 bytes)
        size += 4 + (att == null ? 0 : att.size() * 16);
        // DPT size + entries (pageId: int, recLsn: long = 12 bytes)
        size += 4 + (dpt == null ? 0 : dpt.size() * 12);

        byte[] buf = new byte[size];
        ByteBuffer bb = ByteBuffer.wrap(buf);

        bb.putLong(lsn);
        bb.put((byte) (logType == null ? -1 : logType.ordinal()));
        bb.putLong(txnId);
        bb.putLong(prevLsn);

        bb.putInt(pageId);
        bb.putInt(offset);
        bb.putLong(undoNext);

        bb.put((byte) (undoOp == null ? UndoOp.PHYSICAL.ordinal() : undoOp.ordinal()));
        bb.putInt(logicalType);
        bb.putLong(key);
        bb.putLong(value);

        if (beforeImage == null) {
            bb.putInt(-1);
        } else {
            bb.putInt(beforeImage.length);
            bb.put(beforeImage);
        }

        if (afterImage == null) {
            bb.putInt(-1);
        } else {
            bb.putInt(afterImage.length);
            bb.put(afterImage);
        }

        if (att == null) {
            bb.putInt(-1);
        } else {
            bb.putInt(att.size());
            for (Map.Entry<Long, Long> entry : att.entrySet()) {
                bb.putLong(entry.getKey());
                bb.putLong(entry.getValue());
            }
        }

        if (dpt == null) {
            bb.putInt(-1);
        } else {
            bb.putInt(dpt.size());
            for (Map.Entry<Integer, Long> entry : dpt.entrySet()) {
                bb.putInt(entry.getKey());
                bb.putLong(entry.getValue());
            }
        }

        return buf;
    }

    /**
     * Deserializes a LogRecord from bytes.
     */
    public static LogRecord deserialize(byte[] bytes) {
        ByteBuffer bb = ByteBuffer.wrap(bytes);
        LogRecord rec = new LogRecord();
        rec.lsn = bb.getLong();
        byte typeOrd = bb.get();
        if (typeOrd >= 0 && typeOrd < LogType.values().length) {
            rec.logType = LogType.values()[typeOrd];
        }
        rec.txnId = bb.getLong();
        rec.prevLsn = bb.getLong();

        rec.pageId = bb.getInt();
        rec.offset = bb.getInt();
        rec.undoNext = bb.getLong();

        byte undoOrd = bb.get();
        if (undoOrd >= 0 && undoOrd < UndoOp.values().length) {
            rec.undoOp = UndoOp.values()[undoOrd];
        }
        rec.logicalType = bb.getInt();
        rec.key = bb.getLong();
        rec.value = bb.getLong();

        int biLen = bb.getInt();
        if (biLen >= 0) {
            rec.beforeImage = new byte[biLen];
            bb.get(rec.beforeImage);
        }

        int aiLen = bb.getInt();
        if (aiLen >= 0) {
            rec.afterImage = new byte[aiLen];
            bb.get(rec.afterImage);
        }

        int attSize = bb.getInt();
        if (attSize >= 0) {
            rec.att = new HashMap<>(attSize);
            for (int i = 0; i < attSize; i++) {
                long tId = bb.getLong();
                long lastLsn = bb.getLong();
                rec.att.put(tId, lastLsn);
            }
        }

        int dptSize = bb.getInt();
        if (dptSize >= 0) {
            rec.dpt = new HashMap<>(dptSize);
            for (int i = 0; i < dptSize; i++) {
                int pId = bb.getInt();
                long recLsn = bb.getLong();
                rec.dpt.put(pId, recLsn);
            }
        }

        return rec;
    }

    @Override
    public String toString() {
        return "LogRecord{lsn=" + lsn + ", type=" + logType
                + ", txn=" + txnId + ", prevLsn=" + prevLsn
                + (undoOp == UndoOp.LOGICAL ? (", logicalKey=" + key) : "")
                + '}';
    }
}
