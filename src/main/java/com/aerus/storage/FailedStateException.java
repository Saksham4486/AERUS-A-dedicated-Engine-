package com.aerus.storage;

import com.aerus.common.AerusException;

/**
 * Thrown when an operation is attempted on an engine or storage manager in FAILED health state.
 */
public class FailedStateException extends AerusException {
    public FailedStateException(String message) {
        super(message);
    }

    public FailedStateException(String message, Throwable cause) {
        super(message, cause);
    }
}

