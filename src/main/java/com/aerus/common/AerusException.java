package com.aerus.common;

/**
 * Base unchecked exception for AERUS database engine errors.
 */
public class AerusException extends RuntimeException {
    public AerusException(String message) {
        super(message);
    }

    public AerusException(String message, Throwable cause) {
        super(message, cause);
    }
}

