package com.aerus.common;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

public final class Bytes {

    private Bytes() { }

    private static final VarHandle SHORT_LE =
            MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle INT_LE =
            MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle LONG_LE =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    public static short getShort(byte[] buf, int offset) {
        return (short) SHORT_LE.get(buf, offset);
    }

    public static int getInt(byte[] buf, int offset) {
        return (int) INT_LE.get(buf, offset);
    }

    public static long getLong(byte[] buf, int offset) {
        return (long) LONG_LE.get(buf, offset);
    }

    public static void putShort(byte[] buf, int offset, short value) {
        SHORT_LE.set(buf, offset, value);
    }

    public static void putInt(byte[] buf, int offset, int value) {
        INT_LE.set(buf, offset, value);
    }

    public static void putLong(byte[] buf, int offset, long value) {
        LONG_LE.set(buf, offset, value);
    }
}