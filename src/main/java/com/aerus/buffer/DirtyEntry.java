package com.aerus.buffer;

/**
 * @param pageId
 * @param recLSN
 */
public record DirtyEntry(int pageId, long recLSN) { }

