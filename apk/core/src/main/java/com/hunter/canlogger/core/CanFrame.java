package com.hunter.canlogger.core;

/** One CAN frame. key is the combined ID key (see CanId). t is seconds (capture-relative or synthetic), may be 0 when unused. */
public final class CanFrame {
    public final double t;
    public final long key;
    public final byte[] data;

    public CanFrame(double t, long key, byte[] data) {
        this.t = t;
        this.key = key;
        this.data = data;
    }
}
