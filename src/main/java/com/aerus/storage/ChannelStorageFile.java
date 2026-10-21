package com.aerus.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Standard OS FileChannel implementation of StorageFile.
 */
public class ChannelStorageFile implements StorageFile {
    private final FileChannel channel;
    private final Path path;

    public ChannelStorageFile(Path path) throws IOException {
        this.path = path;
        this.channel = FileChannel.open(path,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
                StandardOpenOption.CREATE);
    }

    @Override
    public int read(ByteBuffer dst, long position) throws IOException {
        return channel.read(dst, position);
    }

    @Override
    public int write(ByteBuffer src, long position) throws IOException {
        return channel.write(src, position);
    }

    @Override
    public long size() throws IOException {
        return channel.size();
    }

    @Override
    public void truncate(long size) throws IOException {
        channel.truncate(size);
    }

    @Override
    public void force(boolean metaData) throws IOException {
        channel.force(metaData);
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    public Path getPath() {
        return path;
    }
}

