package com.aerus.common;

import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;


class BytesTest {

    @Test
    @DisplayName("putShort / getShort round trip — zero")
    void shortZero() {
        byte[] buf = new byte[16];
        Bytes.putShort(buf, 4, (short) 0);
        assertEquals(0, Bytes.getShort(buf, 4));
    }

    @Test
    @DisplayName("putShort / getShort round trip — boundaries")
    void shortBoundaries() {
        byte[] buf = new byte[16];
        for (short v : new short[]{Short.MIN_VALUE, -1, 1, Short.MAX_VALUE}) {
            Bytes.putShort(buf, 2, v);
            assertEquals(v, Bytes.getShort(buf, 2), "value=" + v);
        }
    }

    @RepeatedTest(50)
    @DisplayName("putShort / getShort round trip — random")
    void shortRandom() {
        byte[] buf = new byte[16];
        short v = (short) ThreadLocalRandom.current().nextInt(Short.MIN_VALUE, Short.MAX_VALUE + 1);
        int off = ThreadLocalRandom.current().nextInt(0, buf.length - 2 + 1);
        Bytes.putShort(buf, off, v);
        assertEquals(v, Bytes.getShort(buf, off));
    }


    @Test
    @DisplayName("putInt / getInt round trip — zero")
    void intZero() {
        byte[] buf = new byte[16];
        Bytes.putInt(buf, 0, 0);
        assertEquals(0, Bytes.getInt(buf, 0));
    }

    @Test
    @DisplayName("putInt / getInt round trip — boundaries")
    void intBoundaries() {
        byte[] buf = new byte[16];
        for (int v : new int[]{Integer.MIN_VALUE, -1, 1, Integer.MAX_VALUE, 0xDEAD_BEEF}) {
            Bytes.putInt(buf, 4, v);
            assertEquals(v, Bytes.getInt(buf, 4), "value=" + v);
        }
    }

    @RepeatedTest(50)
    @DisplayName("putInt / getInt round trip — random")
    void intRandom() {
        byte[] buf = new byte[16];
        int v = ThreadLocalRandom.current().nextInt();
        int off = ThreadLocalRandom.current().nextInt(0, buf.length - 4 + 1);
        Bytes.putInt(buf, off, v);
        assertEquals(v, Bytes.getInt(buf, off));
    }

   
    @Test
    @DisplayName("putLong / getLong round trip — zero")
    void longZero() {
        byte[] buf = new byte[16];
        Bytes.putLong(buf, 0, 0L);
        assertEquals(0L, Bytes.getLong(buf, 0));
    }

    @Test
    @DisplayName("putLong / getLong round trip — boundaries")
    void longBoundaries() {
        byte[] buf = new byte[16];
        for (long v : new long[]{Long.MIN_VALUE, -1L, 1L, Long.MAX_VALUE, 0xCAFE_BABE_DEAD_BEEFL}) {
            Bytes.putLong(buf, 0, v);
            assertEquals(v, Bytes.getLong(buf, 0), "value=" + v);
        }
    }

    @RepeatedTest(50)
    @DisplayName("putLong / getLong round trip — random")
    void longRandom() {
        byte[] buf = new byte[16];
        long v = ThreadLocalRandom.current().nextLong();
        int off = ThreadLocalRandom.current().nextInt(0, buf.length - 8 + 1);
        Bytes.putLong(buf, off, v);
        assertEquals(v, Bytes.getLong(buf, off));
    }

    @Test
    @DisplayName("Byte layout is little-endian: int 0x04030201 → bytes 01 02 03 04")
    void intIsLittleEndian() {
        byte[] buf = new byte[4];
        Bytes.putInt(buf, 0, 0x04030201);
        assertEquals(0x01, buf[0] & 0xFF);
        assertEquals(0x02, buf[1] & 0xFF);
        assertEquals(0x03, buf[2] & 0xFF);
        assertEquals(0x04, buf[3] & 0xFF);
    }

    @Test
    @DisplayName("Byte layout is little-endian: long 0x0807060504030201 → bytes 01..08")
    void longIsLittleEndian() {
        byte[] buf = new byte[8];
        Bytes.putLong(buf, 0, 0x0807060504030201L);
        for (int i = 0; i < 8; i++) {
            assertEquals(i + 1, buf[i] & 0xFF, "byte[" + i + "]");
        }
    }

    @Test
    @DisplayName("Byte layout is little-endian: short 0x0201 → bytes 01 02")
    void shortIsLittleEndian() {
        byte[] buf = new byte[2];
        Bytes.putShort(buf, 0, (short) 0x0201);
        assertEquals(0x01, buf[0] & 0xFF);
        assertEquals(0x02, buf[1] & 0xFF);
    }


    @Test
    @DisplayName("Two ints at adjacent offsets don't interfere")
    void adjacentInts() {
        byte[] buf = new byte[8];
        Bytes.putInt(buf, 0, 42);
        Bytes.putInt(buf, 4, 99);
        assertEquals(42, Bytes.getInt(buf, 0));
        assertEquals(99, Bytes.getInt(buf, 4));
    }
}

