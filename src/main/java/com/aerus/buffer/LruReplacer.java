package com.aerus.buffer;

import java.util.LinkedHashSet;
import java.util.OptionalInt;

public class LruReplacer implements Replacer {

    private final LinkedHashSet<Integer> frames;
    private final int capacity;

    public LruReplacer(int capacity) {
        this.capacity = capacity;
        this.frames = new LinkedHashSet<>(capacity);
    }

    @Override
    public synchronized OptionalInt victim() {
        if (frames.isEmpty()) {
            return OptionalInt.empty();
        }
        int victimFrame = frames.iterator().next();
        frames.remove(victimFrame);
        return OptionalInt.of(victimFrame);
    }

    @Override
    public synchronized void pin(int frameId) {
        frames.remove(frameId);
    }

    @Override
    public synchronized void unpin(int frameId) {
        if (frames.size() < capacity || frames.contains(frameId)) {
            frames.remove(frameId);
            frames.add(frameId);
        }
    }

    @Override
    public synchronized int size() {
        return frames.size();
    }
}