package com.hunter.canlogger.core;

/** Display / record filter. Both frame types are always parsed; this only decides what is shown and recorded. */
public enum IdFormat {
    BOTH("both", "Show both"),
    STD11("11", "11-bit only"),
    EXT29("29", "29-bit only");

    public final String code;
    public final String label;

    IdFormat(String code, String label) { this.code = code; this.label = label; }

    public boolean accepts(boolean ext) {
        return this == BOTH || (this == STD11 && !ext) || (this == EXT29 && ext);
    }

    public static IdFormat fromCode(String c) {
        for (IdFormat f : values()) if (f.code.equals(c)) return f;
        return BOTH;
    }
}
