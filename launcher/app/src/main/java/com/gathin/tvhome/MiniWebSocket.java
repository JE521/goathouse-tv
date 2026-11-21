package com.gathin.tvhome;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/** Just enough RFC 6455 for Home Assistant on the LAN: ws:// only, text + binary frames, client masking. */
final class MiniWebSocket {
    interface Listener {
        void onText(String text);
        void onClosed(String why);
        /** Binary message (Sendspin audio), with its arrival time on TimeFilter.nowUs(). */
        default void onBinary(byte[] data, long arrivalUs) { }
    }

    private final Socket socket = new Socket();
    private OutputStream out;
    private final SecureRandom rnd = new SecureRandom();
    private volatile boolean open;

    void connect(String wsUrl, Listener listener) throws IOException {
        URI u = URI.create(wsUrl);
        int port = u.getPort() > 0 ? u.getPort() : 80;
        socket.connect(new InetSocketAddress(u.getHost(), port), 5000);
        socket.setTcpNoDelay(true);
        out = socket.getOutputStream();
        byte[] keyBytes = new byte[16];
        rnd.nextBytes(keyBytes);
        String key = Base64.getEncoder().encodeToString(keyBytes);
        String req = "GET " + (u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath()) + " HTTP/1.1\r\n"
                + "Host: " + u.getHost() + ":" + port + "\r\n"
                + "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\nSec-WebSocket-Version: 13\r\n\r\n";
        out.write(req.getBytes(StandardCharsets.US_ASCII));
        out.flush();
        DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        String status = readLine(in);
        if (status == null || !status.contains(" 101")) throw new IOException("handshake: " + status);
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) { /* skip headers */ }
        open = true;
        Thread reader = new Thread(() -> readLoop(in, listener), "ha-ws");
        reader.setDaemon(true);
        reader.start();
    }

    private void readLoop(DataInputStream in, Listener listener) {
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        int messageOpcode = 0;
        String why = "closed";
        try {
            while (open) {
                int b0 = in.readUnsignedByte(), b1 = in.readUnsignedByte();
                int opcode = b0 & 0x0F;
                boolean fin = (b0 & 0x80) != 0;
                long len = b1 & 0x7F;
                if (len == 126) len = in.readUnsignedShort();
                else if (len == 127) len = in.readLong();
                byte[] mask = null;
                if ((b1 & 0x80) != 0) { mask = new byte[4]; in.readFully(mask); }
                byte[] payload = new byte[(int) len];
                in.readFully(payload);
                if (mask != null) for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
                if (opcode == 0x8) break;                                     // close
                if (opcode == 0x9) { frame(0xA, payload); continue; }          // ping -> pong
                if (opcode == 0xA) continue;
                if (opcode == 0x1 || opcode == 0x2) messageOpcode = opcode;
                message.write(payload);
                if (fin) {
                    if (messageOpcode == 0x2) listener.onBinary(message.toByteArray(), TimeFilter.nowUs());
                    else listener.onText(message.toString("UTF-8"));
                    message.reset();
                }
            }
        } catch (IOException e) {
            why = e.getMessage();
        }
        open = false;
        listener.onClosed(why);
    }

    void sendText(String s) throws IOException { frame(0x1, s.getBytes(StandardCharsets.UTF_8)); }

    void sendBinary(byte[] b, int len) throws IOException {
        byte[] p = len == b.length ? b : java.util.Arrays.copyOf(b, len);
        frame(0x2, p);
    }

    private synchronized void frame(int opcode, byte[] payload) throws IOException {
        if (out == null) throw new IOException("not connected");
        ByteArrayOutputStream f = new ByteArrayOutputStream(payload.length + 14);
        f.write(0x80 | opcode);
        int n = payload.length;
        if (n < 126) f.write(0x80 | n);
        else if (n < 65536) { f.write(0x80 | 126); f.write(n >> 8); f.write(n); }
        else { f.write(0x80 | 127); for (int i = 7; i >= 0; i--) f.write((int) ((long) n >> (8 * i))); }
        byte[] mask = new byte[4];
        rnd.nextBytes(mask);
        f.write(mask);
        for (int i = 0; i < n; i++) f.write(payload[i] ^ mask[i % 4]);
        out.write(f.toByteArray());
        out.flush();
    }

    void close() {
        open = false;
        try { frame(0x8, new byte[0]); } catch (IOException ignored) { }
        try { socket.close(); } catch (IOException ignored) { }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
        }
        return c == -1 && sb.length() == 0 ? null : sb.toString();
    }
}
