package com.aerus.wal;

/**
 * Undo operation type for UPDATE records:
 * PHYSICAL: byte-level delta before-image applied directly to page offset.
 * LOGICAL: high-level operation (e.g. B+Tree key re-insert/delete) where key may have moved due to splits.
 */
public enum UndoOp {
    PHYSICAL,
    LOGICAL
}

