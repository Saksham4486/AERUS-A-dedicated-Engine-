package com.aerus.storage;

import com.aerus.common.Bytes;
import com.aerus.common.Constants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

public class Stage1StorageHardeningTest {

    @TempDir
    Path tempDir;

    private Path dbPath;
    private FileDiskManager diskManager;

    @BeforeEach
    void setUp() throws IOException {
        dbPath = tempDir.resolve("stage1_test.aerus");
        diskManager = new FileDiskManager(dbPath);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (diskManager != null) {
            diskManager.close();
        }
    }

    @Test
    @DisplayName("CRC32 page checksum detects a flipped byte")
    void testChecksumDetectsFlippedByte() throws IOException {
        int pageId = diskManager.allocatePage();
        byte[] data = new byte[Constants.PAGE_SIZE];
        data[10] = 0x42;
        data[100] = 0x77;
        diskManager.writePage(pageId, data);

        // Read works cleanly
        byte[] readData = new byte[Constants.PAGE_SIZE];
        diskManager.readPage(pageId, readData);
        assertEquals(0x42, readData[10]);

        // Manually flip a byte on disk using raw StorageFile
        StorageFile file = new ChannelStorageFile(dbPath);
        ByteBuffer buf = ByteBuffer.allocate(1);
        buf.put((byte) 0x99);
        buf.flip();
        file.write(buf, (long) pageId * Constants.PAGE_SIZE + 10);
        file.close();

        // Reading now should throw TornPageException
        assertThrows(TornPageException.class, () -> {
            diskManager.readPage(pageId, new byte[Constants.PAGE_SIZE]);
        });
    }

    @Test
    @DisplayName("Root page ID and catalog root ID persist across reopen")
    void testRootPersistsAcrossReopen() throws IOException {
        int pageId = diskManager.allocatePage();
        diskManager.setRootPageId(pageId);
        diskManager.setCatalogRootPageId(pageId + 5);
        diskManager.sync();
        diskManager.close();

        FileDiskManager reopened = new FileDiskManager(dbPath);
        assertEquals(pageId, reopened.getRootPageId());
        assertEquals(pageId + 5, reopened.getCatalogRootPageId());
        reopened.close();
    }

    @Test
    @DisplayName("Legacy file with 0 for root IDs opens cleanly and normalizes to INVALID_PAGE_ID")
    void testLegacyFileOpensCleanly() throws IOException {
        diskManager.close();

        // Write a mock legacy header file with rootPageId = 0
        StorageFile file = new ChannelStorageFile(dbPath);
        byte[] legacyHeader = new byte[Constants.PAGE_SIZE];
        Bytes.putLong(legacyHeader, 0, Constants.AERUS_MAGIC);
        Bytes.putInt(legacyHeader, 8, 1); // pageCount
        Bytes.putInt(legacyHeader, 12, Constants.INVALID_PAGE_ID); // freeListHead
        Bytes.putInt(legacyHeader, 16, 0); // legacy 0
        Bytes.putInt(legacyHeader, 20, 0); // legacy 0
        file.write(ByteBuffer.wrap(legacyHeader), 0);
        file.close();

        FileDiskManager legacyDm = new FileDiskManager(dbPath);
        assertEquals(Constants.INVALID_PAGE_ID, legacyDm.getRootPageId());
        assertEquals(Constants.INVALID_PAGE_ID, legacyDm.getCatalogRootPageId());
        assertEquals(Health.HEALTHY, legacyDm.getHealth());
        legacyDm.close();
    }

    @Test
    @DisplayName("Injected fsync failure transitions engine to FAILED state")
    void testInjectedFsyncFailureEntersFailedState() throws IOException {
        diskManager.close();

        // Fault injection storage file
        StorageFile faultFile = new StorageFile() {
            private final StorageFile delegate = new ChannelStorageFile(dbPath);

            @Override
            public int read(ByteBuffer dst, long position) throws IOException {
                return delegate.read(dst, position);
            }

            @Override
            public int write(ByteBuffer src, long position) throws IOException {
                return delegate.write(src, position);
            }

            @Override
            public long size() throws IOException {
                return delegate.size();
            }

            @Override
            public void truncate(long size) throws IOException {
                delegate.truncate(size);
            }

            @Override
            public void force(boolean metaData) throws IOException {
                throw new IOException("Simulated disk fsync failure!");
            }

            @Override
            public void close() throws IOException {
                delegate.close();
            }
        };

        FileDiskManager dm = new FileDiskManager(dbPath, faultFile);
        assertEquals(Health.HEALTHY, dm.getHealth());

        // sync fails
        assertThrows(IOException.class, dm::sync);
        assertEquals(Health.FAILED, dm.getHealth());

        // Further writes/reads must throw FailedStateException
        assertThrows(FailedStateException.class, dm::allocatePage);
        assertThrows(FailedStateException.class, () -> dm.writePage(1, new byte[Constants.PAGE_SIZE]));
        assertThrows(FailedStateException.class, () -> dm.readPage(1, new byte[Constants.PAGE_SIZE]));
        dm.close();
    }
}

