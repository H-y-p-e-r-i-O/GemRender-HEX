package com.wf.gemrender.ui;

import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextBufferTest {
    private static String fixed(double value, int decimals) {
        return new TextBuffer(64).append(value, decimals).toString();
    }

    @Test
    void integersAndPadding() {
        assertEquals("0", new TextBuffer(64).append(0L).toString());
        assertEquals("-42", new TextBuffer(64).append(-42L).toString());
        assertEquals("-0042", new TextBuffer(64).append(-42L, 5, '0', false).toString());
        assertEquals("  +42", new TextBuffer(64).append(42L, 5, ' ', true).toString());
        assertEquals(Long.toString(Long.MIN_VALUE), new TextBuffer(64).append(Long.MIN_VALUE).toString());
    }

    @Test
    void decimalsMatchStringFormatAwayFromTies() {
        Random random = new Random(7);
        for (int i = 0; i < 100_000; i++) {
            double v = (random.nextDouble() - 0.5) * Math.pow(10, random.nextInt(8));
            int decimals = random.nextInt(4);
            String expected = String.format(Locale.ROOT, "%." + decimals + "f", v);
            if (expected.matches("-0\\.?0*")) {
                expected = expected.substring(1);
            }
            assertEquals(expected, fixed(v, decimals), () -> "value " + v);
        }
    }

    @Test
    void specials() {
        assertEquals("NaN", fixed(Double.NaN, 1));
        assertEquals("-Inf", fixed(Double.NEGATIVE_INFINITY, 1));
        assertEquals("0.0", fixed(-0.04, 1));
        assertEquals("-003.50", new TextBuffer(64).append(-3.5, 2, 7, '0', false).toString());
    }

    @Test
    void fixedCapacityTruncates() {
        TextBuffer b = new TextBuffer(5);
        b.append("AMMO ").append(30L).append(-1.25, 2);
        assertEquals("AMMO ", b.toString());
        b.clear().append(-12345.678, 2);
        assertEquals("-1234", b.toString());
        assertEquals(5, b.capacity());
    }

    @Test
    void clock() {
        assertEquals("01:05", new TextBuffer(64).appendClock(65.9, false).toString());
        assertEquals("02:00:05", new TextBuffer(64).appendClock(7205, false).toString());
        assertEquals("00:00:00", new TextBuffer(64).appendClock(-3, true).toString());
    }
}
