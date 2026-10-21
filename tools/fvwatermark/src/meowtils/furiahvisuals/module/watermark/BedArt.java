package meowtils.furiahvisuals.module.watermark;

import meowtils.furiahvisuals.core.FuriahsVisuals;
import net.minecraft.block.Block;
import net.minecraft.block.BlockBed;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;

import java.nio.FloatBuffer;

/**
 * The little scene in the Bed Tracker's "breaking your bed" message, where the island would draw its icon: the one
 * breaking it (their own model and skin, swinging at it the whole time) and the block, cracking as far as the server
 * says. The island calls {@link #icon} for every message icon (patched into IslandRenderer.drawNotice); anything but
 * that message gets its icon as before. Minecraft names are SRG.
 */
public final class BedArt {
    /** what the scene shows, set by the Bed Tracker while someone is breaking the bed */
    static volatile int entity = -1, x, y, z, stage;
    static volatile long until;
    /** leading spaces in the message, the room the scene takes beside the icon's own */
    static final String ROOM = "        ";
    private static boolean broken;
    private static final FloatBuffer COLOR = BufferUtils.createFloatBuffer(16);

    private BedArt() { }

    public static void icon(IslandDraw d, IslandIcons.Icon icon, float ix, float iy, float size, int color) {
        if (icon == IslandIcons.Icon.HOURGLASS && !broken && System.currentTimeMillis() < until) {
            try {
                float room = d.width(false, size * 13.0f / 12.0f, ROOM);
                scene(ix, iy, size, size * 1.4f + room);
                return;
            } catch (Throwable t) {
                broken = true;
                FuriahsVisuals.LOG.warn("Bed Tracker: the breaking scene is off ({})", new Object[]{ t.toString() });
            }
        }
        d.icon(icon, ix, iy, size, color);
    }

    private static void scene(float ix, float iy, float size, float width) {
        Minecraft mc = Minecraft.func_71410_x();
        World world = mc.field_71441_e;
        if (world == null) return;
        int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        GL20.glUseProgram(0);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GlStateManager.func_179094_E();
        try {
            float cy = iy + size / 2.0f;
            // the block on the right, cracking
            float bs = size * 1.15f, bx = ix + width - bs - size * 0.1f, by = cy - bs / 2.0f;
            BlockPos pos = new BlockPos(x, y, z);
            IBlockState state = world.func_180495_p(pos);
            Block block = state.func_177230_c();
            Item item = block instanceof BlockBed ? Items.field_151104_aV : Item.func_150898_a(block);
            if (item != null) {
                GlStateManager.func_179094_E();
                GlStateManager.func_179109_b(bx, by, 0.0f);
                GlStateManager.func_179152_a(bs / 16.0f, bs / 16.0f, 1.0f);
                RenderHelper.func_74520_c();
                GlStateManager.func_179091_B();
                mc.func_175599_af().func_180450_b(new ItemStack(item, 1, block instanceof BlockBed ? 0 : block.func_180651_a(state)), 0, 0);
                RenderHelper.func_74518_a();
                GlStateManager.func_179121_F();
            }
            int s = Math.max(0, Math.min(9, stage));
            TextureAtlasSprite crack = mc.func_147117_R().func_110572_b("minecraft:blocks/destroy_stage_" + s);
            if (crack != null) {
                GlStateManager.func_179097_i();
                GlStateManager.func_179140_f();
                GlStateManager.func_179147_l();
                GlStateManager.func_179120_a(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
                GlStateManager.func_179098_w();
                GlStateManager.func_179131_c(1.0f, 1.0f, 1.0f, 0.9f);
                mc.func_110434_K().func_110577_a(TextureMap.field_110575_b);
                float inset = bs * 0.12f;
                Tessellator t = Tessellator.func_178181_a();
                WorldRenderer wr = t.func_178180_c();
                wr.func_181668_a(GL11.GL_QUADS, DefaultVertexFormats.field_181707_g);
                wr.func_181662_b(bx + inset, by + bs - inset, 300.0).func_181673_a(crack.func_94209_e(), crack.func_94210_h()).func_181675_d();
                wr.func_181662_b(bx + bs - inset, by + bs - inset, 300.0).func_181673_a(crack.func_94212_f(), crack.func_94210_h()).func_181675_d();
                wr.func_181662_b(bx + bs - inset, by + inset, 300.0).func_181673_a(crack.func_94212_f(), crack.func_94206_g()).func_181675_d();
                wr.func_181662_b(bx + inset, by + inset, 300.0).func_181673_a(crack.func_94209_e(), crack.func_94206_g()).func_181675_d();
                t.func_78381_a();
                GlStateManager.func_179131_c(1.0f, 1.0f, 1.0f, 1.0f);
            }
            // the one breaking it, on the left, turned to the block and swinging
            Entity e = entity < 0 ? null : world.func_73045_a(entity);
            if (e instanceof EntityLivingBase) figure((EntityLivingBase) e, ix + size * 0.6f, cy + size * 0.95f, size * 0.88f);
        } finally {
            GlStateManager.func_179121_F();
            GL11.glPopAttrib();
            resync();
            GL20.glUseProgram(program);
        }
    }

    /** As the inventory draws you, but at any size, side on, mid-swing. */
    private static void figure(EntityLivingBase e, float fx, float fy, float scale) {
        float yawOffset = e.field_70761_aq, prevYawOffset = e.field_70760_ar, yaw = e.field_70177_z, prevYaw = e.field_70126_B;
        float pitch = e.field_70125_A, prevPitch = e.field_70127_C, head = e.field_70759_as, prevHead = e.field_70758_at;
        float swing = e.field_70733_aJ, prevSwing = e.field_70732_aI;
        boolean swinging = e.field_82175_bq;
        try {
            float phase = System.currentTimeMillis() % 330L / 330.0f;
            e.field_70733_aJ = phase;
            e.field_70732_aI = phase;
            e.field_82175_bq = true;
            // side on, facing right (the block), looking a little down at it
            e.field_70761_aq = e.field_70760_ar = -90.0f;
            e.field_70177_z = e.field_70126_B = -90.0f;
            e.field_70759_as = e.field_70758_at = -90.0f;
            e.field_70125_A = e.field_70127_C = 15.0f;
            GlStateManager.func_179126_j();
            GlStateManager.func_179142_g();
            GlStateManager.func_179094_E();
            GlStateManager.func_179109_b(fx, fy, 50.0f);
            GlStateManager.func_179152_a(-scale, scale, scale);
            GlStateManager.func_179114_b(180.0f, 0.0f, 0.0f, 1.0f);
            GlStateManager.func_179114_b(135.0f, 0.0f, 1.0f, 0.0f);
            RenderHelper.func_74519_b();
            GlStateManager.func_179114_b(-135.0f, 0.0f, 1.0f, 0.0f);
            RenderManager rm = Minecraft.func_71410_x().func_175598_ae();
            rm.func_178631_a(180.0f);
            rm.func_178633_a(false);
            rm.func_147940_a(e, 0.0, 0.0, 0.0, 0.0f, 1.0f);
            rm.func_178633_a(true);
            GlStateManager.func_179121_F();
            RenderHelper.func_74518_a();
            GlStateManager.func_179101_C();
            GlStateManager.func_179138_g(OpenGlHelper.field_77476_b);
            GlStateManager.func_179090_x();
            GlStateManager.func_179138_g(OpenGlHelper.field_77478_a);
        } finally {
            e.field_70761_aq = yawOffset;
            e.field_70760_ar = prevYawOffset;
            e.field_70177_z = yaw;
            e.field_70126_B = prevYaw;
            e.field_70125_A = pitch;
            e.field_70127_C = prevPitch;
            e.field_70759_as = head;
            e.field_70758_at = prevHead;
            e.field_70733_aJ = swing;
            e.field_70732_aI = prevSwing;
            e.field_82175_bq = swinging;
        }
    }

    /** After glPopAttrib: tell the game's state cache what GL really is again, so its next calls are not skipped. */
    private static void resync() {
        cap(GL11.GL_DEPTH_TEST, 0);
        cap(GL11.GL_BLEND, 1);
        cap(GL11.GL_ALPHA_TEST, 2);
        cap(GL11.GL_LIGHTING, 3);
        cap(GL11.GL_CULL_FACE, 4);
        cap(GL11.GL_COLOR_MATERIAL, 5);
        cap(0x803A, 6); // GL_RESCALE_NORMAL
        for (int i = 0; i < 2; i++) {
            if (GL11.glIsEnabled(GL11.GL_LIGHT0 + i)) GlStateManager.func_179085_a(i);
            else GlStateManager.func_179122_b(i);
        }
        GlStateManager.func_179132_a(GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK));
        GlStateManager.func_179120_a(GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB), GL11.glGetInteger(GL14.GL_BLEND_DST_RGB), GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA),
            GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA));
        int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        for (int unit : new int[]{ OpenGlHelper.field_77476_b, OpenGlHelper.field_77478_a }) {
            GlStateManager.func_179138_g(unit);
            if (GL11.glIsEnabled(GL11.GL_TEXTURE_2D)) GlStateManager.func_179098_w();
            else GlStateManager.func_179090_x();
            GlStateManager.func_179144_i(GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D));
        }
        GlStateManager.func_179138_g(active);
        COLOR.clear();
        GL11.glGetFloat(GL11.GL_CURRENT_COLOR, COLOR);
        GlStateManager.func_179131_c(COLOR.get(0), COLOR.get(1), COLOR.get(2), COLOR.get(3));
    }

    private static void cap(int cap, int which) {
        boolean on = GL11.glIsEnabled(cap);
        switch (which) {
            case 0: if (on) GlStateManager.func_179126_j(); else GlStateManager.func_179097_i(); break;
            case 1: if (on) GlStateManager.func_179147_l(); else GlStateManager.func_179084_k(); break;
            case 2: if (on) GlStateManager.func_179141_d(); else GlStateManager.func_179118_c(); break;
            case 3: if (on) GlStateManager.func_179145_e(); else GlStateManager.func_179140_f(); break;
            case 4: if (on) GlStateManager.func_179089_o(); else GlStateManager.func_179129_p(); break;
            case 5: if (on) GlStateManager.func_179142_g(); else GlStateManager.func_179119_h(); break;
            default: if (on) GlStateManager.func_179091_B(); else GlStateManager.func_179101_C(); break;
        }
    }
}
