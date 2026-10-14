package com.aerus.buffer;

import com.aerus.storage.Page;

import java.io.IOException;
import java.util.List;

public interface BufferPool {

    /**
     * @return
     * @throws IOException
     */
    Page newPage() throws IOException;

    /**
     * @param pageId
     * @return
     * @throws IOException
     */
    Page fetchPage(int pageId) throws IOException;

    /**
     * @param pageId
     * @param dirty
     * @return
     */
    boolean unpinPage(int pageId, boolean dirty);

    /**
     * @param pageId
     * @return
     * @throws IOException
     */
    boolean flushPage(int pageId) throws IOException;

    /**
     * @throws IOException
     */
    void flushAll() throws IOException;

    /**
     * @return
     */
    List<DirtyEntry> dirtyPageTable();
}

