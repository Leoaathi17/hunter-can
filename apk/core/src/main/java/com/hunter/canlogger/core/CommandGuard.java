package com.hunter.canlogger.core;

/**
 * Safety gate for everything written to the CANable. Only SLCAN setup commands are allowed:
 * C (close), S0..S8 (bitrate), M1 (listen-only), O (open), V (version).
 * CAN data frames ("t...", "T...", "r...") can never pass. Nothing here can clear or write fault codes.
 */
public final class CommandGuard {
    private CommandGuard() {}

    public static boolean isAllowed(String cmd) {
        if (cmd == null) return false;
        if (cmd.length() == 1) return cmd.charAt(0) == 'C' || cmd.charAt(0) == 'O' || cmd.charAt(0) == 'V';
        if (cmd.length() == 2) {
            char a = cmd.charAt(0), b = cmd.charAt(1);
            return (a == 'S' && b >= '0' && b <= '8') || (a == 'M' && b == '1');
        }
        return false;
    }

    public static void requireAllowed(String cmd) {
        if (!isAllowed(cmd)) throw new IllegalArgumentException("blocked command: " + cmd);
    }

    /** The exact bytes to put on the wire for an allowed command (command + CR). */
    public static byte[] wireBytes(String cmd) {
        requireAllowed(cmd);
        byte[] b = new byte[cmd.length() + 1];
        for (int i = 0; i < cmd.length(); i++) b[i] = (byte) cmd.charAt(i);
        b[cmd.length()] = '\r';
        return b;
    }
}
