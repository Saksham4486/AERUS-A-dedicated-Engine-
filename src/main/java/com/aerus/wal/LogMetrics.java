package com.aerus.wal;

import java.util.concurrent.atomic.LongAdder;

/**
 * Observability metrics for the Write-Ahead Log.
 */
public class LogMetrics {
    private final LongAdder[] recordsByType;
    private final LongAdder totalBytes = new LongAdder();
    private final LongAdder flushCount = new LongAdder();
    private final LongAdder fsyncCount = new LongAdder();
    private final LongAdder bufferHits = new LongAdder();

    public LogMetrics() {
        recordsByType = new LongAdder[LogType.values().length];
        for (int i = 0; i < recordsByType.length; i++) {
            recordsByType[i] = new LongAdder();
        }
    }

    public void recordAppend(LogType type, int bytes) {
        if (type != null && type.ordinal() < recordsByType.length) {
            recordsByType[type.ordinal()].increment();
        }
        totalBytes.add(bytes);
    }

    public void recordFlush() {
        flushCount.increment();
    }

    public void recordFsync() {
        fsyncCount.increment();
    }

    public void recordBufferHit() {
        bufferHits.increment();
    }

    public long getCount(LogType type) {
        return recordsByType[type.ordinal()].sum();
    }

    public long getTotalBytes() { return totalBytes.sum(); }
    public long getFlushCount() { return flushCount.sum(); }
    public long getFsyncCount() { return fsyncCount.sum(); }
    public long getBufferHits() { return bufferHits.sum(); }
}

