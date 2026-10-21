package com.aerus.storage;

import com.aerus.common.AerusException;

/**
 * Thrown when a page fails CRC32 verification, indicating a torn or corrupted write.
 */
public class TornPageException extends AerusException {
    private final int pageId;
    private final int expectedChecksum;
    private final int actualChecksum;

    public TornPageException(int pageId, int expectedChecksum, int actualChecksum) {
        super(String.format("Torn/corrupted page detected for page %d (expected checksum 0x%08X, got 0x%08X)",
                pageId, expectedChecksum, actualChecksum));
        this.pageId = pageId;
        this.expectedChecksum = expectedChecksum;
        this.actualChecksum = actualChecksum;
    }

    public int getPageId() {
        return pageId;
    }

    public int getExpectedChecksum() {
        return expectedChecksum;
    }

    public int getActualChecksum() {
        return actualChecksum;
    }
}

