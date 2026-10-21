package gale.netease;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.awt.image.BufferedImage;
import java.util.EnumMap;
import java.util.Map;

/**
 * Logging in by QR code, as on NetEase's website: a code is asked for, shown as a QR picture, and asked about every two
 * seconds until it is scanned and confirmed in the NetEase Cloud Music app (or runs out). The cookie it gives is
 * saved by {@link NeteaseApi#saveCookie}.
 */
public final class NeteaseLogin {
    public enum State { IDLE, LOADING, WAITING, SCANNED, SUCCESS, EXPIRED, ERROR }

    public static volatile State state = State.IDLE;
    /** the QR picture while waiting; version goes up with each new picture */
    public static volatile BufferedImage qr;
    public static volatile int qrVersion;
    public static volatile String error = "";
    private static volatile int session;

    private NeteaseLogin() { }

    public static void start() {
        int my = ++session;
        state = State.LOADING;
        qr = null;
        error = "";
        Thread t = new Thread(() -> run(my), "Gale NetEase login");
        t.setDaemon(true);
        t.start();
    }

    public static void cancel() {
        session++;
        if (state != State.SUCCESS) state = State.IDLE;
        qr = null;
    }

    private static void run(int my) {
        try {
            NeteaseApi.Weapi key = NeteaseApi.weapi(NeteaseApi.base + "/weapi/login/qrcode/unikey", "{\"type\":1}", null);
            JsonElement k = key == null ? null : new JsonParser().parse(key.body).getAsJsonObject().get("unikey");
            String unikey = k == null || k.isJsonNull() ? null : k.getAsString();
            if (unikey == null || unikey.isEmpty()) {
                if (my == session) fail("拿不到二维码");
                return;
            }
            if (my != session) return;
            qr = makeQr("https://music.163.com/login?codekey=" + unikey, 256);
            qrVersion++;
            state = State.WAITING;
            while (my == session) {
                Thread.sleep(2000);
                if (my != session) return;
                NeteaseApi.Weapi r = NeteaseApi.weapi(NeteaseApi.base + "/weapi/login/qrcode/client/login", "{\"key\":\"" + unikey + "\",\"type\":1}", null);
                if (r == null) continue;
                JsonElement c = new JsonParser().parse(r.body).getAsJsonObject().get("code");
                if (c == null) continue;
                switch (c.getAsInt()) {
                    case 801: state = State.WAITING; break;
                    case 802: state = State.SCANNED; break;
                    case 800:
                        state = State.EXPIRED;
                        return;
                    case 803: {
                        String cookie = NeteaseApi.keepCookies(r.setCookies);
                        if (cookie.contains("MUSIC_U=")) {
                            NeteaseApi.saveCookie(cookie);
                            qr = null;
                            state = State.SUCCESS;
                        } else {
                            fail("登录成功了但没拿到登录信息");
                        }
                        return;
                    }
                    default: break;
                }
            }
        } catch (Exception e) {
            if (my == session) fail(e.toString());
        }
    }

    private static void fail(String why) {
        error = why;
        state = State.ERROR;
    }

    public static BufferedImage makeQr(String text, int size) throws Exception {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.MARGIN, 1);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints);
        BufferedImage img = new BufferedImage(m.getWidth(), m.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < m.getHeight(); y++) for (int x = 0; x < m.getWidth(); x++) img.setRGB(x, y, m.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
        return img;
    }
}
