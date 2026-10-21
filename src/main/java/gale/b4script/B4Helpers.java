package gale.b4script;

import gale.core.RotationManager;
import gale.core.ServerState;
import gale.util.ChatUtil;
import gale.util.ReflectUtil;
import net.minecraft.block.Block;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.BlockDoor;
import net.minecraft.block.BlockFence;
import net.minecraft.block.BlockFenceGate;
import net.minecraft.block.BlockTrapDoor;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiScreenBook;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.resources.model.IBakedModel;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.EnumAction;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.client.ForgeHooksClient;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * B4's own helpers that its script API calls (OpenB4's PlayerUtils, MoveUtils, RotationUtils, WorldUtils, BlockUtils,
 * RenderUtils, Render3D, Hypixel and Theme), copied with B4's default settings: rotations aim 90% toward the closest
 * point and jitter by up to 3 mouse steps, only swords count as weapons, the theme is Frost at time multiplier 0.5.
 * Where B4 asks its own rotation manager, these ask Gale's ({@link RotationManager}, {@link ServerState}).
 */
public final class B4Helpers {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final double AIM_CLOSEST_POINT = 90.0;
    private static final int JITTER_YAW = 3;
    private static final Method CLICK_MOUSE = ReflectUtil.method(Minecraft.class, new String[]{ "clickMouse", "func_147116_af" });
    private static final Method RIGHT_CLICK_MOUSE = ReflectUtil.method(Minecraft.class, new String[]{ "rightClickMouse", "func_147121_ag" });
    private static final Field BOOK_PAGES = ReflectUtil.field(GuiScreenBook.class, "bookPages", "field_146483_y");

    private B4Helpers() { }

    // ------------------------------------------------------------------ PlayerUtils

    public enum MoveDirection {
        NONE(false), FORWARD(false), FORWARD_LEFT(true), FORWARD_RIGHT(true), BACK(false), BACK_LEFT(true), BACK_RIGHT(true), LEFT(false), RIGHT(false);

        public final boolean diagonal;

        MoveDirection(boolean diagonal) { this.diagonal = diagonal; }
    }

    public static boolean holdingWeapon(net.minecraft.entity.EntityLivingBase entity) {
        ItemStack s = entity.getHeldItem();
        return s != null && s.getItem() instanceof ItemSword;
    }

    // B4 asks whether YOU are using an item, then reads the other player's item in use (null when they use none)
    public static boolean isEating(EntityPlayer player) {
        if (mc.thePlayer.isUsingItem() && player.getItemInUse() != null) {
            EnumAction action = player.getItemInUse().getItem().getItemUseAction(player.getItemInUse());
            return action == EnumAction.EAT || action == EnumAction.DRINK;
        }
        return false;
    }

    public static double heightAboveGround(Entity entity) {
        double x = entity.posX, y = entity.posY, z = entity.posZ;
        Vec3 from = new Vec3(x, y + mc.thePlayer.getEyeHeight(), z);
        Vec3 dir = vectorForRotation(0.0f, 90.0f);
        MovingObjectPosition hit = mc.theWorld.rayTraceBlocks(from, from.addVector(dir.xCoord * 100.0, dir.yCoord * 100.0, dir.zCoord * 100.0), false, false, false);
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) return -1.0;
        Block block = mc.theWorld.getBlockState(hit.getBlockPos()).getBlock();
        if (block.getMaterial().isSolid() || block == Blocks.carpet || block == Blocks.waterlily) y -= block.getBlockBoundsMaxY();
        return Math.max(y - hit.getBlockPos().getY(), 0.0);
    }

    public static boolean isAirBelow(Entity entity) {
        double y = entity.posY;
        return mc.theWorld.isAirBlock(new BlockPos(entity.posX, y - (y % 1.0 == 0.0 ? 1 : 0), entity.posZ));
    }

    public static float forwardInput() {
        float f = 0f;
        if (mc.gameSettings.keyBindForward.isKeyDown()) f += 1f;
        if (mc.gameSettings.keyBindBack.isKeyDown()) f -= 1f;
        return f;
    }

    public static float strafeInput() {
        float f = 0f;
        if (mc.gameSettings.keyBindLeft.isKeyDown()) f += 1f;
        if (mc.gameSettings.keyBindRight.isKeyDown()) f -= 1f;
        return f;
    }

    public static MoveDirection direction(double forward, double strafe) {
        if (forward == 0.0) return strafe == 0.0 ? MoveDirection.NONE : strafe > 0.0 ? MoveDirection.LEFT : MoveDirection.RIGHT;
        if (forward > 0.0) return strafe == 0.0 ? MoveDirection.FORWARD : strafe > 0.0 ? MoveDirection.FORWARD_LEFT : MoveDirection.FORWARD_RIGHT;
        return strafe == 0.0 ? MoveDirection.BACK : strafe > 0.0 ? MoveDirection.BACK_LEFT : MoveDirection.BACK_RIGHT;
    }

    public static MoveDirection direction() { return direction(forwardInput(), strafeInput()); }

    /** The keys as seen from another yaw (the server's): what the server sees you walk toward. */
    public static MoveDirection direction(float yaw) {
        float moveYaw = movementYaw();
        if (yaw == moveYaw) return direction();
        double rad = Math.toRadians(yaw - moveYaw), cos = Math.cos(rad), sin = Math.sin(rad);
        double forward = mc.thePlayer.movementInput.moveForward, strafe = mc.thePlayer.movementInput.moveStrafe;
        return direction(Math.round(forward * cos - strafe * sin), Math.round(forward * sin + strafe * cos));
    }

    public static boolean diagonalYaw() {
        float f = Math.abs(mc.thePlayer.rotationYaw % 90.0f);
        return f > 10.0f && f < 80.0f;
    }

    public static boolean isDiagonal() { return diagonalYaw() || direction().diagonal; }

    public static void clickMouse() { ReflectUtil.invoke(CLICK_MOUSE, mc); }

    public static void rightClickMouse() { ReflectUtil.invoke(RIGHT_CLICK_MOUSE, mc); }

    public static void attack(Entity entity) {
        mc.thePlayer.swingItem();
        mc.playerController.attackEntity(mc.thePlayer, entity);
    }

    public static void ping() { mc.thePlayer.playSound("note.pling", 1.0f, 1.0f); }

    public static List<String> bookPages() {
        if (!(mc.currentScreen instanceof GuiScreenBook)) return null;
        NBTTagList pages = ReflectUtil.get(BOOK_PAGES, mc.currentScreen);
        if (pages == null) return null;
        List<String> list = new ArrayList<>();
        for (int i = 0; i < pages.tagCount(); ++i) list.add(pages.getStringTagAt(i));
        return list;
    }

    /** The open screen's class name; Gale's own settings screen counts as B4's ("GuiRaven"). */
    public static String screenName(GuiScreen screen) {
        if (screen == null) return "";
        if (screen instanceof gale.ui.ClickGui) return "GuiRaven";
        return screen.getClass().getSimpleName();
    }

    // ------------------------------------------------------------------ MoveUtils

    /** The yaw you move along: a script's getMovementYaw, the server rotation when moving along it, else the camera. */
    public static float movementYaw() {
        Float f = ScriptEvents.movementYawNow();
        return f != null ? f : RotationManager.cameraYaw();
    }

    public static float moveYaw(float yaw) {
        float forward = mc.thePlayer.movementInput.moveForward, strafe = mc.thePlayer.movementInput.moveStrafe, f4 = 1.0f;
        if (forward < 0.0f) {
            yaw += 180.0f;
            f4 = -0.5f;
        } else if (forward > 0.0f) {
            f4 = 0.5f;
        }
        if (strafe > 0.0f) yaw -= 90.0f * f4;
        else if (strafe < 0.0f) yaw += 90.0f * f4;
        return yaw;
    }

    public static void setSpeed(double speed, float yaw) {
        if (speed == 0.0) {
            mc.thePlayer.motionZ = 0.0;
            mc.thePlayer.motionX = 0.0;
            return;
        }
        mc.thePlayer.motionX = -Math.sin(yaw *= (float) Math.PI / 180) * speed;
        mc.thePlayer.motionZ = Math.cos(yaw) * speed;
    }

    public static void setSpeedForward(double speed) {
        float yaw = mc.thePlayer.rotationYaw;
        if (mc.thePlayer.movementInput.moveForward < 0.0f) yaw += 180.0f;
        setSpeed(speed, yaw);
    }

    public static void strafe(double speed) { setSpeed(speed, moveYaw(movementYaw())); }

    // ------------------------------------------------------------------ RotationUtils (B4's "fixed" rotations)

    /** What B4 calls the current rotation: the server one while a silent rotation is in use, else yours. */
    private static float[] currentRotation() {
        if (RotationManager.isActive()) return new float[]{ RotationManager.yaw(), RotationManager.pitch() };
        return new float[]{ mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch };
    }

    /** The rotation the server last got (B4's RotationManager.lastYaw / lastPitch). */
    private static float[] lastSent() {
        if (ServerState.lastMovementPacket() == 0) return new float[]{ mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch };
        return new float[]{ ServerState.yaw(), ServerState.pitch() };
    }

    private static float clampPitch(float pitch) { return MathHelper.clamp_float(pitch, -90.0f, 90.0f); }

    public static float[] fromDelta(double dx, double dy, double dz) {
        double d4 = Math.sqrt(dx * dx + dz * dz);
        float f = (float) (Math.atan2(dz, dx) * 57.29578f) - 90.0f;
        float f2 = (float) (-(Math.atan2(dy, d4) * 57.29578f));
        float[] current = currentRotation();
        f = current[0] + MathHelper.wrapAngleTo180_float(f - current[0]);
        f2 = clampPitch(current[1] + MathHelper.wrapAngleTo180_float(f2 - current[1]));
        return new float[]{ f, f2 };
    }

    public static float[] fixSensitivity(float yaw, float pitch, float lastYaw, float lastPitch) {
        float f5 = yaw - lastYaw, f6 = Math.abs(f5), f7 = pitch - lastPitch;
        float f8 = mc.gameSettings.mouseSensitivity * 0.6f + 0.2f;
        double d = (double) (f8 * f8 * f8) * 1.2;
        float f9 = (float) ((double) Math.round(f5 / d) * d);
        float f10 = (float) ((double) Math.round(f7 / d) * d);
        yaw = lastYaw + f9;
        pitch = lastPitch + f10;
        if (f6 >= d && JITTER_YAW > 0) {
            int n2 = (int) (Math.random() * (JITTER_YAW * 2 + 1)) - JITTER_YAW;
            yaw += (float) (n2 * d);
        }
        return new float[]{ yaw, clampPitch(pitch) };
    }

    public static float[] toBlockFixed(BlockPos pos, EnumFacing facing) {
        double d4 = pos.getX() + 0.45 - mc.thePlayer.posX;
        double d5 = pos.getY() + 0.45 - (mc.thePlayer.posY + mc.thePlayer.getEyeHeight());
        double d6 = pos.getZ() + 0.45 - mc.thePlayer.posZ;
        if (facing != null) {
            d4 += facing.getDirectionVec().getX() * 0.5;
            d5 += facing.getDirectionVec().getY() * 0.5;
            d6 += facing.getDirectionVec().getZ() * 0.5;
        }
        float[] r = fromDelta(d4, d5, d6), last = lastSent();
        return fixSensitivity(r[0], r[1], last[0], last[1]);
    }

    public static float[] toPositionFixed(double x, double y, double z) {
        float[] r = fromDelta(x - mc.thePlayer.posX, y - (mc.thePlayer.posY + mc.thePlayer.getEyeHeight()), z - mc.thePlayer.posZ), last = lastSent();
        return fixSensitivity(r[0], r[1], last[0], last[1]);
    }

    public static float[] toEntityFixed(Entity entity) {
        if (entity == null) return null;
        float f = entity.getCollisionBorderSize();
        AxisAlignedBB box = entity.getEntityBoundingBox().expand(f, f, f);
        Vec3 eyes = mc.thePlayer.getPositionEyes(1.0f);
        float[] r;
        if (box.isVecInside(eyes)) r = new float[]{ mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch };
        else {
            Vec3 p = aimPoint(eyes, box);
            r = fromDelta(p.xCoord - eyes.xCoord, p.yCoord - eyes.yCoord, p.zCoord - eyes.zCoord);
        }
        float[] last = lastSent();
        return fixSensitivity(r[0], r[1], last[0], last[1]);
    }

    private static Vec3 aimPoint(Vec3 eyes, AxisAlignedBB box) {
        double cx = (box.minX + box.maxX) / 2.0, cy = box.minY + (box.maxY - box.minY) * 0.825, cz = (box.minZ + box.maxZ) / 2.0;
        double d5 = 0.03;
        double px = MathHelper.clamp_double(eyes.xCoord, box.minX + d5, box.maxX - d5);
        double py = MathHelper.clamp_double(eyes.yCoord, box.minY + d5, box.maxY - d5);
        double pz = MathHelper.clamp_double(eyes.zCoord, box.minZ + d5, box.maxZ - d5);
        double t = AIM_CLOSEST_POINT / 100.0;
        return new Vec3(cx + (px - cx) * t, cy + (py - cy) * t, cz + (pz - cz) * t);
    }

    public static Vec3 vectorForRotation(float yaw, float pitch) {
        float f3 = MathHelper.cos((yaw *= -((float) Math.PI / 180)) - (float) Math.PI);
        float f4 = MathHelper.sin(yaw - (float) Math.PI);
        float f5 = -MathHelper.cos(pitch *= -((float) Math.PI / 180));
        float f6 = MathHelper.sin(pitch);
        return new Vec3(f4 * f5, f6, f3 * f5);
    }

    public static boolean canSee(Entity entity) {
        Vec3 eyes = mc.thePlayer.getPositionEyes(1.0f);
        double x = entity.posX, y = entity.posY, z = entity.posZ, h = entity.getEyeHeight() - 0.2;
        if (clearLine(eyes, new Vec3(x + 0.3, y + h, z)) || clearLine(eyes, new Vec3(x - 0.3, y + h, z))
            || clearLine(eyes, new Vec3(x, y + h, z + 0.3)) || clearLine(eyes, new Vec3(x, y + h, z - 0.3))) return true;
        for (double d = entity.getEyeHeight() + 0.2; d > 0.0; d -= 0.2) if (clearLine(eyes, new Vec3(x, y + d, z))) return true;
        return false;
    }

    // B4's WorldUtils.rayTrace: the game's block ray (every block that can be hit, from the first cell on)
    private static boolean clearLine(Vec3 from, Vec3 to) {
        MovingObjectPosition hit = mc.theWorld.rayTraceBlocks(from, to, false, false, false);
        return hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK;
    }

    // ------------------------------------------------------------------ BlockUtils

    public static boolean isInteractable(Block block) {
        if (block instanceof BlockContainer || block == Blocks.crafting_table || block == Blocks.anvil || block == Blocks.bed || block == Blocks.lever) return true;
        if (block instanceof BlockTrapDoor || block instanceof BlockFence || block instanceof BlockFenceGate) return true;
        return block instanceof BlockDoor && block != Blocks.iron_door;
    }

    // ------------------------------------------------------------------ drawing

    /** A full-screen quad in GUI coordinates with texture coordinates 0..1 (for the blur and bloom passes). */
    public static void screenQuad() {
        ScaledResolution sr = new ScaledResolution(mc);
        double w = sr.getScaledWidth(), h = sr.getScaledHeight();
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(0.0f, 0.0f);
        GL11.glVertex2d(0, h);
        GL11.glTexCoord2f(1.0f, 0.0f);
        GL11.glVertex2d(w, h);
        GL11.glTexCoord2f(1.0f, 1.0f);
        GL11.glVertex2d(w, 0);
        GL11.glTexCoord2f(0.0f, 1.0f);
        GL11.glVertex2d(0, 0);
        GL11.glEnd();
    }

    public static void drawItem(ItemStack stack, float x, float y) {
        if (stack == null || stack.getItem() == null) return;
        RenderHelper.enableGUIStandardItemLighting();
        RenderItem renderItem = mc.getRenderItem();
        renderItem.zLevel = -100.0f;
        IBakedModel model = renderItem.getItemModelMesher().getItemModel(stack);
        GlStateManager.pushMatrix();
        mc.getTextureManager().bindTexture(TextureMap.locationBlocksTexture);
        mc.getTextureManager().getTexture(TextureMap.locationBlocksTexture).setBlurMipmap(false, false);
        GlStateManager.enableRescaleNormal();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(770, 771);
        GlStateManager.color(1f, 1f, 1f, 1f);
        GlStateManager.translate(x, y, 100.0f + renderItem.zLevel);
        GlStateManager.translate(8.0f, 8.0f, 0.0f);
        GlStateManager.scale(1.0f, 1.0f, -1.0f);
        GlStateManager.scale(0.5f, 0.5f, 0.5f);
        if (model.isGui3d()) {
            GlStateManager.scale(40.0f, 40.0f, 40.0f);
            GlStateManager.rotate(210.0f, 1.0f, 0.0f, 0.0f);
            GlStateManager.rotate(-135.0f, 0.0f, 1.0f, 0.0f);
            GlStateManager.enableLighting();
        } else {
            GlStateManager.scale(64.0f, 64.0f, 64.0f);
            GlStateManager.rotate(180.0f, 1.0f, 0.0f, 0.0f);
            GlStateManager.disableLighting();
        }
        model = ForgeHooksClient.handleCameraTransforms(model, ItemCameraTransforms.TransformType.GUI);
        renderItem.renderItem(stack, model);
        GlStateManager.disableBlend();
        GlStateManager.disableRescaleNormal();
        GlStateManager.disableLighting();
        GlStateManager.popMatrix();
        mc.getTextureManager().bindTexture(TextureMap.locationBlocksTexture);
        mc.getTextureManager().getTexture(TextureMap.locationBlocksTexture).restoreLastBlurMipmap();
        renderItem.zLevel = 0.0f;
        RenderHelper.disableStandardItemLighting();
    }

    public static void blockBox(int x, int y, int z, int color, boolean outline, boolean shade) {
        RenderManager rm = mc.getRenderManager();
        double d = x - rm.viewerPosX, d2 = y - rm.viewerPosY, d3 = z - rm.viewerPosZ;
        GL11.glBlendFunc(770, 771);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(false);
        float a = (color >> 24 & 0xFF) / 255.0f, r = (color >> 16 & 0xFF) / 255.0f, g = (color >> 8 & 0xFF) / 255.0f, b = (color & 0xFF) / 255.0f;
        AxisAlignedBB bb = new AxisAlignedBB(d, d2, d3, d + 1, d2 + 1, d3 + 1);
        if (outline) {
            GlStateManager.color(r, g, b, a);
            GL11.glEnable(GL11.GL_LINE_SMOOTH);
            GL11.glLineWidth(2.0f);
            RenderGlobal.drawSelectionBoundingBox(bb);
            GL11.glDisable(GL11.GL_LINE_SMOOTH);
        }
        if (shade) filledBox(bb, r, g, b, 0.42f);
        GlStateManager.color(1f, 1f, 1f, 1f);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(true);
        GL11.glDisable(GL11.GL_BLEND);
    }

    private static void filledBox(AxisAlignedBB a, float r, float g, float b, float alpha) {
        Tessellator t = Tessellator.getInstance();
        WorldRenderer w = t.getWorldRenderer();
        w.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        double[][] v = {
            { a.minX, a.minY, a.minZ }, { a.minX, a.maxY, a.minZ }, { a.maxX, a.maxY, a.minZ }, { a.maxX, a.minY, a.minZ },
            { a.minX, a.minY, a.maxZ }, { a.maxX, a.minY, a.maxZ }, { a.maxX, a.maxY, a.maxZ }, { a.minX, a.maxY, a.maxZ },
            { a.minX, a.minY, a.minZ }, { a.minX, a.minY, a.maxZ }, { a.minX, a.maxY, a.maxZ }, { a.minX, a.maxY, a.minZ },
            { a.maxX, a.minY, a.minZ }, { a.maxX, a.maxY, a.minZ }, { a.maxX, a.maxY, a.maxZ }, { a.maxX, a.minY, a.maxZ },
            { a.minX, a.minY, a.minZ }, { a.maxX, a.minY, a.minZ }, { a.maxX, a.minY, a.maxZ }, { a.minX, a.minY, a.maxZ },
            { a.minX, a.maxY, a.minZ }, { a.minX, a.maxY, a.maxZ }, { a.maxX, a.maxY, a.maxZ }, { a.maxX, a.maxY, a.minZ } };
        for (double[] p : v) w.pos(p[0], p[1], p[2]).color(r, g, b, alpha).endVertex();
        t.draw();
    }

    // ------------------------------------------------------------------ Hypixel

    /** The sidebar's title and up to 15 lines, top to bottom, with colours (null without a sidebar). */
    public static List<String> sidebar() {
        if (mc.theWorld == null) return null;
        Scoreboard sb = mc.theWorld.getScoreboard();
        if (sb == null) return null;
        ScoreObjective obj = sb.getObjectiveInDisplaySlot(1);
        if (obj == null) return null;
        List<Score> scores = new ArrayList<>(sb.getSortedScores(obj));
        List<String> out = new ArrayList<>(10);
        out.add(obj.getDisplayName());
        int n = 0;
        for (int i = scores.size() - 1; i >= 0 && n < 15; --i) {
            Score s = scores.get(i);
            String name;
            if (s == null || (name = s.getPlayerName()) == null || name.startsWith("#")) continue;
            out.add(ScorePlayerTeam.formatPlayerName(sb.getPlayersTeam(name), name));
            ++n;
        }
        return out;
    }

    /** Where you are on Hypixel, as B4 names it (NOT_HYPIXEL, LOBBY, BEDWARS_GAME_BED, DUELS_GAME...). */
    public static String hypixelState() {
        if (mc.isSingleplayer() || mc.getCurrentServerData() == null) return "NOT_HYPIXEL";
        boolean host = mc.getCurrentServerData().serverIP.toLowerCase().endsWith("hypixel.net")
            || gale.util.ServerUtil.tabHeader().toLowerCase().contains("hypixel.net");
        if (!host) return "NOT_HYPIXEL";
        List<String> lines = sidebar();
        if (lines == null) return mc.theWorld.provider.getDimensionId() == 1 ? "LIMBO" : "UNKNOWN";
        boolean bedwars = false, skywars = false, skyPlayers = false, duels = false, murder = false;
        for (int i = 0; i < lines.size(); ++i) {
            String line = lines.get(i);
            if (i == 0) {
                String t = ChatUtil.strip(line);
                if (t.equals("REPLAY")) return "REPLAY";
                if (t.equals("BED WARS PRACTICE")) return "BRIDGE_PRACTICE";
                if (t.equals("THE HYPIXEL PIT")) return "PIT";
                if (t.equals("BED WARS")) bedwars = true;
                else if (t.equals("SKYWARS")) skywars = true;
                else if (t.equals("DUELS")) duels = true;
                else if (t.equals("MURDER MYSTERY")) murder = true;
                continue;
            }
            if (i == 1 && line.contains("  " + ChatUtil.SECTION + "8L")) return "LOBBY";
            if (bedwars) {
                if (i >= 5) {
                    if (i == 8 && line.startsWith("Mode")) return "BEDWARS_PREGAME";
                    if (line.endsWith(" YOU")) return line.indexOf('✓') != -1 ? "BEDWARS_GAME_BED" : "BEDWARS_GAME_NO_BED";
                }
            } else if (skywars) {
                if (i == 3) {
                    if (line.startsWith("Player")) return "SKYWARS_QUEUE";
                } else if (i == 6) {
                    if (line.startsWith("Player")) skyPlayers = true;
                } else if (i == 7 && skyPlayers) {
                    return !line.startsWith("Team") ? "SKYWARS_GAME_SOLO" : "SKYWARS_GAME_TEAM";
                }
            } else if (murder) {
                if (i == 3 && line.startsWith("Role")) return "MURDER_MYSTERY_GAME";
            } else if (duels) {
                if (i == 3) {
                    if (line.startsWith("Time")) return "DUELS_GAME";
                } else if (i == 6) {
                    if (line.startsWith("Waiting.") || line.startsWith("Starting ")) return "DUELS_QUEUE";
                }
            }
        }
        return "UNKNOWN";
    }

    // ------------------------------------------------------------------ Theme

    private static final String[] THEME_NAMES = { "Default", "Rainbow", "Aurora", "Cherry", "Cotton candy", "Flare", "Flower", "Forest", "Frost",
        "Gold", "Grayscale", "Inferno", "Royal", "Sandstorm", "Sky", "Vine" };
    private static final int[][] THEME_COLORS = {
        null, null, { 120, 0, 200, 0, 255, 180 }, { 255, 200, 200, 243, 58, 106 }, { 99, 249, 255, 255, 104, 204 }, { 241, 39, 17, 245, 175, 25 },
        { 215, 166, 231, 211, 90, 232 }, { 34, 139, 34, 154, 205, 50 }, { 253, 253, 253, 210, 225, 230 }, { 255, 250, 0, 255, 253, 195 },
        { 240, 240, 240, 110, 110, 110 }, { 255, 51, 0, 50, 0, 0 }, { 125, 204, 241, 30, 71, 170 }, { 194, 178, 128, 255, 236, 179 },
        { 140, 254, 250, 14, 210, 247 }, { 0, 255, 25, 180, 255, 180 } };

    /** A B4 theme's colour now ("Default" is Frost, B4's default), 0 for an unknown name. */
    public static int themeRGB(String name) {
        int i = -1;
        for (int k = 0; k < THEME_NAMES.length; k++) if (THEME_NAMES[k].equals(name)) i = k;
        if (i < 0) return 0;
        if (i == 0) i = 8;
        double mult = 0.5;
        if (i == 1) {
            long speed = (long) Math.max(2.0 * mult, 1.0);
            long l2 = System.currentTimeMillis();
            return Color.getHSBColor((float) (l2 % (15000L / speed)) / (15000.0f / (float) speed), 0.75f, 1.0f).getRGB();
        }
        int[] c = THEME_COLORS[i];
        double d3 = (double) System.currentTimeMillis() / 1.0E8 * mult * 400000.0;
        double d4 = (Math.sin(d3) + 1.0) * 0.5;
        int n = (int) (d4 * 255.0), n2 = 255 - n;
        int r = c[0] * n + c[3] * n2 >> 8, g = c[1] * n + c[4] * n2 >> 8, b = c[2] * n + c[5] * n2 >> 8;
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    public static IBlockState state(BlockPos pos) { return mc.theWorld.getBlockState(pos); }
}
