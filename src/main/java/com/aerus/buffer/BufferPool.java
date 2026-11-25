package com.aerus.buffer;

import com.aerus.storage.Page;

import java.io.IOException;
import java.util.List;

public interface BufferPool {

    Page newPage() throws IOException;

    Page fetchPage(int pageId) throws IOException;

    boolean unpinPage(int pageId, boolean dirty);

    boolean flushPage(int pageId) throws IOException;

    void flushAll() throws IOException;

    List<DirtyEntry> dirtyPageTable();
}
