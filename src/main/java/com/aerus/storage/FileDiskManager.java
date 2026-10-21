package com.aerus.storage;

import com.aerus.common.Bytes;
import com.aerus.common.Constants;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.CRC32;

/**
 * DiskManager implementation using 4 KB pages with header page, CRC32 checksums,
 * health tracking, and StorageFile abstraction.
 */
public class FileDiskManager implements DiskManager {

    private static final int HEADER_PAGE_ID = 0;
    
    // Header offsets in Page 0
    private static final int OFFSET_MAGIC = 0;
    private static final int OFFSET_PAGE_COUNT = 8;
    private static final int OFFSET_FREE_LIST_HEAD = 12;
    private static final int OFFSET_ROOT_PAGE_ID = 16;
    private static final int OFFSET_CATALOG_ROOT_PAGE_ID = 20;

    private final StorageFile file;
    private final Path path;

    // In-memory cache of header fields, guarded by 'this' monitor.
    private int pageCount;
    private int freeListHead;
    private int rootPageId = Constants.INVALID_PAGE_ID;
    private int catalogRootPageId = Constants.INVALID_PAGE_ID;

    private volatile Health health = Health.HEALTHY;

    /**
     * Opens or creates the database file using a default ChannelStorageFile.
     */
    public FileDiskManager(Path path) throws IOException {
        this(path, new ChannelStorageFile(path));
    }

    /**
     * Opens or creates the database file using the specified StorageFile abstraction.
     */
    public FileDiskManager(Path path, StorageFile storageFile) throws IOException {
        this.path = path;
        this.file = storageFile;

        try {
            if (file.size() == 0) {
                initNewFile();
            } else {
                loadExistingFile();
            }
        } catch (Exception e) {
            markFailed();
            throw e;
        }
    }

    public Health getHealth() {
        return health;
    }

    private void checkHealthy() {
        if (health == Health.FAILED) {
            throw new FailedStateException("FileDiskManager is in FAILED health state due to earlier I/O error.");
        }
    }

    private void markFailed() {
        this.health = Health.FAILED;
    }

    private void initNewFile() throws IOException {
        this.pageCount = 1; // Page 0 is the header
        this.freeListHead = Constants.INVALID_PAGE_ID;
        this.rootPageId = Constants.INVALID_PAGE_ID;
        this.catalogRootPageId = Constants.INVALID_PAGE_ID;
        writeHeader();
        sync();
    }

    private void loadExistingFile() throws IOException {
        byte[] headerData = new byte[Constants.PAGE_SIZE];
        readPageRaw(HEADER_PAGE_ID, headerData);

        long magic = Bytes.getLong(headerData, OFFSET_MAGIC);
        if (magic != Constants.AERUS_MAGIC) {
            throw new IOException("Invalid file format: bad magic number. Expected " 
                    + Constants.AERUS_MAGIC + ", got " + magic);
        }

        this.pageCount = Bytes.getInt(headerData, OFFSET_PAGE_COUNT);
        this.freeListHead = Bytes.getInt(headerData, OFFSET_FREE_LIST_HEAD);
        this.rootPageId = Bytes.getInt(headerData, OFFSET_ROOT_PAGE_ID);
        this.catalogRootPageId = Bytes.getInt(headerData, OFFSET_CATALOG_ROOT_PAGE_ID);

        // Normalize 0 to INVALID_PAGE_ID if legacy file had 0 for root IDs
        if (this.rootPageId == 0) {
            this.rootPageId = Constants.INVALID_PAGE_ID;
        }
        if (this.catalogRootPageId == 0) {
            this.catalogRootPageId = Constants.INVALID_PAGE_ID;
        }
    }

    private synchronized void writeHeader() throws IOException {
        byte[] headerData = new byte[Constants.PAGE_SIZE];
        Bytes.putLong(headerData, OFFSET_MAGIC, Constants.AERUS_MAGIC);
        Bytes.putInt(headerData, OFFSET_PAGE_COUNT, pageCount);
        Bytes.putInt(headerData, OFFSET_FREE_LIST_HEAD, freeListHead);
        Bytes.putInt(headerData, OFFSET_ROOT_PAGE_ID, rootPageId);
        Bytes.putInt(headerData, OFFSET_CATALOG_ROOT_PAGE_ID, catalogRootPageId);
        writePageRaw(HEADER_PAGE_ID, headerData);
    }

    @Override
    public void readPage(int pageId, byte[] out) throws IOException {
        checkHealthy();
        if (pageId <= HEADER_PAGE_ID) {
            throw new IllegalArgumentException("Cannot read meta page through public API");
        }
        if (out.length != Constants.PAGE_SIZE) {
            throw new IllegalArgumentException("Buffer size must be exactly PAGE_SIZE");
        }

        try {
            readPageRaw(pageId, out);
            // Verify checksum if page is not completely zeroed
            verifyChecksum(pageId, out);
        } catch (TornPageException tpe) {
            throw tpe;
        } catch (Exception e) {
            markFailed();
            throw e;
        }
    }

    private void readPageRaw(int pageId, byte[] out) throws IOException {
        long position = (long) pageId * Constants.PAGE_SIZE;
        ByteBuffer buffer = ByteBuffer.wrap(out);
        Arrays.fill(out, (byte) 0);
        
        while (buffer.hasRemaining()) {
            int read = file.read(buffer, position + buffer.position());
            if (read == -1) {
                // EOF reached: zeros for unwritten portion
                break;
            }
        }
    }

    private void verifyChecksum(int pageId, byte[] data) {
        // If the page is all zeros (e.g. freshly allocated page on disk), skip checksum check
        boolean allZeros = true;
        for (byte b : data) {
            if (b != 0) {
                allZeros = false;
                break;
            }
        }
        if (allZeros) {
            return;
        }

        int storedChecksum = Bytes.getInt(data, Constants.PAGE_CHECKSUM_OFFSET);
        int computedChecksum = computeChecksum(data);
        if (storedChecksum != computedChecksum) {
            throw new TornPageException(pageId, computedChecksum, storedChecksum);
        }
    }

    private int computeChecksum(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data, 0, Constants.USABLE_PAGE_SIZE);
        return (int) crc.getValue();
    }

    @Override
    public void writePage(int pageId, byte[] data) throws IOException {
        checkHealthy();
        if (pageId <= HEADER_PAGE_ID) {
            throw new IllegalArgumentException("Cannot write meta page through public API");
        }
        if (data.length != Constants.PAGE_SIZE) {
            throw new IllegalArgumentException("Data size must be exactly PAGE_SIZE");
        }

        try {
            // Compute and append CRC32 checksum at the last 4 bytes
            int checksum = computeChecksum(data);
            Bytes.putInt(data, Constants.PAGE_CHECKSUM_OFFSET, checksum);
            writePageRaw(pageId, data);
        } catch (Exception e) {
            markFailed();
            throw e;
        }
    }

    private void writePageRaw(int pageId, byte[] data) throws IOException {
        long position = (long) pageId * Constants.PAGE_SIZE;
        ByteBuffer buffer = ByteBuffer.wrap(data);
        
        while (buffer.hasRemaining()) {
            file.write(buffer, position + buffer.position());
        }
    }

    @Override
    public synchronized int allocatePage() throws IOException {
        checkHealthy();
        try {
            int allocatedId;
            if (freeListHead != Constants.INVALID_PAGE_ID) {
                allocatedId = freeListHead;
                byte[] freeNodeData = new byte[Constants.PAGE_SIZE];
                readPageRaw(allocatedId, freeNodeData);
                freeListHead = Bytes.getInt(freeNodeData, 0);
            } else {
                allocatedId = pageCount++;
            }
            writeHeader();
            return allocatedId;
        } catch (Exception e) {
            markFailed();
            throw e;
        }
    }

    @Override
    public synchronized void deallocatePage(int pageId) throws IOException {
        checkHealthy();
        if (pageId <= HEADER_PAGE_ID) {
            throw new IllegalArgumentException("Cannot deallocate header page");
        }
        if (pageId >= pageCount) {
            throw new IllegalArgumentException("Cannot deallocate unallocated page id: " + pageId);
        }

        try {
            byte[] freeNodeData = new byte[Constants.PAGE_SIZE];
            Bytes.putInt(freeNodeData, 0, freeListHead);
            // Put valid checksum on the freed page
            int checksum = computeChecksum(freeNodeData);
            Bytes.putInt(freeNodeData, Constants.PAGE_CHECKSUM_OFFSET, checksum);
            writePageRaw(pageId, freeNodeData);

            freeListHead = pageId;
            writeHeader();
        } catch (Exception e) {
            markFailed();
            throw e;
        }
    }

    @Override
    public void sync() throws IOException {
        checkHealthy();
        try {
            file.force(true);
        } catch (Exception e) {
            markFailed();
            throw e;
        }
    }

    @Override
    public void close() throws IOException {
        file.close();
    }
    
    // Header properties
    public synchronized int getPageCount() { return pageCount; }
    public synchronized int getFreeListHead() { return freeListHead; }
    public synchronized int getRootPageId() { return rootPageId; }
    public synchronized void setRootPageId(int id) throws IOException {
        this.rootPageId = id;
        writeHeader();
    }
    public synchronized int getCatalogRootPageId() { return catalogRootPageId; }
    public synchronized void setCatalogRootPageId(int id) throws IOException {
        this.catalogRootPageId = id;
        writeHeader();
    }
    public Path getPath() { return path; }
}
