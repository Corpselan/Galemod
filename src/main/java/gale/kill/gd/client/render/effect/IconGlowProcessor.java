/* Adapted from GD656Killicon by Minecraft_GD656 (MIT); see META-INF/GD656-NOTICE.txt. */
package gale.kill.gd.client.render.effect;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ResourceLocation;
import gale.kill.gd.compat.Mth;
import gale.kill.gd.client.textures.ExternalTextureManager;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.*;
import java.util.Map;

public final class IconGlowProcessor {
    private static final int MAX_ENTRIES = 48;
    private static final long MAX_PIXELS = 16L * 1024 * 1024; // 64 MiB of RGBA, across all presets
    private static long cachedPixels;
    private static final Map<CacheKey, CachedTexture> CACHE = new LinkedHashMap<>(16, .75f, true);
    private static final Set<CacheKey> FAILED = new LinkedHashSet<>();
    private static final Map<CacheKey, FutureTask<BufferedImage>> PENDING = new LinkedHashMap<>();
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<Runnable>(8), task -> {
            Thread thread = new Thread(task, "Gale GD texture preparation"); thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.AbortPolicy());

    private static final class CachedTexture {
        final ResourceLocation location; final long pixels;
        CachedTexture(ResourceLocation location, long pixels) { this.location = location; this.pixels = pixels; }
    }

    private static final class CacheKey {
        final String path; final boolean tint; final int color, brightness, contrast, hash;
        CacheKey(String path, boolean tint, int color, float brightness, float contrast) {
            this.path = path; this.tint = tint; this.color = tint ? color & 0xFFFFFF : 0;
            this.brightness = Math.round(brightness * 1000); this.contrast = Math.round(contrast * 1000);
            int h = path.hashCode(); h = 31*h + (tint ? 1 : 0); h = 31*h + this.color;
            h = 31*h + this.brightness; this.hash = 31*h + this.contrast;
        }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            if (!(other instanceof CacheKey)) return false;
            CacheKey k = (CacheKey) other;
            return tint == k.tint && color == k.color && brightness == k.brightness && contrast == k.contrast && path.equals(k.path);
        }
    }

    private IconGlowProcessor() {
    }

    public static void clearCache() {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft != null) for (CachedTexture texture : CACHE.values())
            minecraft.getTextureManager().deleteTexture(texture.location);
        CACHE.clear(); FAILED.clear(); cachedPixels = 0;
        for (FutureTask<BufferedImage> task : PENDING.values()) task.cancel(true);
        PENDING.clear(); WORKER.purge();
    }

    /** Enqueues CPU work only. Texture registration always remains on the client render thread. */
    public static void prepare(String presetId, String path, boolean tint, int color, float brightness, float contrast) {
        if (path == null || path.isEmpty()) return;
        CacheKey key = new CacheKey(path, tint, color, brightness, contrast);
        if (CACHE.containsKey(key) || PENDING.containsKey(key) || FAILED.contains(key)) return;
        if (PENDING.size() >= 8) {
            Iterator<FutureTask<BufferedImage>> old = PENDING.values().iterator();
            while (old.hasNext() && PENDING.size() >= 8) if (old.next().isDone()) old.remove();
            if (PENDING.size() >= 8) return;
        }
        FutureTask<BufferedImage> task = new FutureTask<>(() -> {
            BufferedImage source = ExternalTextureManager.readTextureImage(presetId, path);
            if (source == null) throw new IOException("Unsupported texture: " + path);
            int width = source.getWidth(), height = source.getHeight();
            int[] pixels = source.getRGB(0, 0, width, height, null, 0, width);
            for (int i = 0; i < pixels.length; i++) {
                if ((i & 16383) == 0 && Thread.currentThread().isInterrupted()) throw new CancellationException();
                pixels[i] = processTexturePixel(pixels[i], tint, color, brightness, contrast);
            }
            BufferedImage processed = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            processed.setRGB(0, 0, width, height, pixels, 0, width);
            return processed;
        });
        PENDING.put(key, task);
        try { WORKER.execute(task); } catch (RejectedExecutionException full) { PENDING.remove(key); }
    }

    public static ResourceLocation getOrCreateProcessedTexture(String presetId, String texturePath, boolean accentTintEnabled, int accentColor, float brightness, float contrast) {
        if (texturePath == null || texturePath.isEmpty()) return null;
        CacheKey key = new CacheKey(texturePath, accentTintEnabled, accentColor, brightness, contrast);
        CachedTexture cached = CACHE.get(key);
        if (cached != null) return cached.location;
        if (FAILED.contains(key)) return null;
        FutureTask<BufferedImage> task = PENDING.get(key);
        if (task == null) { prepare(presetId, texturePath, accentTintEnabled, accentColor, brightness, contrast); return null; }
        if (!task.isDone()) return null; // Keep the original texture while CPU work completes.
        PENDING.remove(key);
        try {
            BufferedImage processed = task.get();
            ResourceLocation location = registerBufferedTexture("processed", key.hashCode(), processed);
            long pixels = (long) processed.getWidth() * processed.getHeight();
            CACHE.put(key, new CachedTexture(location, pixels)); cachedPixels += pixels;
            Iterator<Map.Entry<CacheKey, CachedTexture>> entries = CACHE.entrySet().iterator();
            while (CACHE.size() > 1 && (CACHE.size() > MAX_ENTRIES || cachedPixels > MAX_PIXELS)) {
                CachedTexture old = entries.next().getValue(); entries.remove(); cachedPixels -= old.pixels;
                Minecraft.getMinecraft().getTextureManager().deleteTexture(old.location);
            }
            return location;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); return null;
        } catch (ExecutionException | CancellationException failed) {
            if (FAILED.size() >= MAX_ENTRIES) FAILED.remove(FAILED.iterator().next());
            FAILED.add(key); return null;
        }
    }

    public static int applyBrightnessContrastToRgb(int rgb, float brightness, float contrast) {
        int red = applyBrightnessContrastToChannel((rgb >> 16) & 0xFF, brightness, contrast);
        int green = applyBrightnessContrastToChannel((rgb >> 8) & 0xFF, brightness, contrast);
        int blue = applyBrightnessContrastToChannel(rgb & 0xFF, brightness, contrast);
        return (red << 16) | (green << 8) | blue;
    }

    private static ResourceLocation registerBufferedTexture(String prefix, int hash, BufferedImage image) {
        // DynamicTexture uploads this BufferedImage directly; encoding it to PNG served no purpose.
        DynamicTexture texture = new DynamicTexture(image);
        String dynamicName = "gd656killicon_" + prefix + "_" + Integer.toHexString(hash);
        return Minecraft.getMinecraft().getTextureManager().getDynamicTextureLocation(dynamicName, texture);
    }

    private static int processTexturePixel(int argb, boolean accentTintEnabled, int accentColor, float brightness, float contrast) {
        int alpha = (argb >>> 24) & 0xFF;
        if (alpha <= 0) {
            return 0;
        }

        int red = (argb >> 16) & 0xFF;
        int green = (argb >> 8) & 0xFF;
        int blue = argb & 0xFF;

        if (accentTintEnabled && isAccentPixel(argb)) {
            float luminance = Math.max(red, Math.max(green, blue)) / 255.0f;
            red = Math.round(((accentColor >> 16) & 0xFF) * luminance);
            green = Math.round(((accentColor >> 8) & 0xFF) * luminance);
            blue = Math.round((accentColor & 0xFF) * luminance);
        }

        red = applyBrightnessContrastToChannel(red, brightness, contrast);
        green = applyBrightnessContrastToChannel(green, brightness, contrast);
        blue = applyBrightnessContrastToChannel(blue, brightness, contrast);
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    private static boolean isAccentPixel(int argb) {
        int alpha = (argb >>> 24) & 0xFF;
        if (alpha < 16) {
            return false;
        }
        int red = (argb >> 16) & 0xFF;
        int green = (argb >> 8) & 0xFF;
        int blue = argb & 0xFF;
        int maximum = Math.max(red, Math.max(green, blue));
        int minimum = Math.min(red, Math.min(green, blue));
        float saturation = maximum == 0 ? 0 : (maximum - minimum) / (float) maximum;
        float brightness = maximum / 255.0f;
        return brightness >= 0.18f && brightness <= 0.98f && saturation >= 0.18f;
    }

    private static int applyBrightnessContrastToChannel(int channel, float brightness, float contrast) {
        float normalized = channel / 255.0f;
        float contrasted = ((normalized - 0.5f) * contrast) + 0.5f;
        float adjusted = contrasted * brightness;
        return Mth.clamp(Math.round(adjusted * 255.0f), 0, 255);
    }
}
