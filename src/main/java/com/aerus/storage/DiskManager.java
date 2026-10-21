package com.aerus.storage;


public interface DiskManager {

    /**
    
      @param pageId the zero-based page number
      @param out    a byte array of exactly {@code PAGE_SIZE} bytes
      @throws java.io.IOException on I/O failure
     */
    void readPage(int pageId, byte[] out) throws java.io.IOException;

    /**
      @param pageId the zero-based page number
      @param data   a byte array of exactly {@code PAGE_SIZE} bytes
      @throws java.io.IOException on I/O failure
     */
    void writePage(int pageId, byte[] data) throws java.io.IOException;

    /**
     
      @return the id of the newly allocated page
      @throws java.io.IOException on I/O failure
     */
    int allocatePage() throws java.io.IOException;

    /**
      {@link #allocatePage()} call.
     
      @param pageId the page to deallocate
      @throws java.io.IOException on I/O failure
     */
    void deallocatePage(int pageId) throws java.io.IOException;

    /**
     * Force all pending writes to durable storage ({@code fsync}).
     *
     * @throws java.io.IOException on I/O failure
     */
    void sync() throws java.io.IOException;

    /**
     * Close the underlying file.  Does <b>not</b> implicitly sync.
     *
     * @throws java.io.IOException on I/O failure
     */
    void close() throws java.io.IOException;
}

