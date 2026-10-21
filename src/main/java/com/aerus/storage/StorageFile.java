package com.aerus.storage;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * StorageFile abstraction providing positional read, write, truncate, force, and size operations.
 * Allows decoupling FileDiskManager and LogManager from raw disk channels for fault-injection testing.
 */
public interface StorageFile extends Closeable {
    int read(ByteBuffer dst, long position) throws IOException;
    int write(ByteBuffer src, long position) throws IOException;
    long size() throws IOException;
    void truncate(long size) throws IOException;
    void force(boolean metaData) throws IOException;
}

