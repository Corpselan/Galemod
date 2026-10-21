package gale.script.api;

import gale.Gale;
import gale.core.Scheduler;
import gale.script.ScriptEvents;

import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Scripts' network connections: plain TCP (text lines) and WebSocket (text messages), each by a name the script picks.
 * Every connection reads on its own thread; what happens reaches scripts as the "socket" event on the game thread:
 * {name, "open" | "line" | "message" | "close" | "error", text}.
 */
final class ScriptNet {
    private static final Map<String, Conn> OPEN = new ConcurrentHashMap<>();
    private static final SecureRandom RANDOM = new SecureRandom();

    private ScriptNet() { }

    static List<String> names() { return new ArrayList<>(OPEN.keySet()); }

    static void tcp(String name, String host, int port, boolean tls) {
        open(new Conn(name, false) {
            @Override
            void connect() throws IOException {
                socket = openSocket(host, port, tls);
                out = socket.getOutputStream();
                event("open", host + ":" + port);
                BufferedReader r = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while ((line = r.readLine()) != null) event("line", line);
            }

            @Override
            void send(String text) throws IOException { write(text.getBytes(StandardCharsets.UTF_8)); }
        });
    }

    static void websocket(String name, String url) {
        URI uri = URI.create(url);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!scheme.equals("ws") && !scheme.equals("wss")) throw new IllegalArgumentException("WebSocket 地址要以 ws:// 或 wss:// 开头");
        boolean tls = scheme.equals("wss");
        int port = uri.getPort() > 0 ? uri.getPort() : tls ? 443 : 80;
        String path = (uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath()) + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        open(new Conn(name, true) {
            @Override
            void connect() throws Exception {
                socket = openSocket(uri.getHost(), port, tls);
                out = socket.getOutputStream();
                byte[] nonce = new byte[16];
                RANDOM.nextBytes(nonce);
                String key = Base64.getEncoder().encodeToString(nonce);
                String hostHeader = uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
                write(("GET " + path + " HTTP/1.1\r\nHost: " + hostHeader + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + key + "\r\nSec-WebSocket-Version: 13\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                DataInputStream in = new DataInputStream(socket.getInputStream());
                String status = readLine(in), accept = null;
                for (String h; !(h = readLine(in)).isEmpty(); )
                    if (h.toLowerCase().startsWith("sec-websocket-accept:")) accept = h.substring(h.indexOf(':') + 1).trim();
                String want = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                    .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.ISO_8859_1)));
                if (!status.contains(" 101") || !want.equals(accept)) throw new IOException("服务器没有接受 WebSocket：" + status);
                event("open", url);
                ByteArrayOutputStream message = new ByteArrayOutputStream();
                int messageType = 0;
                while (true) {
                    int b0 = in.readUnsignedByte(), b1 = in.readUnsignedByte();
                    boolean fin = (b0 & 0x80) != 0;
                    int opcode = b0 & 0x0F;
                    long len = b1 & 0x7F;
                    if (len == 126) len = in.readUnsignedShort();
                    else if (len == 127) len = in.readLong();
                    byte[] mask = (b1 & 0x80) != 0 ? new byte[4] : null;
                    if (mask != null) in.readFully(mask);
                    if (len > 16 << 20) throw new IOException("消息太大");
                    byte[] data = new byte[(int) len];
                    in.readFully(data);
                    if (mask != null) for (int i = 0; i < data.length; i++) data[i] ^= mask[i % 4];
                    if (opcode == 8) break;
                    if (opcode == 9) {
                        frame(10, data);
                        continue;
                    }
                    if (opcode == 10) continue;
                    if (opcode != 0) messageType = opcode;
                    message.write(data);
                    if (fin) {
                        String text = messageType == 2 ? Base64.getEncoder().encodeToString(message.toByteArray()) : new String(message.toByteArray(), StandardCharsets.UTF_8);
                        event("message", text);
                        message.reset();
                    }
                }
            }

            @Override
            void send(String text) throws IOException { frame(1, text.getBytes(StandardCharsets.UTF_8)); }

            @Override
            void closing() {
                try {
                    frame(8, new byte[0]);
                } catch (IOException ignored) {
                    // closing anyway
                }
            }

            /** A frame from the client: always masked. */
            private void frame(int opcode, byte[] data) throws IOException {
                ByteArrayOutputStream f = new ByteArrayOutputStream();
                f.write(0x80 | opcode);
                if (data.length < 126) f.write(0x80 | data.length);
                else if (data.length < 65536) {
                    f.write(0x80 | 126);
                    f.write(data.length >> 8);
                    f.write(data.length);
                } else {
                    f.write(0x80 | 127);
                    for (int i = 7; i >= 0; i--) f.write((int) ((long) data.length >> (i * 8)));
                }
                byte[] mask = new byte[4];
                RANDOM.nextBytes(mask);
                f.write(mask);
                for (int i = 0; i < data.length; i++) f.write(data[i] ^ mask[i % 4]);
                write(f.toByteArray());
            }
        });
    }

    /** A connected socket; for TLS layered on a plain one, so the server gets the host name (SNI). */
    private static Socket openSocket(String host, int port, boolean tls) throws IOException {
        Socket plain = new Socket();
        plain.connect(new InetSocketAddress(host, port), 10_000);
        if (!tls) return plain;
        return ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(plain, host, port, true);
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
        }
        if (c == -1 && sb.length() == 0) throw new IOException("连接断了");
        return sb.toString();
    }

    private static void open(Conn c) {
        Conn old = OPEN.put(c.name, c);
        if (old != null) old.close();
        Thread t = new Thread(c::run, "Gale script connection " + c.name);
        t.setDaemon(true);
        t.start();
    }

    static void send(String name, String text) throws IOException {
        Conn c = OPEN.get(name);
        if (c == null) throw new IllegalArgumentException("没有叫 " + name + " 的连接");
        if (c.out == null) throw new IllegalStateException(name + " 还没连上（等 \"open\"）");
        c.send(text);
    }

    static boolean close(String name) {
        Conn c = OPEN.remove(name);
        if (c == null) return false;
        c.close();
        return true;
    }

    static int closeAll() {
        int n = 0;
        for (String name : names()) if (close(name)) n++;
        return n;
    }

    private abstract static class Conn {
        final String name;
        final boolean ws;
        volatile Socket socket;
        volatile OutputStream out;
        private volatile boolean closed;

        Conn(String name, boolean ws) {
            this.name = name;
            this.ws = ws;
        }

        abstract void connect() throws Exception;

        abstract void send(String text) throws IOException;

        void closing() { }

        synchronized void write(byte[] b) throws IOException {
            out.write(b);
            out.flush();
        }

        void run() {
            try {
                connect();
            } catch (Exception e) {
                // the other side hanging up is a close, not an error
                if (!closed && !(e instanceof java.io.EOFException)) event("error", e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
            } finally {
                OPEN.remove(name, this);
                closeSocket();
                event("close", "");
            }
        }

        void close() {
            if (closed) return;
            closed = true;
            if (out != null) closing();
            closeSocket();
        }

        private void closeSocket() {
            try {
                if (socket != null) socket.close();
            } catch (IOException ignored) {
                // already gone
            }
        }

        void event(String type, String data) {
            Scheduler.main(() -> {
                try {
                    ScriptEvents.fire("socket", new Object[]{ name, type, data });
                } catch (Throwable t) {
                    Gale.LOG.warn("Script socket event failed", t);
                }
            });
        }
    }
}
