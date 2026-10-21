# AERUS Architecture and Design Decisions

## Decision 1 (Stage 1: Storage Hardening)
- **Header Page 0 Layout:**
  - `0..7`: Magic (`AERUS_MAGIC = 0x4145_5255_5344_4200L`)
  - `8..11`: `pageCount` (int)
  - `12..15`: `freeListHead` (int)
  - `16..19`: `rootPageId` (int) - Legacy single tree root / default table root
  - `20..23`: `catalogRootPageId` (int) - Root page of catalog B+Tree
- **Checksum Location:**
  - Placed at bytes `4092..4095` (last 4 bytes) of every 4KB page.
  - Calculated using `java.util.zip.CRC32` across bytes `0..4091`.
  - Stored as big-endian 32-bit integer.
  - On page read, if checksum does not match, throw `TornPageException`.
- **Health State:**
  - Added enum `Health { HEALTHY, FAILED }`. If any write or sync fails, transitions immediately to `FAILED` and subsequent disk operations throw `FailedStateException`.
- **StorageFile Abstraction:**
  - Introduced `StorageFile` interface decoupling `FileDiskManager` and `LogManager` from raw OS channels, enabling deterministic fault-injection (e.g., sim power loss, injected fsync failure).

