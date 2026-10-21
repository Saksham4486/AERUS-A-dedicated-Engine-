package com.aerus.storage;

import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import com.aerus.common.Constants;


class PageTest {

    @Test
    @DisplayName("New page is PAGE_SIZE bytes, all zeroed")
    void newPageIsZeroed() {
        Page p = new Page();
        assertEquals(Constants.PAGE_SIZE, p.getData().length);
        for (byte b : p.getData()) {
            assertEquals(0, b);
        }
    }

    @Test
    @DisplayName("New page has invalid page id")
    void defaultPageId() {
        Page p = new Page();
        assertEquals(Constants.INVALID_PAGE_ID, p.getPageId());
    }

    @Test
    @DisplayName("Constructor with page id sets it")
    void constructorWithId() {
        Page p = new Page(42);
        assertEquals(42, p.getPageId());
    }

    @Test
    @DisplayName("LSN read/write round trip through bytes 0-7")
    void lsnRoundTrip() {
        Page p = new Page();
        p.setLsn(123456789L);
        assertEquals(123456789L, p.getLsn());
    }

    @Test
    @DisplayName("LSN is stored in bytes 0-7 of the data array")
    void lsnStoredInData() {
        Page p = new Page();
        long lsn = 0xDEAD_BEEF_CAFE_BABEL;
        p.setLsn(lsn);
        assertEquals(lsn, p.getLsn());
        // Also verify via direct data access — the getLsn reads from data
        Page p2 = new Page();
        System.arraycopy(p.getData(), 0, p2.getData(), 0, Constants.PAGE_SIZE);
        assertEquals(lsn, p2.getLsn());
    }

    @Test
    @DisplayName("Pin count increments and decrements")
    void pinCount() {
        Page p = new Page();
        assertEquals(0, p.getPinCount());
        p.pin();
        assertEquals(1, p.getPinCount());
        p.pin();
        assertEquals(2, p.getPinCount());
        p.unpin();
        assertEquals(1, p.getPinCount());
        p.unpin();
        assertEquals(0, p.getPinCount());
    }

    @Test
    @DisplayName("Dirty flag starts false")
    void dirtyDefault() {
        Page p = new Page();
        assertFalse(p.isDirty());
    }

    @Test
    @DisplayName("setDirty works")
    void setDirty() {
        Page p = new Page();
        p.setDirty(true);
        assertTrue(p.isDirty());
        p.setDirty(false);
        assertFalse(p.isDirty());
    }

    @Test
    @DisplayName("recLSN starts as INVALID_LSN")
    void recLsnDefault() {
        Page p = new Page();
        assertEquals(Constants.INVALID_LSN, p.getRecLSN());
    }

    // ── noteUpdate ──────────────────────────────────────────────────────

    @Test
    @DisplayName("noteUpdate on clean page: sets dirty, stamps LSN, sets recLSN")
    void noteUpdateClean() {
        Page p = new Page();
        p.noteUpdate(100L);

        assertTrue(p.isDirty());
        assertEquals(100L, p.getLsn());
        assertEquals(100L, p.getRecLSN(), "recLSN should be set on first update");
    }

    @Test
    @DisplayName("noteUpdate on already-dirty page: updates LSN, keeps original recLSN")
    void noteUpdateDirty() {
        Page p = new Page();
        p.noteUpdate(100L);
        p.noteUpdate(200L);

        assertTrue(p.isDirty());
        assertEquals(200L, p.getLsn(), "LSN should advance to the latest update");
        assertEquals(100L, p.getRecLSN(), "recLSN should remain the first update's LSN");
    }

    @Test
    @DisplayName("noteUpdate sequence: recLSN is always the clean-to-dirty transition LSN")
    void noteUpdateSequence() {
        Page p = new Page();
        for (long lsn = 10; lsn <= 50; lsn += 10) {
            p.noteUpdate(lsn);
        }
        assertEquals(50L, p.getLsn());
        assertEquals(10L, p.getRecLSN());
    }

    // ── resetMeta ───────────────────────────────────────────────────────

    @Test
    @DisplayName("resetMeta clears all transient bookkeeping")
    void resetMeta() {
        Page p = new Page(7);
        p.pin();
        p.pin();
        p.noteUpdate(42L);

        p.resetMeta();

        assertEquals(Constants.INVALID_PAGE_ID, p.getPageId());
        assertEquals(0, p.getPinCount());
        assertFalse(p.isDirty());
        assertEquals(Constants.INVALID_LSN, p.getRecLSN());
    }

    // ── randomized round-trip ───────────────────────────────────────────

    @RepeatedTest(20)
    @DisplayName("Random LSN round trip")
    void randomLsnRoundTrip() {
        Page p = new Page();
        long lsn = ThreadLocalRandom.current().nextLong();
        p.setLsn(lsn);
        assertEquals(lsn, p.getLsn());
    }

    @Test
    @DisplayName("toString contains useful info")
    void toStringNotBlank() {
        Page p = new Page(5);
        p.noteUpdate(99L);
        String s = p.toString();
        assertTrue(s.contains("5"), "should contain page id");
        assertTrue(s.contains("99"), "should contain LSN");
    }
}

