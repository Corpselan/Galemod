package gale.spotify;

/**
 * Where Spotify is. The addresses can be pointed elsewhere with system properties (gale.spotify.accounts,
 * gale.spotify.api): the tests use a local stand-in.
 */
public final class SpotifyEndpoints {
    /** What Gale asks to be allowed: see and control playback, read playlists and liked songs, see the account type. */
    public static final String SCOPES = "user-read-playback-state user-modify-playback-state user-read-currently-playing "
        + "playlist-read-private playlist-read-collaborative user-library-read user-read-private";
    /** The login callback's port; the redirect URI registered for the app must be http://127.0.0.1:PORT/callback. */
    public static final int DEFAULT_PORT = 25616;

    private SpotifyEndpoints() { }

    public static String accounts() { return System.getProperty("gale.spotify.accounts", "https://accounts.spotify.com"); }

    public static String api() { return System.getProperty("gale.spotify.api", "https://api.spotify.com/v1"); }

    public static String redirect(int port) { return "http://127.0.0.1:" + port + "/callback"; }
}
