package com.aerus.buffer;

import java.util.OptionalInt;

public interface Replacer {

    /**
     * @return
     */
    OptionalInt victim();

    /**
     * @param frameId
     */
    void pin(int frameId);

    /**
     * @param frameId
     */
    void unpin(int frameId);

    /**
     * @return
     */
    int size();
}

