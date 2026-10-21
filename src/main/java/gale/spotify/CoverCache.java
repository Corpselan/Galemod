package gale.spotify;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Album covers: downloaded once each, in the background (two at a time), the latest 80 kept. {@link #get} answers
 * at once: the picture, or null while it loads.
 */
public final class CoverCache {
    private static final int KEEP = 80;
    private final Map<String, BufferedImage> images = Collections.synchronizedMap(new LinkedHashMap<String, BufferedImage>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> e) { return size() > KEEP; }
    });
    private final Set<String> loading = Collections.synchronizedSet(new HashSet<>()), failed = Collections.synchronizedSet(new HashSet<>());
    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "Gale Spotify covers");
        t.setDaemon(true);
        return t;
    });

    public BufferedImage get(String url) {
        if (url == null || url.isEmpty()) return null;
        BufferedImage img = images.get(url);
        if (img != null || failed.contains(url) || !loading.add(url)) return img;
        pool.execute(() -> {
            try {
                BufferedImage i = ImageIO.read(new ByteArrayInputStream(Http.bytes(url)));
                if (i != null) images.put(url, i);
                else failed.add(url);
            } catch (Exception e) {
                failed.add(url);
            } finally {
                loading.remove(url);
            }
        });
        return null;
    }
}
