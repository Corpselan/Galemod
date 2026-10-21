package gale.spotify;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Plain HTTP for the Spotify client (HttpURLConnection; no other libraries). */
final class Http {
    static final int TIMEOUT = 8000;

    private Http() { }

    static final class Response {
        final int status;
        final String body;
        final String retryAfter;

        Response(int status, String body, String retryAfter) {
            this.status = status;
            this.body = body;
            this.retryAfter = retryAfter;
        }

        boolean ok() { return status >= 200 && status < 300; }
    }

    /** One request; body may be null (an empty body is still sent for PUT and POST, as Spotify wants). */
    static Response send(String method, String url, Map<String, String> headers, String contentType, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(TIMEOUT);
        c.setReadTimeout(TIMEOUT);
        c.setUseCaches(false);
        c.setRequestProperty("Accept", "application/json");
        if (headers != null) for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
        if (method.equals("PUT") || method.equals("POST")) {
            byte[] data = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            c.setDoOutput(true);
            if (contentType != null) c.setRequestProperty("Content-Type", contentType);
            c.setFixedLengthStreamingMode(data.length);
            try (OutputStream out = c.getOutputStream()) {
                out.write(data);
            }
        }
        int status = c.getResponseCode();
        InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
        String text = in == null ? "" : read(in);
        String retry = c.getHeaderField("Retry-After");
        c.disconnect();
        return new Response(status, text, retry);
    }

    static byte[] bytes(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(TIMEOUT);
        c.setReadTimeout(TIMEOUT);
        try (InputStream in = c.getInputStream()) {
            return readBytes(in);
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        try (InputStream i = in) {
            return new String(readBytes(i), StandardCharsets.UTF_8);
        }
    }

    private static byte[] readBytes(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return out.toByteArray();
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** a=1&b=2 */
    static String form(String... pairs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            if (pairs[i + 1] == null) continue;
            if (sb.length() > 0) sb.append('&');
            sb.append(enc(pairs[i])).append('=').append(enc(pairs[i + 1]));
        }
        return sb.toString();
    }
}
