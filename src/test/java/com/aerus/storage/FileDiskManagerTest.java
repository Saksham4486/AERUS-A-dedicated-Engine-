package com.aerus.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.aerus.common.Bytes;
import com.aerus.common.Constants;


class FileDiskManagerTest {

    @TempDir
    Path tempDir;

    private Path dbPath;
    private FileDiskManager diskManager;

    @BeforeEach
    void setUp() throws IOException {
        dbPath = tempDir.resolve("test.aerus");
        diskManager = new FileDiskManager(dbPath);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (diskManager != null) {
            diskManager.close();
        }
    }

    @Test
    @DisplayName("Write-read round trip on a new page")
    void writeReadRoundTrip() throws IOException {
        int pageId = diskManager.allocatePage();
        assertEquals(1, pageId, "First allocated page should be 1 (0 is header)");

        byte[] writeData = new byte[Constants.PAGE_SIZE];
        ThreadLocalRandom.current().nextBytes(writeData);

        diskManager.writePage(pageId, writeData);

        byte[] readData = new byte[Constants.PAGE_SIZE];
        diskManager.readPage(pageId, readData);

        assertArrayEquals(writeData, readData, "Read data should match written data exactly");
    }

    @Test
    @DisplayName("Persistence after reopen")
    void persistenceAfterReopen() throws IOException {
        int p1 = diskManager.allocatePage();
        int p2 = diskManager.allocatePage();

        byte[] d1 = new byte[Constants.PAGE_SIZE];
        byte[] d2 = new byte[Constants.PAGE_SIZE];
        Arrays.fill(d1, (byte) 1);
        Arrays.fill(d2, (byte) 2);

        diskManager.writePage(p1, d1);
        diskManager.writePage(p2, d2);
        diskManager.sync();
        diskManager.close();

        // Reopen
        diskManager = new FileDiskManager(dbPath);

        assertEquals(3, diskManager.getPageCount(), "Page count should be preserved (0, 1, 2 = 3 pages)");

        byte[] r1 = new byte[Constants.PAGE_SIZE];
        byte[] r2 = new byte[Constants.PAGE_SIZE];

        diskManager.readPage(p1, r1);
        diskManager.readPage(p2, r2);

        assertArrayEquals(d1, r1, "Page 1 data preserved");
        assertArrayEquals(d2, r2, "Page 2 data preserved");
    }

    @Test
    @DisplayName("Reading a never-written page returns zeroed page")
    void readingNeverWrittenPage() throws IOException {
        int pageId = diskManager.allocatePage();
        // We do not write to it.
        
        byte[] readData = new byte[Constants.PAGE_SIZE];
        // Fill buffer with garbage to prove DiskManager zeros it
        Arrays.fill(readData, (byte) 99);
        
        diskManager.readPage(pageId, readData);

        for (byte b : readData) {
            assertEquals(0, b, "Never-written page should be fully zeroed");
        }
    }

    @Test
    @DisplayName("ID reuse after deallocate")
    void idReuseAfterDeallocate() throws IOException {
        int p1 = diskManager.allocatePage(); // 1
        int p2 = diskManager.allocatePage(); // 2
        int p3 = diskManager.allocatePage(); // 3

        assertEquals(4, diskManager.getPageCount());

        diskManager.deallocatePage(p2);
        assertEquals(p2, diskManager.getFreeListHead(), "p2 should be head of free list");
        
        diskManager.deallocatePage(p1);
        assertEquals(p1, diskManager.getFreeListHead(), "p1 should now be head of free list");

        // The free list should be p1 -> p2 -> -1
        
        int r1 = diskManager.allocatePage();
        assertEquals(p1, r1, "Should reuse p1 first (LIFO)");
        assertEquals(p2, diskManager.getFreeListHead(), "Free list head should point to p2");

        int r2 = diskManager.allocatePage();
        assertEquals(p2, r2, "Should reuse p2 next");
        assertEquals(Constants.INVALID_PAGE_ID, diskManager.getFreeListHead(), "Free list should be empty");

        int p4 = diskManager.allocatePage();
        assertEquals(4, p4, "Should grow the file since free list is empty");
        assertEquals(5, diskManager.getPageCount());
    }

    @Test
    @DisplayName("Corrupt-magic rejection")
    void corruptMagicRejection() throws IOException {
        diskManager.close();

        // Corrupt the magic number manually
        try (FileChannel fc = FileChannel.open(dbPath, StandardOpenOption.WRITE)) {
            byte[] badMagic = new byte[8];
            Bytes.putLong(badMagic, 0, 0xBAD_BAD_BAD_BADL);
            fc.position(0);
            fc.write(ByteBuffer.wrap(badMagic));
        }

        IOException thrown = assertThrows(IOException.class, () -> {
            new FileDiskManager(dbPath);
        });

        assertTrue(thrown.getMessage().contains("bad magic number"), 
                "Should throw exception with specific message");
        
        // Prevent tearDown from closing already null/closed instance
        diskManager = null; 
    }
}

