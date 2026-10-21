package com.aerus.wal;

import com.aerus.common.Constants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LogRecordTest {

    @Test
    @DisplayName("Default LSN is INVALID_LSN")
    void defaultLsn() {
        LogRecord r = new LogRecord();
        assertEquals(Constants.INVALID_LSN, r.getLsn());
    }

    @Test
    @DisplayName("Default prevLsn is INVALID_LSN")
    void defaultPrevLsn() {
        LogRecord r = new LogRecord();
        assertEquals(Constants.INVALID_LSN, r.getPrevLsn());
    }

    @Test
    @DisplayName("Default undoNext is INVALID_LSN")
    void defaultUndoNext() {
        LogRecord r = new LogRecord();
        assertEquals(Constants.INVALID_LSN, r.getUndoNext());
    }

    @Test
    @DisplayName("Type round trip")
    void typeRoundTrip() {
        for (LogRecord.Type t : LogRecord.Type.values()) {
            LogRecord r = new LogRecord();
            r.setType(t);
            assertEquals(t, r.getType());
        }
    }

    @Test
    @DisplayName("All ARIES record types are present")
    void allTypesPresent() {
        LogRecord.Type[] types = LogRecord.Type.values();
        assertEquals(9, types.length,
                "Expected 9 ARIES types: BEGIN, COMMIT, ABORT, END, UPDATE, CLR, NTA_END, CKPT_BEGIN, CKPT_END");
    }

    @Test
    @DisplayName("Field setters and getters round trip")
    void fieldRoundTrip() {
        LogRecord r = new LogRecord();
        r.setLsn(100);
        r.setTxnId(7);
        r.setPrevLsn(50);
        r.setPageId(3);
        r.setOffset(128);
        r.setUndoNext(25);
        r.setKey(999L);
        r.setValue(888L);
        r.setLogicalType(1);
        r.setBeforeImage(new byte[]{1, 2, 3});
        r.setAfterImage(new byte[]{4, 5, 6});

        assertEquals(100, r.getLsn());
        assertEquals(7, r.getTxnId());
        assertEquals(50, r.getPrevLsn());
        assertEquals(3, r.getPageId());
        assertEquals(128, r.getOffset());
        assertEquals(25, r.getUndoNext());
        assertEquals(999L, r.getKey());
        assertEquals(888L, r.getValue());
        assertEquals(1, r.getLogicalType());
        assertArrayEquals(new byte[]{1, 2, 3}, r.getBeforeImage());
        assertArrayEquals(new byte[]{4, 5, 6}, r.getAfterImage());
    }

    @Test
    @DisplayName("toString is informative")
    void toStringWorks() {
        LogRecord r = new LogRecord();
        r.setType(LogRecord.Type.UPDATE);
        r.setTxnId(42);
        String s = r.toString();
        assertTrue(s.contains("UPDATE"));
        assertTrue(s.contains("42"));
    }
}

