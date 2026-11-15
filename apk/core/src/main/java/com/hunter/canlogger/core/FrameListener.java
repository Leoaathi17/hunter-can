package com.hunter.canlogger.core;

public interface FrameListener {
    /** data is a fresh array of length dlc (0..8); the listener may keep it. */
    void onFrame(int id, boolean ext, byte[] data);
}
