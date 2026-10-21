package gale.module.impl.render;

import gale.Gale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.Vec3;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Reference-based, camera-facing sprite animation anchored to the victim's world position. */
final class EndKillEffect {
    private static final int MAX_TREES = 4;
    private static final double MAX_DISTANCE_SQ = 96 * 96;
    private static final Sprite[] SPRITES = {
        new Sprite("bud", 2.9), new Sprite("growing", 5.8), new Sprite("mature", 8.0)
    };
    private final List<Tree> trees = new ArrayList<>();
    private final List<Tree> drawing = new ArrayList<>();
    private boolean prepared;
    private long clock;
    private float lastPartial;

    private static final class Tree {
        final Vec3 origin;
        final double born, lifetime, size;
        Tree(Vec3 origin, double lifetime, double size, long clock) {
            this.origin = origin;
            born = clock;
            this.lifetime = lifetime;
            this.size = size;
        }
    }

    private static final class Sprite {
        final ResourceLocation texture;
        final double height;
        double left, right, u0, u1, v0, v1;

        Sprite(String name, double height) {
            this.texture = new ResourceLocation(Gale.ID, "textures/kill/end/" + name + ".png");
            this.height = height;
        }

        void measure(Minecraft mc) throws Exception {
            // Measure the cutout once. Its visible foot, rather than the PNG canvas edge,
            // is the pivot, so transitions cannot slide off the recorded death position.
            try (InputStream in = mc.getResourceManager().getResource(texture).getInputStream()) {
                BufferedImage image = ImageIO.read(in);
                if (image == null || !image.getColorModel().hasAlpha()) throw new IllegalArgumentException("End sprite needs alpha: " + texture);
                int w = image.getWidth(), h = image.getHeight();
                int minX = w, maxX = -1, minY = h, maxY = -1;
                for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                    if ((image.getRGB(x, y) >>> 24) < 24) continue;
                    minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                }
                if (maxX < minX || maxY <= minY) throw new IllegalArgumentException("Empty end sprite: " + texture);
                int footLeft = w, footRight = -1;
                for (int y = Math.max(minY, maxY - Math.max(2, (maxY - minY) / 60)); y <= maxY; y++)
                    for (int x = minX; x <= maxX; x++) if ((image.getRGB(x, y) >>> 24) >= 80) {
                        footLeft = Math.min(footLeft, x); footRight = Math.max(footRight, x);
                    }
                double pivot = footRight < footLeft ? (minX + maxX) * .5 : (footLeft + footRight) * .5;
                double scale = height / (maxY + 1.0 - minY);
                left = (minX - pivot) * scale;
                right = (maxX + 1 - pivot) * scale;
                u0 = minX / (double) w; u1 = (maxX + 1.0) / w;
                v0 = minY / (double) h; v1 = (maxY + 1.0) / h;
            }
        }
    }

    void prepare() {
        if (prepared) return;
        Minecraft mc = Minecraft.getMinecraft();
        int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        int bound = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            for (Sprite sprite : SPRITES) {
                sprite.measure(mc);
                mc.getTextureManager().bindTexture(sprite.texture);
                mc.getTextureManager().getTexture(sprite.texture).setBlurMipmap(true, false);
            }
            prepared = true;
        } catch (Exception ex) {
            Gale.LOG.error("Could not prepare the end reference sprites", ex);
            prepared = false;
        } finally {
            GlStateManager.bindTexture(bound);
            GlStateManager.setActiveTexture(active);
        }
    }

    void spawn(Vec3 origin, double lifetime, double size) {
        if (origin == null || !finite(origin.xCoord) || !finite(origin.yCoord) || !finite(origin.zCoord)) return;
        if (trees.size() == MAX_TREES) trees.remove(0);
        trees.add(new Tree(origin, lifetime, size, clock));
    }

    private static boolean finite(double value) { return !Double.isNaN(value) && !Double.isInfinite(value); }

    void tick() {
        clock++;
        trees.removeIf(t -> clock - t.born >= t.lifetime * 20);
    }

    void clear() { trees.clear(); drawing.clear(); clock = 0; lastPartial = 0; }

    void dispose() { clear(); prepared = false; }

    void render(float partialTicks) {
        Minecraft mc = Minecraft.getMinecraft();
        if (trees.isEmpty() || !prepared || mc.theWorld == null || mc.thePlayer == null) return;
        RenderManager camera = mc.getRenderManager();
        if (!mc.isGamePaused()) lastPartial = partialTicks;
        double now = clock + lastPartial;
        drawing.clear();
        drawing.addAll(trees);
        drawing.sort(Comparator.comparingDouble((Tree t) -> -distanceSquared(t.origin, camera)));
        int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int matrix = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        // Raw GL is isolated from GlStateManager; all attributes and texture units are restored.
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        try {
            GL13.glActiveTexture(OpenGlHelper.lightmapTexUnit);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL13.glActiveTexture(OpenGlHelper.defaultTexUnit);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            GL11.glDepthMask(false);
            GL11.glShadeModel(GL11.GL_SMOOTH);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            for (Tree tree : drawing) {
                if (distanceSquared(tree.origin, camera) > MAX_DISTANCE_SQ) continue;
                double age = Math.max(0, (now - tree.born) / 20);
                if (age >= tree.lifetime) continue;
                float fade = (float) smooth((tree.lifetime - age) / .7);
                GL11.glPushMatrix();
                try {
                    GL11.glTranslated(tree.origin.xCoord - camera.viewerPosX, tree.origin.yCoord - camera.viewerPosY + .015, tree.origin.zCoord - camera.viewerPosZ);
                    // Rotate around the planted foot, never around the player's position.
                    double yaw = Math.toDegrees(Math.atan2(camera.viewerPosX - tree.origin.xCoord, camera.viewerPosZ - tree.origin.zCoord));
                    GL11.glRotated(yaw, 0, 1, 0);
                    GL11.glScaled(tree.size, tree.size, tree.size);
                    double animationRate = Math.min(1, (tree.lifetime - .7) / 2.75);
                    animation(age / animationRate, fade);
                    GL11.glDisable(GL11.GL_TEXTURE_2D);
                    GL11.glDisable(GL11.GL_ALPHA_TEST);
                    mist(age, fade);
                    if (age < 2.15) vortex(age, fade);
                    petals(age, fade);
                } finally { GL11.glPopMatrix(); }
            }
        } finally {
            GL11.glPopMatrix();
            GL11.glMatrixMode(matrix);
            GL11.glPopAttrib();
            GL13.glActiveTexture(active);
        }
    }

    private static void animation(double age, float fade) {
        if (age < .35) return;
        if (age < 1.3) {
            double grow = smooth((age - .35) / .95);
            card(0, .35 + .65 * grow, fade * (float) smooth((age - .35) / .22), age);
        } else if (age < 2.15) {
            float mix = (float) smooth((age - 1.3) / .24);
            card(0, 1, fade * (1 - mix), age);
            card(1, .5 + .5 * smooth((age - 1.3) / .85), fade * mix, age);
        } else {
            float mix = (float) smooth((age - 2.15) / .45);
            card(1, 1, fade * (1 - mix), age);
            card(2, .725 + .275 * smooth((age - 2.15) / .55), fade * mix, age);
        }
    }

    private static void card(int stage, double grow, float alpha, double age) {
        if (alpha <= .002f) return;
        Sprite sprite = SPRITES[stage];
        ITextureObject texture = Minecraft.getMinecraft().getTextureManager().getTexture(sprite.texture);
        if (texture == null) return;
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture.getGlTextureId());
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL11.GL_MODULATE);
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        GL11.glAlphaFunc(GL11.GL_GREATER, .01f);
        GL11.glColor4f(1, 1, 1, alpha);
        // A small vertical grid lets the crown breathe in the wind; its foot remains still.
        GL11.glBegin(GL11.GL_QUADS);
        for (int row = 0; row < 16; row++) {
            spriteVertex(sprite, 0, row, grow, age);
            spriteVertex(sprite, 1, row, grow, age);
            spriteVertex(sprite, 1, row + 1, grow, age);
            spriteVertex(sprite, 0, row + 1, grow, age);
        }
        GL11.glEnd();
    }

    private static void spriteVertex(Sprite sprite, int side, int row, double grow, double age) {
        double t = row / 16.0;
        double wind = Math.sin(age * 1.3 + t * 2.8) * .022 * t * t;
        GL11.glTexCoord2d(side == 0 ? sprite.u0 : sprite.u1, sprite.v1 + (sprite.v0 - sprite.v1) * t);
        GL11.glVertex3d(((side == 0 ? sprite.left : sprite.right) + wind) * grow, sprite.height * t * grow, 0);
    }
    private static void mist(double age, float fade) {
        // Feathered radial discs, no square particle sprites or full-screen bloom.
        float pulse = (float) (.88 + .12 * Math.sin(age * 2));
        for (int layer = 0; layer < 3; layer++) {
            double radius = (1.05 + layer * .34) * (.4 + .6 * smooth(age / .45));
            GL11.glBegin(GL11.GL_TRIANGLE_FAN);
            GL11.glColor4f(1f, age < 1.6 ? .06f : .66f, .77f, fade * .13f * pulse);
            GL11.glVertex3d(0, .025 + layer * .034, 0);
            GL11.glColor4f(.98f, .34f, .58f, 0);
            for (int i = 0; i <= 64; i++) {
                double a = i * Math.PI * 2 / 64;
                GL11.glVertex3d(Math.cos(a) * radius, .025 + layer * .034, Math.sin(a) * radius);
            }
            GL11.glEnd();
        }
    }

    private static void vortex(double age, float fade) {
        double strength = smooth(age / .18) * (1 - smooth((age - 1.15) / .95));
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
        for (int ribbon = 0; ribbon < 3; ribbon++) {
            GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
            for (int j = 0; j <= 96; j++) {
                double t = j / 96.0;
                double a = t * Math.PI * 3.1 - age * 5.8 + ribbon * Math.PI * 2 / 3;
                double r = (1.35 * (1 - t) + .12) * (.35 + .65 * smooth(age / .4));
                double y = .08 + t * Math.min(3.8, .3 + age * 2.3);
                float alpha = (float) (Math.sin(t * Math.PI) * strength * fade);
                GL11.glColor4f(1, .015f, .18f, alpha * .72f);
                GL11.glVertex3d(Math.cos(a) * r, y, Math.sin(a) * r);
                GL11.glColor4f(1, .32f, .51f, alpha * .05f);
                GL11.glVertex3d(Math.cos(a) * (r + .22), y + .12 + .24 * (1 - t), Math.sin(a) * (r + .22));
            }
            GL11.glEnd();
        }
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
    }

    private static void petals(double age, float fade) {
        double mature = smooth((age - 1.15) / 1.0);
        GL11.glBegin(GL11.GL_TRIANGLES);
        for (int i = 0; i < 68; i++) {
            double cycle = (age * (.16 + (i % 7) * .009) + i * .61803398875) % 1;
            double a = i * 2.39996 + age * (.27 + (i % 3) * .08);
            double radius = (.55 + (i % 13) * .15) * (mature > 0 ? 1 : smooth(age / .4));
            double x = Math.cos(a) * radius + Math.sin(age * 1.7 + i) * .12;
            double z = Math.sin(a) * radius;
            double y = mature > 0 ? .1 + (1 - cycle) * (3 + (i % 9) * .36) * mature : .1 + cycle * age * 2;
            float alpha = (float) (fade * Math.sin(cycle * Math.PI) * (age < 2 ? .9 : .75));
            double length = .045 + (i % 4) * .015, tilt = age * 3 + i;
            double dx = Math.cos(tilt) * length, dy = Math.sin(tilt) * length;
            GL11.glColor4f(.93f, .025f, .17f, alpha);
            GL11.glVertex3d(x - dx, y - dy, z);
            GL11.glVertex3d(x + dy * .48, y - dx * .48, z + length * .4);
            GL11.glVertex3d(x + dx, y + dy, z);
            GL11.glColor4f(.64f, .015f, .12f, alpha);
            GL11.glVertex3d(x - dx, y - dy, z);
            GL11.glVertex3d(x + dx, y + dy, z);
            GL11.glVertex3d(x - dy * .48, y + dx * .48, z - length * .4);
        }
        GL11.glEnd();
    }

    private static double smooth(double t) { t = Math.max(0, Math.min(1, t)); return t * t * (3 - 2 * t); }

    private static double distanceSquared(Vec3 origin, RenderManager camera) {
        double x = origin.xCoord - camera.viewerPosX, y = origin.yCoord - camera.viewerPosY, z = origin.zCoord - camera.viewerPosZ;
        return x * x + y * y + z * z;
    }
}
