package com.aerus.wal;

import com.aerus.common.AerusException;
import com.aerus.common.Constants;
import com.aerus.storage.ChannelStorageFile;
import com.aerus.storage.StorageFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class Stage2LogManagerTest {

    @TempDir
    Path tempDir;

    private Path logPath;
    private LogManager logManager;

    @BeforeEach
    void setUp() throws IOException {
        logPath = tempDir.resolve("test.wal");
        logManager = new LogManager(logPath);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (logManager != null) {
            try {
                logManager.close();
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    @DisplayName("Log record serialization and deserialization round trip")
    void testRecordSerializationRoundTrip() {
        LogRecord r = new LogRecord(LogType.UPDATE, 42, 100);
        r.setLsn(200);
        r.setPageId(5);
        r.setOffset(64);
        r.setUndoNext(100);
        r.setUndoOp(UndoOp.LOGICAL);
        r.setKey(12345L);
        r.setValue(67890L);
        r.setBeforeImage(new byte[]{1, 2, 3});
        r.setAfterImage(new byte[]{4, 5, 6, 7});

        Map<Long, Long> att = new HashMap<>();
        att.put(42L, 200L);
        att.put(43L, 150L);
        r.setAtt(att);

        Map<Integer, Long> dpt = new HashMap<>();
        dpt.put(5, 100L);
        dpt.put(6, 150L);
        r.setDpt(dpt);

        byte[] serialized = r.serialize();
        LogRecord recovered = LogRecord.deserialize(serialized);

        assertEquals(r.getLsn(), recovered.getLsn());
        assertEquals(r.getLogType(), recovered.getLogType());
        assertEquals(r.getTxnId(), recovered.getTxnId());
        assertEquals(r.getPrevLsn(), recovered.getPrevLsn());
        assertEquals(r.getPageId(), recovered.getPageId());
        assertEquals(r.getOffset(), recovered.getOffset());
        assertEquals(r.getUndoNext(), recovered.getUndoNext());
        assertEquals(r.getUndoOp(), recovered.getUndoOp());
        assertEquals(r.getKey(), recovered.getKey());
        assertEquals(r.getValue(), recovered.getValue());
        assertArrayEquals(r.getBeforeImage(), recovered.getBeforeImage());
        assertArrayEquals(r.getAfterImage(), recovered.getAfterImage());
        assertEquals(r.getAtt(), recovered.getAtt());
        assertEquals(r.getDpt(), recovered.getDpt());
    }

    @Test
    @DisplayName("Append and read round trip via LogManager")
    void testAppendAndRead() throws IOException {
        LogRecord rec1 = new LogRecord(LogType.BEGIN, 1, Constants.INVALID_LSN);
        long lsn1 = logManager.append(rec1);

        LogRecord rec2 = new LogRecord(LogType.UPDATE, 1, lsn1);
        rec2.setPageId(2);
        rec2.setOffset(10);
        rec2.setBeforeImage(new byte[]{10});
        rec2.setAfterImage(new byte[]{20});
        long lsn2 = logManager.append(rec2);

        LogRecord rec3 = new LogRecord(LogType.COMMIT, 1, lsn2);
        long lsn3 = logManager.append(rec3);

        logManager.flush();

        assertEquals(lsn1, rec1.getLsn());
        assertEquals(lsn2, rec2.getLsn());
        assertEquals(lsn3, rec3.getLsn());

        LogRecord read1 = logManager.read(lsn1);
        assertEquals(LogType.BEGIN, read1.getLogType());
        assertEquals(1, read1.getTxnId());

        LogRecord read2 = logManager.read(lsn2);
        assertEquals(LogType.UPDATE, read2.getLogType());
        assertArrayEquals(new byte[]{20}, read2.getAfterImage());

        LogRecord read3 = logManager.read(lsn3);
        assertEquals(LogType.COMMIT, read3.getLogType());
    }

    @Test
    @DisplayName("Torn tail is safely truncated upon restart")
    void testTornTailTruncated() throws IOException {
        long lsn1 = logManager.append(new LogRecord(LogType.BEGIN, 1, -1));
        long lsn2 = logManager.append(new LogRecord(LogType.COMMIT, 1, lsn1));
        logManager.flush();
        long validEndLsn = logManager.endLsn();

        // Append a third record and simulate crash mid-write
        long lsn3 = logManager.append(new LogRecord(LogType.BEGIN, 2, -1));
        logManager.close();

        // Artificially cut the file mid-frame of record 3
        StorageFile file = new ChannelStorageFile(logPath);
        long fullSize = file.size();
        assertTrue(fullSize > validEndLsn);
        file.truncate(validEndLsn + 5); // cut 5 bytes into frame 3
        file.close();

        // Reopen logManager: should truncate torn tail back to validEndLsn
        LogManager reopened = new LogManager(logPath);
        assertEquals(validEndLsn, reopened.endLsn());

        // Can still read valid records 1 and 2
        LogRecord r1 = reopened.read(lsn1);
        assertEquals(LogType.BEGIN, r1.getLogType());
        LogRecord r2 = reopened.read(lsn2);
        assertEquals(LogType.COMMIT, r2.getLogType());
        reopened.close();
    }

    @Test
    @DisplayName("Corrupt middle frame before master checkpoint refuses to open")
    void testCorruptMiddleRefuses() throws IOException {
        long lsn1 = logManager.append(new LogRecord(LogType.BEGIN, 1, -1));
        long lsn2 = logManager.append(new LogRecord(LogType.CKPT_BEGIN, 0, -1));
        logManager.flush();
        logManager.setMaster(lsn2);
        long lsn3 = logManager.append(new LogRecord(LogType.CKPT_END, 0, -1));
        logManager.close();

        // Corrupt frame 1 (prior to master checkpoint LSN)
        StorageFile file = new ChannelStorageFile(logPath);
        ByteBuffer bad = ByteBuffer.allocate(4);
        bad.putInt(0xDEADBEEF);
        bad.flip();
        file.write(bad, lsn1 + LogManager.FRAME_HEADER_SIZE); // corrupt payload of lsn1
        file.close();

        // Reopen must throw AerusException refusing to start
        assertThrows(AerusException.class, () -> new LogManager(logPath));
    }

    @Test
    @DisplayName("Session generation increments and persists")
    void testSessionGenerationIncrement() throws IOException {
        assertEquals(1, logManager.getSessionGen());
        logManager.close();

        LogManager reopened1 = new LogManager(logPath);
        assertEquals(2, reopened1.getSessionGen());
        reopened1.close();

        LogManager reopened2 = new LogManager(logPath);
        assertEquals(3, reopened2.getSessionGen());
        reopened2.close();
    }

    @Test
    @DisplayName("flushUpTo semantics")
    void testFlushUpTo() throws IOException {
        long lsn1 = logManager.append(new LogRecord(LogType.BEGIN, 1, -1));
        long lsn2 = logManager.append(new LogRecord(LogType.UPDATE, 1, lsn1));

        assertTrue(logManager.flushedLsn() < lsn2);
        logManager.flushUpTo(lsn2);
        assertTrue(logManager.flushedLsn() >= lsn2);
    }
}

