# AERUS Architecture

## Layer Diagram

```
AerusDB API (Missing)
   |
Execution engine (Missing) -> Index (B+Tree) (Missing) + Table heap (Missing)
   |                                 |
   |              Transaction / Lock manager (Skeleton)      WAL: LogManager + RecoveryManager (ARIES) (Skeleton)
   |
BufferPool (Skeleton)
   |
DiskManager (FileDiskManager)
```

## Class Descriptions

### Implemented Layers
* **`FileDiskManager`**: Implements the `DiskManager` interface. Backed by a single file using `FileChannel`. Provides 4 KB fixed-page positional read/write and `force()`. Manages a free list using page 0 as the header.
* **`Page`**: An in-memory representation of a 4 KB page frame. Implements byte 0-7 `pageLSN` tracking and metadata bookkeeping (dirty, pin count, `recLSN`).
* **`Constants` / `Bytes`**: Utilities for engine-wide magic numbers and zero-copy little-endian byte array manipulations.

### Skeleton / Interface Layers
* **Buffer**: `BufferPool`, `Replacer`, `DirtyEntry` interfaces exist, but no implementations (e.g., `BufferPoolManager`, `LruReplacer`) are present in the codebase.
* **WAL**: `LogRecord` skeleton exists. `LogManager`, `PageLogger`, `RecoveryManager` are missing.
* **Txn**: `Transaction` skeleton and `UndoHandler` interface exist. `TxnManager` and `LockManager` are missing.
* **Index**: Package marker only. `BTree` is missing.
* **API / CLI / Bench**: Package markers only. `AerusDB`, `Cli`, `Metrics`, `Stats` are missing.

## Write-Ahead Logging & Checkpoint Ordering Rules
*Currently unimplemented. The architecture intends to follow ARIES strict write-ahead rules where the WAL is flushed up to the `pageLSN` before a dirty page is evicted, and checkpoints snapshot the active transaction and dirty page tables.*

