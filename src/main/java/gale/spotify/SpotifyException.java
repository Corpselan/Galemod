package gale.spotify;

/** A Spotify request that failed: its HTTP status and Spotify's reason (PREMIUM_REQUIRED, NO_ACTIVE_DEVICE ...). */
public final class SpotifyException extends Exception {
    public final int status;
    public final String reason;
    /** for 429: how long Spotify wants us to wait */
    public final long retryAfterMs;

    public SpotifyException(int status, String reason, String message, long retryAfterMs) {
        super(message);
        this.status = status;
        this.reason = reason == null ? "" : reason;
        this.retryAfterMs = retryAfterMs;
    }

    public SpotifyException(int status, String reason, String message) { this(status, reason, message, 0); }

    public boolean premiumRequired() { return reason.equals("PREMIUM_REQUIRED") || status == 403 && String.valueOf(getMessage()).toLowerCase().contains("premium"); }

    public boolean noDevice() { return reason.equals("NO_ACTIVE_DEVICE") || status == 404 && String.valueOf(getMessage()).toLowerCase().contains("device"); }

    /** Said in Chinese, for chat and the player screen. */
    public String explain() {
        if (premiumRequired()) return "控制播放需要 Spotify Premium（免费账号只能看正在放什么）";
        if (noDevice()) return "没有正在用的设备：先打开 Spotify 放一首歌，或在「设备」里选一个";
        if (status == 401) return "登录过期了，重新 /spotify login";
        if (status == 429) return "请求太快，Spotify 让等 " + Math.max(1, retryAfterMs / 1000) + " 秒";
        if (status == 0) return "连不上 Spotify：" + getMessage();
        return "Spotify 出错（" + status + (reason.isEmpty() ? "" : " " + reason) + "）：" + getMessage();
    }
}
