package com.aerus.buffer;

import com.aerus.storage.Page;

import java.io.IOException;
import java.util.List;

/**

  <h3>Contract highlights (ARIES depends on these)</h3>
  <ul>
    <li>{@link #newPage()} returns a zeroed, pinned page.</li>
    <li>{@link #fetchPage(int)} for a page id that was allocated but never
        written returns a <b>zeroed page, not an error</b> — redo rebuilds
        it from the log.</li>
    <li>{@link #flushPage(int)} calls {@code log.flushUpTo(page.lsn())}
        <b>before</b> writing the page, then clears dirty + recLSN.</li>
    <li>When every frame is pinned, fetch/new must fail cleanly (return
        {@code null} or throw a documented exception), never deadlock.</li>
  </ul>
 */
public interface BufferPool {

    /**
     * Allocate a new page on disk and bring it into the pool, pinned.
     *
     * @return the pinned page, or {@code null} if no frame is available
     * @throws IOException on disk I/O failure
     */
    Page newPage() throws IOException;

    /**
      @param pageId the page to fetch
      @return the pinned page, or {@code null} if no frame is available
      @throws IOException on disk I/O failure
     */
    Page fetchPage(int pageId) throws IOException;

    /**
      @param pageId the page to unpin
      @param dirty  if {@code true}, mark the page dirty
      @return {@code true} if the page was found in the pool
     */
    boolean unpinPage(int pageId, boolean dirty);

    /**
     
      @param pageId the page to flush
      @return {@code true} if the page was found and flushed
      @throws IOException on disk I/O failure
     */
    boolean flushPage(int pageId) throws IOException;

    /**
      Flush every dirty page in the pool (in no particular order).
      @throws IOException on disk I/O failure
     */
    void flushAll() throws IOException;

    /**
      Return a snapshot of the dirty page table for use by checkpointing.
      @return list of (pageId, recLSN) entries for all dirty pages in the pool
     */
    List<DirtyEntry> dirtyPageTable();
}

