package com.aerus.common;

public final class Constants {

    private Constants() { }

    public static final int PAGE_SIZE = 4096;

    public static final int PAGE_LSN_OFFSET = 0;

    public static final int PAGE_LSN_SIZE = Long.BYTES; // 8

    public static final int PAGE_CHECKSUM_SIZE = Integer.BYTES; // 4

    public static final int PAGE_CHECKSUM_OFFSET = PAGE_SIZE - PAGE_CHECKSUM_SIZE; // 4092

    public static final int USABLE_PAGE_SIZE = PAGE_SIZE - PAGE_CHECKSUM_SIZE; // 4092

    public static final long INVALID_LSN = -1L;

    public static final int INVALID_PAGE_ID = -1;

    public static final long AERUS_MAGIC = 0x4145_5255_5344_4200L; // "AERUSDB\0" as bytes

    public static final long WAL_MAGIC = 0x4145_5255_5357_414CL; // "AERUSWAL"
}
