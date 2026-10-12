package com.wf.gemrender.ui;

/**
 * Fixed-capacity character buffer for per-frame HUD text: sized once, never grows, never allocates
 * while appending; text past {@link #capacity()} is dropped. {@link #toString()} and
 * {@link #subSequence} allocate.
 */
public final class TextBuffer implements CharSequence {
    private static final long[] POW10 = {1L, 10L, 100L, 1_000L, 10_000L, 100_000L, 1_000_000L, 10_000_000L,
            100_000_000L, 1_000_000_000L};

    private char[] chars;
    private int length;

    private final char[] scratch = new char[24];

    public TextBuffer(int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("capacity " + capacity);
        }
        chars = new char[capacity];
    }

    public int capacity() {
        return chars.length;
    }

    /** Chars a {@link #append(double, int, int, char, boolean)} can emit at most: sign, 18 digits, point, decimals, padding. */
    public static int numberCapacity(int decimals, int minWidth) {
        return Math.max(minWidth, 20 + (decimals > 0 ? decimals + 1 : 0));
    }

    public TextBuffer clear() {
        length = 0;
        return this;
    }

    public void setLength(int length) {
        if (length < 0 || length > this.length) {
            throw new IndexOutOfBoundsException(length);
        }
        this.length = length;
    }

    public char[] array() {
        return chars;
    }

    @Override
    public int length() {
        return length;
    }

    @Override
    public char charAt(int index) {
        if (index >= length) {
            throw new IndexOutOfBoundsException(index);
        }
        return chars[index];
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        return new String(chars, start, end - start);
    }

    @Override
    public String toString() {
        return new String(chars, 0, length);
    }

    public boolean contentEquals(CharSequence other) {
        if (other.length() != length) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            if (chars[i] != other.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private void put(char c) {
        if (length < chars.length) {
            chars[length++] = c;
        }
    }

    public TextBuffer append(char c) {
        put(c);
        return this;
    }

    public TextBuffer append(CharSequence text) {
        return append(text, 0, text.length());
    }

    public TextBuffer append(CharSequence text, int start, int end) {
        int n = Math.min(end - start, chars.length - length);
        if (n <= 0) {
            return this;
        }
        if (text instanceof String s) {
            s.getChars(start, start + n, chars, length);
        } else if (text instanceof TextBuffer b) {
            System.arraycopy(b.chars, start, chars, length, n);
        } else {
            for (int i = 0; i < n; i++) {
                chars[length + i] = text.charAt(start + i);
            }
        }
        length += n;
        return this;
    }

    public TextBuffer append(long value) {
        return append(value, 0, ' ', false);
    }

    /**
     * @param minWidth total width including sign; shorter results are left-padded with {@code pad}
     *                 ({@code '0'} pads after the sign)
     */
    public TextBuffer append(long value, int minWidth, char pad, boolean plus) {
        boolean negative = value < 0;
        int digits = digits(value);
        int sign = negative || plus ? 1 : 0;
        int padding = Math.max(0, minWidth - digits - sign);
        if (pad != '0') {
            fill(pad, padding);
        }
        if (negative) {
            put('-');
        } else if (plus) {
            put('+');
        }
        if (pad == '0') {
            fill('0', padding);
        }
        writeDigits(value, digits);
        return this;
    }

    private void writeDigits(long value, int digits) {
        int at = digits;
        if (value == Long.MIN_VALUE) {
            scratch[--at] = '8';
            value /= 10;
        }
        long v = Math.abs(value);
        do {
            scratch[--at] = (char) ('0' + v % 10);
            v /= 10;
        } while (v != 0);
        for (int i = 0; i < digits; i++) {
            put(scratch[i]);
        }
    }

    /** Upper-case hexadecimal of the low 64 bits, zero-padded to {@code minWidth}. */
    public TextBuffer appendHex(long value, int minWidth) {
        int digits = 1;
        for (long v = value >>> 4; v != 0; v >>>= 4) {
            digits++;
        }
        fill('0', Math.max(0, minWidth - digits));
        for (int shift = (digits - 1) * 4; shift >= 0; shift -= 4) {
            put(Character.toUpperCase(Character.forDigit((int) (value >>> shift & 0xF), 16)));
        }
        return this;
    }

    public TextBuffer append(double value, int decimals) {
        return append(value, decimals, 0, ' ', false);
    }

    /**
     * Fixed-point decimal, half-up. {@code decimals} in [0, 9]. Rounds to zero => no minus sign. NaN
     * and infinities print as {@code NaN}, {@code Inf}, {@code -Inf}.
     */
    public TextBuffer append(double value, int decimals, int minWidth, char pad, boolean plus) {
        if (decimals < 0 || decimals >= POW10.length) {
            throw new IllegalArgumentException("decimals " + decimals);
        }
        if (Double.isNaN(value)) {
            return padded("NaN", minWidth);
        }
        if (Double.isInfinite(value)) {
            return padded(value > 0 ? (plus ? "+Inf" : "Inf") : "-Inf", minWidth);
        }
        double magnitude = Math.abs(value);
        long scale = POW10[decimals];
        if (magnitude * scale >= 9.0e17) {
            return append((long) value, minWidth, pad, plus);
        }
        long scaled = (long) Math.floor(magnitude * scale + 0.5);
        long whole = scaled / scale;
        long fraction = scaled % scale;
        boolean negative = value < 0 && scaled != 0;
        int wholeDigits = digits(whole);
        int sign = negative || plus ? 1 : 0;
        int width = sign + wholeDigits + (decimals > 0 ? decimals + 1 : 0);
        int padding = Math.max(0, minWidth - width);
        if (pad != '0') {
            fill(pad, padding);
        }
        if (negative) {
            put('-');
        } else if (plus) {
            put('+');
        }
        if (pad == '0') {
            fill('0', padding);
        }
        writeDigits(whole, wholeDigits);
        if (decimals > 0) {
            put('.');
            for (int at = decimals - 1; at >= 0; at--) {
                scratch[at] = (char) ('0' + fraction % 10);
                fraction /= 10;
            }
            for (int i = 0; i < decimals; i++) {
                put(scratch[i]);
            }
        }
        return this;
    }

    /** {@code [h:]mm:ss}, hours only when non-zero or {@code forceHours}; negative clamps to zero. */
    public TextBuffer appendClock(double seconds, boolean forceHours) {
        long total = Double.isFinite(seconds) ? Math.max(0L, (long) seconds) : 0L;
        long hours = total / 3600;
        long minutes = total / 60 % 60;
        long secs = total % 60;
        if (hours > 0 || forceHours) {
            append(hours, 2, '0', false).append(':');
        }
        return append(minutes, 2, '0', false).append(':').append(secs, 2, '0', false);
    }

    private TextBuffer padded(String text, int minWidth) {
        fill(' ', Math.max(0, minWidth - text.length()));
        return append(text);
    }

    private void fill(char c, int count) {
        for (int i = 0; i < count; i++) {
            put(c);
        }
    }

    private static int digits(long value) {
        if (value == Long.MIN_VALUE) {
            return 19;
        }
        long v = Math.abs(value);
        int n = 1;
        while (v >= 10) {
            v /= 10;
            n++;
        }
        return n;
    }
}
