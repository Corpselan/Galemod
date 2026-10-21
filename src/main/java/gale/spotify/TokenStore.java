package gale.spotify;

import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Keeps the login (access and refresh token) in one file on this computer, so you log in once. The refresh token is
 * like a password for your Spotify account's playback: it is never logged or sent anywhere but to Spotify.
 */
public final class TokenStore {
    public static final class Tokens {
        public final String access, refresh, scope;
        /** System.currentTimeMillis() when the access token stops working */
        public final long expiresAt;

        public Tokens(String access, String refresh, String scope, long expiresAt) {
            this.access = access;
            this.refresh = refresh;
            this.scope = scope;
            this.expiresAt = expiresAt;
        }

        /** Expired or about to (refreshed a minute early). */
        public boolean stale() { return System.currentTimeMillis() > expiresAt - 60_000; }
    }

    private final File file;

    public TokenStore(File file) { this.file = file; }

    public File file() { return file; }

    public synchronized Tokens load() {
        if (!file.isFile()) return null;
        try {
            JsonObject o = Json.parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            String refresh = Json.str(o, "refresh_token");
            if (refresh == null || refresh.isEmpty()) return null;
            return new Tokens(Json.str(o, "access_token", ""), refresh, Json.str(o, "scope", ""), Json.num(o, "expires_at", 0));
        } catch (Exception e) {
            return null;
        }
    }

    public synchronized void save(Tokens t) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("access_token", t.access);
        o.addProperty("refresh_token", t.refresh);
        o.addProperty("scope", t.scope);
        o.addProperty("expires_at", t.expiresAt);
        File dir = file.getAbsoluteFile().getParentFile();
        if (dir != null && !dir.isDirectory()) dir.mkdirs();
        File tmp = new File(file.getPath() + ".tmp");
        Files.write(tmp.toPath(), o.toString().getBytes(StandardCharsets.UTF_8));
        Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    public synchronized void clear() {
        if (file.isFile() && !file.delete()) file.deleteOnExit();
    }
}
