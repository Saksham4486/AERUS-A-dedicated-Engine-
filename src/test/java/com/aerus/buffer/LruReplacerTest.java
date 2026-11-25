package com.aerus.buffer;

import org.junit.jupiter.api.Test;

import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.*;

class LruReplacerTest {

    @Test
    void testLruReplacerBasicEviction() {
        LruReplacer replacer = new LruReplacer(3);
        
        replacer.unpin(1);
        replacer.unpin(2);
        replacer.unpin(3);

        assertEquals(3, replacer.size());

        OptionalInt victim = replacer.victim();
        assertTrue(victim.isPresent());
        assertEquals(1, victim.getAsInt(), "Frame 1 should be least recently used");

        replacer.unpin(4);
        
       
        replacer.pin(2);
        replacer.unpin(2);

        victim = replacer.victim();
        assertTrue(victim.isPresent());
        assertEquals(3, victim.getAsInt(), "Frame 3 should be least recently used now");
    }
}

