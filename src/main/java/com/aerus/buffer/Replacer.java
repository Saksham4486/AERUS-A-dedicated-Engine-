package com.aerus.buffer;

import java.util.OptionalInt;

public interface Replacer {

    /**
      @return the frame number to evict, or empty if every frame is pinned
     */
    OptionalInt victim();

    /**
     
     * @param frameId the frame number
     */
    void pin(int frameId);

    /**
      @param frameId the frame number
     */
    void unpin(int frameId);

    /**
     * @return the number of frames currently tracked as evictable
     */
    int size();
}

