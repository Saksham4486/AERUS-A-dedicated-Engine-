package com.aerus.txn;

import com.aerus.common.Constants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;


class TransactionTest {

    @Test
    @DisplayName("New transaction starts ACTIVE with INVALID_LSN")
    void defaults() {
        Transaction txn = new Transaction(1);
        assertEquals(1, txn.getTxnId());
        assertEquals(Transaction.State.ACTIVE, txn.getState());
        assertEquals(Constants.INVALID_LSN, txn.getLastLsn());
        assertEquals(Constants.INVALID_LSN, txn.getSavepointLsn());
    }

    @Test
    @DisplayName("State transitions")
    void stateTransitions() {
        Transaction txn = new Transaction(2);
        txn.setState(Transaction.State.COMMITTING);
        assertEquals(Transaction.State.COMMITTING, txn.getState());
        txn.setState(Transaction.State.COMMITTED);
        assertEquals(Transaction.State.COMMITTED, txn.getState());
    }

    @Test
    @DisplayName("lastLsn tracks the most recent log record")
    void lastLsn() {
        Transaction txn = new Transaction(3);
        txn.setLastLsn(100);
        assertEquals(100, txn.getLastLsn());
        txn.setLastLsn(200);
        assertEquals(200, txn.getLastLsn());
    }

    @Test
    @DisplayName("savepointLsn round trip")
    void savepointLsn() {
        Transaction txn = new Transaction(4);
        txn.setSavepointLsn(150);
        assertEquals(150, txn.getSavepointLsn());
    }

    @Test
    @DisplayName("All states are present")
    void allStatesPresent() {
        Transaction.State[] states = Transaction.State.values();
        assertEquals(5, states.length, "ACTIVE, COMMITTING, ABORTING, COMMITTED, ABORTED");
    }

    @Test
    @DisplayName("toString contains useful info")
    void toStringWorks() {
        Transaction txn = new Transaction(42);
        txn.setLastLsn(99);
        String s = txn.toString();
        assertTrue(s.contains("42"));
        assertTrue(s.contains("99"));
        assertTrue(s.contains("ACTIVE"));
    }
}

