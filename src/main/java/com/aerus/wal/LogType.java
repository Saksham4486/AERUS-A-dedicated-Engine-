package com.aerus.wal;

/**
 * ARIES and AERUS log record types.
 * Appended at the end only to maintain backward serialization compatibility.
 */
public enum LogType {
    BEGIN,
    UPDATE,
    COMMIT,
    ABORT,
    END,
    CLR,
    CKPT_BEGIN,
    CKPT_END,
    NTA_END,
    SAVEPOINT,
    FPI
}

