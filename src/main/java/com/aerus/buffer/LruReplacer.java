package com.aerus.buffer;

import java.util.LinkedHashSet;
import java.util.OptionalInt;

public class LruReplacer implements Replacer {

    // LinkedHashSet maintains insertion order (LRU at the front).
    private final LinkedHashSet<Integer> frames;
    private final int capacity;

    public LruReplacer(int capacity) {
        this.capacity = capacity;
        this.frames = new LinkedHashSet<>(capacity);
    }

    @Override
    public synchronized OptionalInt victim() {
        if (frames.isEmpty()) {
            return OptionalInt.empty(); // No evictable frames
        }
        // First element is the least recently used
        int victimFrame = frames.iterator().next();
        frames.remove(victimFrame);
        return OptionalInt.of(victimFrame);
    }

    @Override
    public synchronized void pin(int frameId) {
        // Pinned frames are actively used, removing them from eviction candidates
        frames.remove(frameId);
    }

    @Override
    public synchronized void unpin(int frameId) {
        // Only track up to capacity (though normally pool controls bounds)
        if (frames.size() < capacity || frames.contains(frameId)) {
            // Unpinning adds the frame as an eviction candidate.
            // Removing and re-adding updates it to 'most recently used' (end of list)
            frames.remove(frameId);
            frames.add(frameId);
        }
    }

    @Override
    public synchronized int size() {
        return frames.size();
    }
}

