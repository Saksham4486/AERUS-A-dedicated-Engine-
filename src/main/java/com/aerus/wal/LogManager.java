package com.aerus.wal;

import com.aerus.common.AerusException;
import com.aerus.common.Bytes;
import com.aerus.common.Constants;
import com.aerus.storage.ChannelStorageFile;
import com.aerus.storage.StorageFile;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * Write-Ahead Log Manager for AERUS.
 *
 * File Header:
 * [0..7]   : masterRecord (LSN of last completed checkpoint-begin)
 * [8..15]  : magic (Constants.WAL_MAGIC)
 * [16..19] : session generation (int)
 * Header total = 20 bytes.
 *
 * Log Frame:
 * [0..3]   : len (payload length in bytes)
 * [4..7]   : crc32 (computed over gen + payload)
 * [8..11]  : gen (session generation)
 * [12..]   : payload (serialized LogRecord bytes)
 *
 * LSN of a record = file offset of its frame.
 */
public class LogManager implements Closeable {

    public static final int HEADER_SIZE = 20;
    public static final int FRAME_HEADER_SIZE = 12; // len(4) + crc(4) + gen(4)

    private static final int OFFSET_MASTER = 0;
    private static final int OFFSET_MAGIC = 8;
    private static final int OFFSET_GEN = 16;

    private final StorageFile file;
    private final Path path;
    private final LogMetrics metrics = new LogMetrics();

    private long masterLsn = Constants.INVALID_LSN;
    private int sessionGen = 0;

    // Log offsets
    private long flushedLsn = 0;
    private long endLsn = 0; // points to the next byte to append

    // In-memory append buffer (accumulates frames before flush)
    private ByteBuffer appendBuffer = ByteBuffer.allocate(64 * 1024);
    private long appendBufferStartLsn = 0;

    // LRU cache for log records to service read(lsn) during rollback / recovery
    private final Map<Long, LogRecord> recordCache = new LinkedHashMap<>(128, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, LogRecord> eldest) {
            return size() > 500;
        }
    };

    public LogManager(Path path) throws IOException {
        this(path, new ChannelStorageFile(path));
    }

    public LogManager(Path path, StorageFile storageFile) throws IOException {
        this.path = path;
        this.file = storageFile;

        if (file.size() == 0) {
            initNewLog();
        } else {
            recoverAndInitLog();
        }
    }

    public LogMetrics getMetrics() {
        return metrics;
    }

    public synchronized long getMasterLsn() {
        return masterLsn;
    }

    public synchronized void setMaster(long lsn) throws IOException {
        this.masterLsn = lsn;
        ByteBuffer buf = ByteBuffer.allocate(8);
        buf.putLong(masterLsn);
        buf.flip();
        file.write(buf, OFFSET_MASTER);
        file.force(false);
        metrics.recordFsync();
    }

    public synchronized int getSessionGen() {
        return sessionGen;
    }

    public synchronized long flushedLsn() {
        return flushedLsn;
    }

    public synchronized long endLsn() {
        return endLsn;
    }

    private void initNewLog() throws IOException {
        this.masterLsn = Constants.INVALID_LSN;
        this.sessionGen = 1;

        ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE);
        buf.putLong(masterLsn);
        buf.putLong(Constants.WAL_MAGIC);
        buf.putInt(sessionGen);
        buf.flip();
        file.write(buf, 0);
        file.force(true);

        this.flushedLsn = HEADER_SIZE;
        this.endLsn = HEADER_SIZE;
        this.appendBufferStartLsn = HEADER_SIZE;
    }

    private void recoverAndInitLog() throws IOException {
        long fileSize = file.size();
        if (fileSize < HEADER_SIZE) {
            throw new AerusException("Log file smaller than header: " + fileSize);
        }

        // Read header
        ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE);
        file.read(header, 0);
        header.flip();

        this.masterLsn = header.getLong();
        long magic = header.getLong();
        if (magic != Constants.WAL_MAGIC) {
            throw new AerusException("Corrupted log file: invalid magic " + Long.toHexString(magic));
        }
        int lastGen = header.getInt();

        // Scan frames from offset HEADER_SIZE up to fileSize
        long offset = HEADER_SIZE;
        long validOffset = offset;

        while (offset + FRAME_HEADER_SIZE <= fileSize) {
            ByteBuffer frameHdr = ByteBuffer.allocate(FRAME_HEADER_SIZE);
            file.read(frameHdr, offset);
            frameHdr.flip();

            int len = frameHdr.getInt();
            int expectedCrc = frameHdr.getInt();
            int gen = frameHdr.getInt();

            if (len < 0 || len > 10 * 1024 * 1024) {
                // Invalid frame length: torn tail or corruption
                break;
            }

            if (offset + FRAME_HEADER_SIZE + len > fileSize) {
                // Partial frame cut off: torn tail
                break;
            }

            ByteBuffer payload = ByteBuffer.allocate(len);
            file.read(payload, offset + FRAME_HEADER_SIZE);
            payload.flip();

            CRC32 crc = new CRC32();
            crc.update(ByteBuffer.allocate(4).putInt(gen).array());
            crc.update(payload.array());
            int computedCrc = (int) crc.getValue();

            if (computedCrc != expectedCrc) {
                // Bad CRC: torn tail or corruption
                break;
            }

            // Valid frame
            validOffset = offset + FRAME_HEADER_SIZE + len;
            offset = validOffset;
        }

        // If corruption or torn tail is before masterLsn, refuse to start
        if (masterLsn != Constants.INVALID_LSN && validOffset < masterLsn) {
            throw new AerusException("Corrupt log: unreadable data prior to checkpoint master LSN " + masterLsn);
        }

        // Truncate torn tail if any
        if (validOffset < fileSize) {
            file.truncate(validOffset);
            file.force(true);
        }

        this.flushedLsn = validOffset;
        this.endLsn = validOffset;
        this.appendBufferStartLsn = validOffset;

        // Bump session generation and make durable before appending
        this.sessionGen = lastGen + 1;
        ByteBuffer genBuf = ByteBuffer.allocate(4);
        genBuf.putInt(sessionGen);
        genBuf.flip();
        file.write(genBuf, OFFSET_GEN);
        file.force(true);
        metrics.recordFsync();
    }

    /**
     * Appends a log record to the in-memory buffer and assigns its LSN.
     */
    public synchronized long append(LogRecord record) {
        long lsn = endLsn;
        record.setLsn(lsn);

        byte[] payload = record.serialize();
        int len = payload.length;

        CRC32 crc = new CRC32();
        crc.update(ByteBuffer.allocate(4).putInt(sessionGen).array());
        crc.update(payload);
        int crcValue = (int) crc.getValue();

        int frameSize = FRAME_HEADER_SIZE + len;
        if (appendBuffer.remaining() < frameSize) {
            flushInternal(false);
        }

        // Put frame into appendBuffer
        appendBuffer.putInt(len);
        appendBuffer.putInt(crcValue);
        appendBuffer.putInt(sessionGen);
        appendBuffer.put(payload);

        endLsn += frameSize;

        // Put into cache
        recordCache.put(lsn, record);
        metrics.recordAppend(record.getLogType(), frameSize);

        return lsn;
    }

    /**
     * Flushes all in-memory log records to disk and fsyncs.
     */
    public synchronized void flush() throws IOException {
        flushInternal(true);
    }

    /**
     * Flushes up to the target LSN (WAL rule).
     */
    public synchronized void flushUpTo(long targetLsn) throws IOException {
        if (targetLsn <= flushedLsn) {
            return;
        }
        flushInternal(true);
    }

    private void flushInternal(boolean forceSync) {
        if (appendBuffer.position() == 0) {
            if (forceSync) {
                try {
                    file.force(false);
                    metrics.recordFsync();
                } catch (IOException e) {
                    throw new AerusException("Failed to force log", e);
                }
            }
            return;
        }

        int bytesToWrite = appendBuffer.position();
        appendBuffer.flip();
        try {
            while (appendBuffer.hasRemaining()) {
                file.write(appendBuffer, appendBufferStartLsn + appendBuffer.position());
            }
            flushedLsn = appendBufferStartLsn + bytesToWrite;
            appendBuffer.clear();
            appendBufferStartLsn = flushedLsn;
            metrics.recordFlush();

            if (forceSync) {
                file.force(false);
                metrics.recordFsync();
            }
        } catch (IOException e) {
            throw new AerusException("Failed to flush log buffer", e);
        }
    }

    /**
     * Reads a log record at the given LSN.
     */
    public synchronized LogRecord read(long lsn) throws IOException {
        if (recordCache.containsKey(lsn)) {
            metrics.recordBufferHit();
            return recordCache.get(lsn);
        }

        // If in appendBuffer, flush to make it accessible or read from buffer
        if (lsn >= appendBufferStartLsn && lsn < endLsn) {
            flushInternal(false);
        }

        ByteBuffer frameHdr = ByteBuffer.allocate(FRAME_HEADER_SIZE);
        file.read(frameHdr, lsn);
        frameHdr.flip();

        int len = frameHdr.getInt();
        int expectedCrc = frameHdr.getInt();
        int gen = frameHdr.getInt();

        ByteBuffer payload = ByteBuffer.allocate(len);
        file.read(payload, lsn + FRAME_HEADER_SIZE);
        payload.flip();

        CRC32 crc = new CRC32();
        crc.update(ByteBuffer.allocate(4).putInt(gen).array());
        crc.update(payload.array());
        int computedCrc = (int) crc.getValue();

        if (computedCrc != expectedCrc) {
            throw new AerusException("Corrupted log frame at LSN " + lsn);
        }

        LogRecord rec = LogRecord.deserialize(payload.array());
        rec.setLsn(lsn);
        recordCache.put(lsn, rec);
        return rec;
    }

    private boolean closed = false;

    @Override
    public synchronized void close() throws IOException {
        if (!closed) {
            flushInternal(true);
            file.close();
            closed = true;
        }
    }
}

