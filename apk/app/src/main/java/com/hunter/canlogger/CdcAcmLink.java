package com.hunter.canlogger;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import com.hunter.canlogger.core.CommandGuard;

import java.io.Closeable;
import java.io.IOException;

/**
 * Minimal hand-written CDC-ACM driver for the CANable (no third-party library).
 * Control interface (class 0x02) gets SET_LINE_CODING + SET_CONTROL_LINE_STATE (DTR, request 0x22);
 * data interface (class 0x0A) carries the bulk endpoints (normally IN 0x81 / OUT 0x01).
 * The ONLY write path is sendCommand(), which goes through CommandGuard: CAN data frames cannot be sent.
 * Nothing in this app can clear or write fault codes, because that would need transmitting CAN frames.
 */
final class CdcAcmLink implements Closeable {
    static final int VID = 0x16D0;
    static final int PID = 0x117E;

    private final UsbDeviceConnection conn;
    private final UsbInterface ctrlIf;
    private final UsbInterface dataIf;
    private final UsbEndpoint epIn;
    private final UsbEndpoint epOut;
    private boolean closed;

    private CdcAcmLink(UsbDeviceConnection conn, UsbInterface ctrlIf, UsbInterface dataIf, UsbEndpoint in, UsbEndpoint out) {
        this.conn = conn;
        this.ctrlIf = ctrlIf;
        this.dataIf = dataIf;
        this.epIn = in;
        this.epOut = out;
    }

    static boolean matches(UsbDevice d) {
        return d != null && d.getVendorId() == VID && d.getProductId() == PID;
    }

    static CdcAcmLink open(UsbManager mgr, UsbDevice dev) throws IOException {
        UsbInterface ctrl = null;
        UsbInterface data = null;
        for (int i = 0; i < dev.getInterfaceCount(); i++) {
            UsbInterface itf = dev.getInterface(i);
            if (itf.getInterfaceClass() == UsbConstants.USB_CLASS_COMM && ctrl == null) ctrl = itf;
            if (itf.getInterfaceClass() == UsbConstants.USB_CLASS_CDC_DATA && data == null) data = itf;
        }
        // Fallback to the documented layout: interface 0 = control, interface 1 = data.
        if (data == null && dev.getInterfaceCount() > 1) data = dev.getInterface(1);
        if (ctrl == null && dev.getInterfaceCount() > 0) ctrl = dev.getInterface(0);
        if (data == null) throw new IOException("No CDC data interface");

        UsbEndpoint in = null;
        UsbEndpoint out = null;
        for (int i = 0; i < data.getEndpointCount(); i++) {
            UsbEndpoint ep = data.getEndpoint(i);
            if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
            if (ep.getDirection() == UsbConstants.USB_DIR_IN && in == null) in = ep;
            if (ep.getDirection() == UsbConstants.USB_DIR_OUT && out == null) out = ep;
        }
        if (in == null || out == null) throw new IOException("Bulk endpoints not found");

        UsbDeviceConnection c = mgr.openDevice(dev);
        if (c == null) throw new IOException("openDevice failed (permission?)");
        try {
            if (ctrl != null && ctrl != data) {
                c.claimInterface(ctrl, true); // not fatal if it fails
            }
            if (!c.claimInterface(data, true)) throw new IOException("claimInterface(data) failed");
            int ctrlId = ctrl != null ? ctrl.getId() : 0;
            // SET_LINE_CODING: 115200 8N1 (ignored by the CANable's USB stack, but standard)
            byte[] lc = new byte[]{(byte) 0x00, (byte) 0xC2, 0x01, 0x00, 0x00, 0x00, 0x08};
            c.controlTransfer(0x21, 0x20, 0, ctrlId, lc, lc.length, 1000);
            // SET_CONTROL_LINE_STATE: DTR on
            c.controlTransfer(0x21, 0x22, 0x01, ctrlId, null, 0, 1000);
        } catch (RuntimeException | IOException e) {
            c.close();
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        }
        return new CdcAcmLink(c, ctrl, data, in, out);
    }

    int maxPacket() {
        int m = epIn.getMaxPacketSize();
        return m > 0 ? m : 64;
    }

    /**
     * Reads one USB transfer. A buffer of exactly one max-packet is used on purpose: the transfer then
     * always completes with the first packet, so a timeout can never discard partially received data.
     * @return bytes read (>0), 0 for none, negative on timeout or error
     */
    int read(byte[] buf, int timeoutMs) {
        return conn.bulkTransfer(epIn, buf, Math.min(buf.length, maxPacket()), timeoutMs);
    }

    /** Sends one allowed SLCAN setup command (C, S0-S8, M1, O, V) followed by CR. Anything else throws IllegalArgumentException. */
    synchronized void sendCommand(String cmd) throws IOException {
        byte[] b = CommandGuard.wireBytes(cmd); // throws IllegalArgumentException for non-allowed commands
        int r = conn.bulkTransfer(epOut, b, b.length, 1000);
        if (r < b.length) throw new IOException("write failed (" + r + ") for " + cmd);
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        try { conn.releaseInterface(dataIf); } catch (RuntimeException ignored) { }
        try { if (ctrlIf != null && ctrlIf != dataIf) conn.releaseInterface(ctrlIf); } catch (RuntimeException ignored) { }
        try { conn.close(); } catch (RuntimeException ignored) { }
    }
}
