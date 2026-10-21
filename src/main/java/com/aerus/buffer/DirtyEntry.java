package com.aerus.buffer;

/**
  Entry in the dirty page table maintained by the BufferPool and consumed
  by ARIES checkpointing / recovery.
 
  @param pageId the dirty page's id
  @param recLSN the LSN at which the page first became dirty (since the last flush)
 */
public record DirtyEntry(int pageId, long recLSN) { }

