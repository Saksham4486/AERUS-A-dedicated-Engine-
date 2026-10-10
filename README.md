# AERUS — A Dedicated Storage Engine

AERUS is a database storage engine written from scratch in Java. It combines three areas: **Operating Systems** (I/O, CPU scheduling, resource allocation, communication), **Database Management Systems** (buffer pool, write-ahead logging, indexing, transactions) and **System Design** (durability trade-offs, concurrency, observability). No external database library is used.

> Reference: C. Mohan, D. Haderle, B. Lindsay, H. Pirahesh, P. Schwarz, *ARIES: A Transaction Recovery Method Supporting Fine-Granularity Locking and Partial Rollbacks Using Write-Ahead Logging*, ACM TODS, 1992.

## Problem Statement / Objective

Most students use databases without seeing how they stay correct after a crash or how they use memory, disk and CPU. The objective is to build a small but real storage engine that:

1. Keeps committed data safe across crashes (durability) and undoes unfinished work (atomicity), using ARIES-style write-ahead logging and recovery.
2. Serves reads and writes quickly through a buffer pool with a replacement policy, and a B+Tree index.
3. Demonstrates the operating-system concepts a database relies on: I/O handling, CPU scheduling, resource allocation (locks, deadlock avoidance) and client/server communication.
4. Supports research on (a) write-ahead-log-aware buffer replacement with bounded-loss asynchronous durability and (b) learned eviction compared with LRU-K and Belady's optimal policy.

## Team Members

| Name | Role |
|---|---|
| Saksham | Team lead |
| Atharva | Team member |
| Abhishek | Team member |
| Gaurav | Team member |

## Technologies / Tools Used

- **Language:** Java 17+
- **Build:** Maven
- **Testing:** JUnit 5
- **Storage I/O:** `java.nio` `FileChannel`, fsync for durability, CRC32 checksums
- **Concurrency:** `synchronized`, `ReentrantLock`, `Semaphore`, `LongAdder`
- **GUI:** Java Swing (`AerusDashboard`)
- **Tooling:** Git/GitHub, VS Code, Antigravity (code generation)
- **Analysis (planned):** Python (numpy, matplotlib, scikit-learn) for cache simulation and benchmark plots

## Project Setup / Installation

**Prerequisites:** JDK 17 or newer (`java -version`), Git, and optionally Maven 3.8+.

```bash
git clone https://github.com/Saksham4486/AERUS-A-dedicated-Engine-.git
cd AERUS-A-dedicated-Engine-
```

**Build and run the dashboard without Maven (Windows PowerShell):**
```powershell
mkdir out
javac -d out (Get-ChildItem -Recurse src\main\java -Filter *.java).FullName
java -cp out com.aerus.cli.AerusDashboard
```

**With Maven:**
```bash
mvn test
mvn compile exec:java -Dexec.mainClass="com.aerus.cli.AerusDashboard"
```

The dashboard creates `aerus_gui.db` in the folder you run it from. It can be deleted at any time.

## Major Features / Modules

| Module | Package | Description |
|---|---|---|
| Storage | `storage` | Fixed-size pages, file-backed disk manager, page checksums |
| Buffer pool | `buffer` | Page cache with pin/unpin, dirty tracking, pluggable replacement (LRU, LRU-K), hit/miss counters |
| Write-ahead log | `wal` | ARIES log records, log manager with torn-tail detection, fuzzy checkpoints, analysis/redo/undo recovery |
| Transactions | `txn` | begin/commit/rollback, savepoints and partial rollback, compensation log records |
| Index | `index` | Disk-based B+Tree with crash-safe splits (nested top actions) |
| Durability modes | `wal` | Sync, bounded-async, unbounded-async and no-sync commit, group commit |
| CPU scheduling | `engine.scheduling` | FCFS, SJF and priority policies (round-robin with time slices, MLFQ planned) |
| Resource allocation | `engine.scheduling` | Banker's algorithm; lock manager with deadlock detection planned |
| Communication | `net` (planned) | TCP client/server for remote commands |
| Dashboard | `cli` | Swing GUI to run tasks, fetch pages and test Banker's requests |
| Metrics | `metrics` | Buffer hits/misses, log records per type, fsync counts |

## Current Project Status

Update this table whenever a module changes. Status is relative to what is merged into this repository.

| Module | Status |
|---|---|
| Disk manager, pages | Done |
| Buffer pool + LRU replacer | Done |
| LRU-K replacer | In progress |
| Log records, transaction state | Done |
| Log manager, recovery (ARIES) | In progress |
| B+Tree index | In progress |
| Public API (get/put/delete/begin/commit/rollback) | Planned |
| CPU scheduler: FCFS, SJF, priority | Done |
| CPU scheduler: round-robin (time-sliced), MLFQ | Planned |
| Banker's algorithm | Done |
| Lock manager + deadlock detection | Planned |
| TCP server | Planned |
| Swing dashboard | Done |
| WAL-aware eviction, learned eviction, benchmarks | Planned |

**Known limitations:** `ROUND_ROBIN` currently runs each task to completion (not time-sliced). There is no command-line interface yet.

## Repository Layout

```
src/main/java/com/aerus/
  api/  buffer/  cli/  common/  engine/scheduling/  index/  storage/  txn/  wal/
src/test/java/com/aerus/
docs/architecture.md
```

## License

Academic project.