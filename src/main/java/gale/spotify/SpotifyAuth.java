package gale.spotify;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Spotify's login for apps without a secret (authorization code with PKCE). The browser shows Spotify's own login
 * page; Spotify sends it back to a small page Gale serves on 127.0.0.1, which hands over the code. Only the user's
 * own app (its Client ID) is used.
 */
public final class SpotifyAuth {
    /** Opens the login page (the game opens the browser; the tests follow it themselves). */
    public interface Browser {
        void open(String url) throws Exception;
    }

    private final String clientId;
    private final int port;

    public SpotifyAuth(String clientId, int port) {
        this.clientId = clientId;
        this.port = port;
    }

    public String clientId() { return clientId; }

    public String redirect() { return SpotifyEndpoints.redirect(port); }

    String authorizeUrl(String challenge, String state) {
        return SpotifyEndpoints.accounts() + "/authorize?" + Http.form("client_id", clientId, "response_type", "code", "redirect_uri", redirect(),
            "code_challenge_method", "S256", "code_challenge", challenge, "scope", SpotifyEndpoints.SCOPES, "state", state);
    }

    /** Logs in: blocks until Spotify sends the browser back (or the time runs out). Run it off the game thread. */
    public TokenStore.Tokens login(Browser browser, long timeoutMs) throws Exception {
        String verifier = Pkce.verifier(), state = Pkce.random(16);
        CompletableFuture<String> code = new CompletableFuture<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 0);
        server.createContext("/callback", ex -> {
            Map<String, String> q = query(ex.getRequestURI().getRawQuery());
            String page;
            if (!state.equals(q.get("state"))) {
                page = page("登录没有完成", "这个回调不是这次登录的（state 不对），请回到游戏重新 /spotify login。");
            } else if (q.get("error") != null) {
                code.completeExceptionally(new SpotifyException(0, q.get("error"), "Spotify 没有授权：" + q.get("error")));
                page = page("没有授权", "Spotify 说：" + q.get("error") + "。可以关掉这个页面。");
            } else if (q.get("code") != null) {
                code.complete(q.get("code"));
                page = page("已登录", "回到游戏吧，这个页面可以关掉了。");
            } else {
                page = page("登录没有完成", "回调里没有授权码。");
            }
            byte[] out = page.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, out.length);
            try (OutputStream o = ex.getResponseBody()) {
                o.write(out);
            }
        });
        server.start();
        try {
            browser.open(authorizeUrl(Pkce.challenge(verifier), state));
            String c;
            try {
                c = code.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                throw new SpotifyException(0, "timeout", "等了 " + timeoutMs / 1000 + " 秒没有登录");
            } catch (java.util.concurrent.ExecutionException e) {
                throw e.getCause() instanceof Exception ? (Exception) e.getCause() : e;
            }
            return token(Http.form("grant_type", "authorization_code", "code", c, "redirect_uri", redirect(), "client_id", clientId,
                "code_verifier", verifier), null);
        } finally {
            server.stop(0);
        }
    }

    /** A new access token from the refresh token (Spotify may hand out a new refresh token too). */
    public TokenStore.Tokens refresh(TokenStore.Tokens t) throws SpotifyException {
        return token(Http.form("grant_type", "refresh_token", "refresh_token", t.refresh, "client_id", clientId), t.refresh);
    }

    private TokenStore.Tokens token(String form, String oldRefresh) throws SpotifyException {
        Http.Response r;
        try {
            r = Http.send("POST", SpotifyEndpoints.accounts() + "/api/token", null, "application/x-www-form-urlencoded", form);
        } catch (IOException e) {
            throw new SpotifyException(0, "network", e.toString());
        }
        JsonObject o = Json.parse(r.body);
        if (!r.ok() || o == null || Json.str(o, "access_token") == null) {
            String err = Json.str(o, "error", "");
            throw new SpotifyException(r.status == 400 && err.equals("invalid_grant") ? 401 : r.status, err,
                Json.str(o, "error_description", "token request failed"));
        }
        String refresh = Json.str(o, "refresh_token", oldRefresh);
        return new TokenStore.Tokens(Json.str(o, "access_token"), refresh, Json.str(o, "scope", ""),
            System.currentTimeMillis() + Json.num(o, "expires_in", 3600) * 1000L);
    }

    static Map<String, String> query(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null) return out;
        for (String part : raw.split("&")) {
            int eq = part.indexOf('=');
            try {
                if (eq > 0) out.put(URLDecoder.decode(part.substring(0, eq), "UTF-8"), URLDecoder.decode(part.substring(eq + 1), "UTF-8"));
            } catch (java.io.UnsupportedEncodingException ignored) {
            }
        }
        return out;
    }

    private static String page(String title, String text) {
        return "<!doctype html><html><head><meta charset=\"utf-8\"><title>Gale · Spotify</title><style>body{background:#121212;color:#fff;"
            + "font-family:'Segoe UI',sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;margin:0}"
            + "div{text-align:center}h1{color:#1DB954;font-weight:600}</style></head><body><div><h1>" + escape(title) + "</h1><p>" + escape(text)
            + "</p></div></body></html>";
    }

    // the error text comes from the callback's query: shown as text, never as HTML
    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
