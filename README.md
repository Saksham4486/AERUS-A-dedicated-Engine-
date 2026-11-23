# AERUS — A Dedicated Storage Engine

AERUS is a database storage engine written from scratch in Java 17+. It combines three key computer science disciplines: **Operating Systems** (I/O management, CPU scheduling, resource allocation, and deadlock avoidance), **Database Management Systems** (4 KB slotted pages, buffer pool memory management, write-ahead logging with ARIES-style recovery, B+Tree indexing, and ACID transactions), and **System Design** (clean abstraction layers, durability hardening, and real-time observability). **No external database or ORM libraries are used.**

> **Primary Reference:** C. Mohan, D. Haderle, B. Lindsay, H. Pirahesh, P. Schwarz, *ARIES: A Transaction Recovery Method Supporting Fine-Granularity Locking and Partial Rollbacks Using Write-Ahead Logging*, ACM Transactions on Database Systems (TODS), 1992.

---

## Problem Statement / Objective

Most modern database systems hide their lower-level OS and DBMS interactions. The objective of AERUS is to engineer a robust, educational, and high-performance storage engine that:

1. **Guarantees Atomicity & Durability:** Implements write-ahead logging (WAL) where page changes are logged before disk flushes, torn tails are truncated, and torn pages are detected via CRC32 page checksums.
2. **High-Performance Memory & Indexing:** Provides a disk-backed B+Tree index (mapping 64-bit keys to 64-bit values) coupled with a Buffer Pool Manager that implements frame pinning and LRU page replacement.
3. **Genuine OS-Level Resource Management & Concurrency:**
   - **Strict Two-Phase Locking (2PL)** with Shared (S) and Exclusive (X) lock modes.
   - **Automatic Deadlock Detection**: Uses a dynamic Wait-For graph to detect wait cycles and automatically abort the youngest transaction.
   - **Live Banker's Algorithm (Admission Control)**: Not a simulation—dynamically tracks real buffer frames, lock slots, and concurrent sessions, verifying system safety ($O(P^2 \cdot R)$) before granting requests.
   - **Pre-emptive Round Robin CPU Scheduling**: Steppable workloads executed across configurable time quanta (default 20 ms), preventing starvation.
4. **Multi-Database Management:** Supports creating, isolating, switching (`CREATE DATABASE`, `USE`, `SHOW DATABASES`), and bulk importing external SQL/CSV datasets.

---

## Team Members

| Name | Role |
|---|---|
| Saksham | Team Lead |
| Atharva | Team Member |
| Abhishek | Team Member |
| Gaurav | Team Member |

---

## Technologies / Tools Used

- **Language:** Java 17+ (JDK 17 LTS)
- **Build & Dependency Management:** Apache Maven (Wrapper included: `mvnw` / `mvnw.cmd`)
- **Testing Framework:** JUnit 5 (`junit-jupiter`)
- **Storage & I/O:** `java.nio` `FileChannel`, positional I/O, `force()` (fsync), CRC32 page checksums
- **Concurrency & Multithreading:** `ReentrantLock`, `Condition`, `AtomicInteger`, `LongAdder`
- **User Interface:** Java Swing Minimalist GUI (`AerusDashboard`) with custom 2D real-time latency & cache graphs
- **Version Control:** Git / GitHub

---

## Project Setup & Quick Launch

### 1. Prerequisites
- **JDK 17 or newer** (`java -version`)
- **Git**

```bash
git clone https://github.com/Saksham4486/AERUS-A-dedicated-Engine-.git
cd AERUS-A-dedicated-Engine-
```

### 2. One-Click GUI Launch (Windows)
- **Without terminal window:** Double-click `launch-gui.vbs`
- **With terminal console logging:** Double-click `run-gui.bat`

### 3. Command-Line Launch
```powershell
# Compile & Run via Java
.\mvnw.cmd compile
java -cp target/classes com.aerus.cli.AerusDashboard
```

### 4. Running the Test Suite
The project includes a comprehensive JUnit 5 test suite verifying every component:
```powershell
.\mvnw.cmd test
```
*Current test suite status: **235 passed, 0 failures, 0 errors**.*

---

## Major Features & Architecture

```
                         ┌──────────── Clients ────────────┐
   Swing Dashboard   Interactive SQL Console   Java API (AerusDB)
                         └───────────────┬──────────────────┘
                                         ▼
            Pre-emptive Request Scheduler (Round Robin / FCFS / SJF / Priority)
                                         ▼
            Live Admission Control & Deadlock Avoidance (Banker's Algorithm)
                                         ▼
          SQL Command Parser / Execution Engine (CRUD, Transactions, DB Mgmt)
                                         ▼
         Multi-Database Manager │ Strict 2PL Lock Manager (Wait-For Graph Cycles)
                                         ▼
             B+Tree Disk-Backed Index (64-bit Keys -> 64-bit Values)
                                         ▼
         Buffer Pool Manager (LRU Replacer, Frame Pinning, Hit/Miss Telemetry)
                            │                       │
                            ▼                       ▼
                   Log Manager (WAL file)     Disk Manager (4 KB Pages + CRC32)
```

| Module | Package | Description |
|---|---|---|
| **Storage Hardening** | `com.aerus.storage` | 4 KB pages, `FileDiskManager`, `StorageFile` abstraction, CRC32 checksums (detects torn writes), engine `Health` state tracking (`HEALTHY`, `FAILED`). |
| **Write-Ahead Log** | `com.aerus.wal` | Append-only `LogManager`, binary `LogRecord` serialization, CRC-checked frames, session generation tracking, and torn-tail recovery/truncation. |
| **Buffer Pool** | `com.aerus.buffer` | Frame-based page cache (`BufferPoolManager`), `Replacer` interface, `LruReplacer`, dirty page table (`recLSN`), cache hit/miss tracking. |
| **Index** | `com.aerus.index` | Disk-backed `BTree`, node splits (leaf & internal nodes), binary search in-page, sibling leaf chaining for range scans. |
| **Execution Engine** | `com.aerus.engine` | Interactive query processor for CRUD (`INSERT`, `SELECT`, `DELETE`, `SCAN`), multi-database management, and transactional undo/rollback. |
| **Concurrency Control** | `com.aerus.txn` | Strict Two-Phase Locking (`LockManager`) with Shared/Exclusive modes, upgrade support, and DFS wait-for cycle deadlock detection. |
| **Resource Allocation** | `com.aerus.engine.scheduling` | Live `ResourceManager` running the **Banker's Algorithm** for real admission control across Frames, Lock Slots, and Sessions. |
| **CPU Scheduling** | `com.aerus.engine.scheduling` | Multi-policy `TaskScheduler` featuring pre-emptive **Round Robin** with steppable time-slices, FCFS, SJF, and Priority queueing. |
| **Interactive GUI** | `com.aerus.cli` | Minimalist Swing `AerusDashboard` with interactive query console, live cache hit rate graph, CPU latency graph, and Banker's algorithm inspection. |

---

## Interactive Query & Command Reference

The database engine supports interactive queries through the dashboard console or directly via `ExecutionEngine`:

```sql
-- Database Management
CREATE DATABASE <name>                 -- Creates an isolated database directory
USE <name>                             -- Switches the active database context
SHOW DATABASES                         -- Lists all databases on disk
IMPORT DATABASE <path> [INTO <name>]  -- Imports external SQL or CSV data files

-- Data Manipulation (CRUD)
INSERT <key> <value>                   -- Inserts or updates a key-value pair
INSERT INTO <table> VALUES (k, v)     -- SQL-style insertion
SELECT WHERE key = <k>                 -- Retrieves row by key
GET <key>                              -- Shortcut for point lookup
DELETE <key>                           -- Removes a key
SCAN <startKey> <endKey>               -- Range scan [startKey, endKey] inclusive

-- Transaction Management & Concurrency
BEGIN [CLAIM frames=<n> locks=<m>]     -- Starts a transaction with Banker's resource claims
COMMIT                                 -- Flushes changes and releases locks/resources
ROLLBACK                               -- Reverts uncommitted transactional changes
SHOW RESOURCES                         -- Inspects live Banker's Algorithm allocation & safety matrices

-- System Help
HELP                                   -- Lists all supported commands
```

---

## Current Project Status

| Module | Status | Verification |
|---|---|---|
| **Disk Manager & Checksums** | **Complete** | CRC32 verified on read; `TornPageException` on byte flip; header persistence tested across reopens. |
| **Buffer Pool & LRU Replacement** | **Complete** | Frame pinning, dirty tracking, eviction, and cache hit/miss metrics tested. |
| **Write-Ahead Log (WAL) & Framing** | **Complete** | Binary serialization, torn-tail truncation, session generations, and LSN ordering verified. |
| **B+Tree Indexing** | **Complete** | Search, multi-level splits, leaf chaining, and randomized `TreeMap` model equivalence tests pass. |
| **Strict 2PL Concurrency Control** | **Complete** | Shared/Exclusive locks, lock queues, and transactional isolation verified. |
| **Deadlock Detection** | **Complete** | Wait-For graph DFS cycle detection aborts the youngest transaction with `DeadlockException`. |
| **Banker's Algorithm (Live)** | **Complete** | Real admission control on Frames/Locks/Sessions; unsafe requests wait; zero hard-coded matrices. |
| **Round Robin CPU Scheduling** | **Complete** | Pre-emptive time slicing (20 ms quantum) with steppable workloads verified. |
| **Multi-Database & Import** | **Complete** | `CREATE DATABASE`, `USE`, `SHOW DATABASES`, and SQL/CSV file import tested. |
| **Swing Dashboard GUI** | **Complete** | Live query console, real-time cache hit graph, and Banker's report viewer functional. |

---

## Repository Layout

```
.
├── src/main/java/com/aerus/
│   ├── buffer/              # BufferPool, BufferPoolManager, Replacer, LruReplacer
│   ├── cli/                 # AerusDashboard (Swing GUI), GraphPanel
│   ├── common/              # Bytes, Constants, AerusException, DeadlockException
│   ├── engine/              # ExecutionEngine, DatabaseManager
│   │   └── scheduling/      # TaskScheduler, Task, SteppableWorkload, ResourceManager
│   ├── index/               # BTree (Disk-backed B+Tree)
│   ├── storage/             # FileDiskManager, StorageFile, Page, TornPageException
│   ├── txn/                 # Transaction, LockManager, UndoHandler
│   └── wal/                 # LogManager, LogRecord, LogMetrics, LogType, UndoOp
├── src/test/java/com/aerus/ # 235 unit & integration tests
├── docs/                    # Architecture diagrams & decision logs
├── run-gui.bat              # One-click Windows console launcher
├── launch-gui.vbs           # One-click Windows background launcher
└── pom.xml                  # Maven project configuration
```

---

## License

Academic project developed for educational and systems research purposes.
