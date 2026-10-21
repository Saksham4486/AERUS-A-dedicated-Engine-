package com.aerus.storage;

import com.aerus.common.Bytes;
import com.aerus.common.Constants;


public class Page {

    private final byte[] data;

    private int pageId;
    private int pinCount;

    private boolean dirty;

    private long recLSN;
    public Page() {
        this(Constants.INVALID_PAGE_ID);
    }

    /** Create a new, zeroed page with the given page id. */
    public Page(int pageId) {
        this.data = new byte[Constants.PAGE_SIZE];
        this.pageId = pageId;
        this.pinCount = 0;
        this.dirty = false;
        this.recLSN = Constants.INVALID_LSN;
    }


    public byte[] getData() {
        return data;
    }

    public long getLsn() {
        return Bytes.getLong(data, Constants.PAGE_LSN_OFFSET);
    }

    public void setLsn(long lsn) {
        Bytes.putLong(data, Constants.PAGE_LSN_OFFSET, lsn);
    }


    public int getPageId() { return pageId; }
    public void setPageId(int id) { this.pageId = id; }

    public int getPinCount() { return pinCount; }
    public void pin()   { pinCount++; }
    public void unpin() { pinCount--; }

    public boolean isDirty() { return dirty; }
    public void setDirty(boolean dirty) { this.dirty = dirty; }

    public long getRecLSN() { return recLSN; }
    public void setRecLSN(long lsn) { this.recLSN = lsn; }

    /**
      @param lsn the LSN of the log record that just modified this page
     */
    public void noteUpdate(long lsn) {
        setLsn(lsn);
        if (!dirty) {
            dirty = true;
            recLSN = lsn;
        }
    }
    public void resetMeta() {
        pageId = Constants.INVALID_PAGE_ID;
        pinCount = 0;
        dirty = false;
        recLSN = Constants.INVALID_LSN;
    }

    @Override
    public String toString() {
        return "Page{id=" + pageId
                + ", lsn=" + getLsn()
                + ", pin=" + pinCount
                + ", dirty=" + dirty
                + ", recLSN=" + recLSN + '}';
    }
}

