package gale.module.impl.render;

import gale.Gale;
import gale.event.impl.EntityJoinEvent;
import gale.event.impl.Render3DEvent;
import gale.event.impl.UpdateEvent;
import gale.module.Category;
import gale.module.Module;
import gale.module.impl.client.Targets;
import gale.module.setting.BoolSetting;
import gale.util.EntityUtil;
import net.minecraft.block.Block;
import net.minecraft.block.BlockAir;
import net.minecraft.block.BlockFire;
import net.minecraft.block.BlockLiquid;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.entity.item.EntityEnderPearl;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.projectile.EntityLargeFireball;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;

/**
 * Where things you throw or shoot will land, and where thrown pearls and fireballs are going: B4's Trajectories,
 * copied from OpenB4 (same settings, same maths, same drawing).
 * <ul>
 * <li>held bow / egg / snowball / ender pearl: the flight from your hand to what it hits, with a cross on the spot
 * (green when it lands on top of a block, red when it hits a living thing)</li>
 * <li>ender pearls in flight (yours and others'): their arc to where they land, the block outlined, and how long until
 * then</li>
 * <li>fireballs in flight: the straight line to what they hit, the block outlined, and how long until then</li>
 * <li>players holding a fire charge: where their fireball would go if they threw it now</li>
 * </ul>
 */
public final class Trajectories extends Module {
    private final BoolSetting autoScaleTarget = bool("Auto-scale target", true).describe("落点的叉离得越远画得越大");
    private final BoolSetting disableUnchargedBow = bool("Disable uncharged bow", true).describe("弓没拉开时不画");
    private final BoolSetting highlightOnEntity = bool("Highlight on entity", true).describe("会打中生物时线变红");
    private final BoolSetting drawBlockOutline = bool("Draw block outline", true).describe("框出落到的那个方块面");
    private final BoolSetting shortenLine = bool("Shorten line", false).describe("线不从手上开始画");
    private final BoolSetting bow = bool("Bow", true);
    private final BoolSetting egg = bool("Egg", true);
    private final BoolSetting enderPearl = bool("Ender pearl", true).describe("手里拿着珍珠时画会落到哪");
    private final BoolSetting enderPearlThrown = bool("Ender pearl (thrown)", true).describe("飞着的珍珠（谁扔的都算）：弧线、落点方块、还要几秒");
    private final BoolSetting fireballEnemy = bool("Fireball (enemy)", true).describe("别人拿着火焰弹时，扔出去会飞到哪");
    private final BoolSetting fireballThrown = bool("Fireball (thrown)", true).describe("飞着的火焰弹：直线、打到的方块、还要几秒");
    private final BoolSetting snowball = bool("Snowball", true);

    private static final int REACH = 75;
    private int held;
    private BlockPos hitBlock;
    private final List<Entity> thrown = new ArrayList<>();
    private final List<EntityLivingBase> aiming = new ArrayList<>();

    public Trajectories() {
        super("Trajectories", Category.RENDER, "抛物线落点：手里的弓、蛋、雪球、珍珠会落到哪，飞着的珍珠和火焰弹的路线和落点（B4 Trajectories 的移植）");
        fireballEnemy.onChange(v -> { if (!v) aiming.clear(); });
        on(UpdateEvent.class, e -> update());
        on(EntityJoinEvent.class, this::onJoin);
        on(Render3DEvent.class, e -> render(e.partialTicks));
    }

    @Override
    protected void onDisable() {
        held = 0;
        thrown.clear();
        aiming.clear();
    }

    private void update() {
        held = heldType();
        if (!thrown.isEmpty()) thrown.removeIf(e -> e == null || mc.theWorld.getEntityByID(e.getEntityId()) == null);
        if (fireballEnemy.get()) {
            aiming.clear();
            for (EntityPlayer p : mc.theWorld.playerEntities) {
                if (p == mc.thePlayer || p.deathTime != 0) continue;
                ItemStack s = p.getHeldItem();
                if (s != null && s.getItem() == Items.fire_charge && !isBot(p)) aiming.add(p);
            }
        }
    }

    // B4's bot check with its AntiBot: dead players, and with Targets' "Skip bots" the ones that are not real players
    private static boolean isBot(EntityPlayer p) {
        if (p.isDead) return true;
        Targets t = Gale.modules().get(Targets.class);
        return t != null && t.bots.get() && EntityUtil.isBot(p);
    }

    // 0 nothing, 1 thrown item, 2 bow
    private int heldType() {
        ItemStack s = mc.thePlayer.getHeldItem();
        if (s != null) {
            Item item = s.getItem();
            if (item != Items.bow) {
                if (item == Items.egg) return egg.get() ? 1 : 0;
                if (item == Items.ender_pearl) return enderPearl.get() ? 1 : 0;
                if (item == Items.snowball) return snowball.get() ? 1 : 0;
            } else if (bow.get()) {
                return disableUnchargedBow.get() && !mc.thePlayer.isUsingItem() ? 0 : 2;
            }
        }
        return 0;
    }

    private void onJoin(EntityJoinEvent e) {
        if (e.entity == mc.thePlayer) {
            thrown.clear();
            return;
        }
        if (e.entity instanceof EntityEnderPearl) {
            if (enderPearlThrown.get() && !thrown.contains(e.entity)) thrown.add(e.entity);
        } else if (e.entity instanceof EntityLargeFireball && fireballThrown.get() && !thrown.contains(e.entity)) {
            thrown.add(e.entity);
        }
    }

    private void timeLabel(BlockPos pos, double seconds, float pitch, float yaw, double cx, double cy, double cz) {
        String s = round1(seconds) + "s";
        double x = pos.getX() + 0.5 - cx, y = pos.getY() + 0.5 - cy, z = pos.getZ() + 0.5 - cz;
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, z);
        GlStateManager.rotate(-yaw, 0.0f, 1.0f, 0.0f);
        GlStateManager.rotate(pitch, 1.0f, 0.0f, 0.0f);
        double d = mc.thePlayer.getDistanceSq(pos);
        float sc = (float) Math.min(0.025 * (1.0 + d / 500.0), 0.1);
        GlStateManager.scale(-sc, -sc, sc);
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(770, 771);
        int w = mc.fontRendererObj.getStringWidth(s);
        mc.fontRendererObj.drawString(s, (float) (-w / 2), -4.0f, -1, true);
        GlStateManager.disableBlend();
        GlStateManager.disableTexture2D();
        GlStateManager.popMatrix();
    }

    // B4's Strings.round(seconds, 1), shown as a double ("1.5", "2.0")
    private static double round1(double d) { return (double) Math.round(d * 10.0) / 10.0; }

    // a thrown pearl's arc: its own motion, 0.99 drag and 0.03 gravity a tick, until it hits a block
    private void pearlPath(Entity e, double cx, double cy, double cz, float pitch, float yaw) {
        double mx = e.motionX;
        if (Double.isNaN(mx)) return;
        float r = 0.45f, g = 0.15f, b = 1.0f;
        GlStateManager.color(r, g, b, 0.8f);
        GL11.glLineWidth(2.0f);
        double my = e.motionY, mz = e.motionZ;
        Vec3 pos = new Vec3(e.posX, e.posY, e.posZ);
        Vec3 hit = null;
        BlockPos block = null;
        GL11.glBegin(GL11.GL_LINE_STRIP);
        GL11.glVertex3d(pos.xCoord - cx, pos.yCoord - cy, pos.zCoord - cz);
        int ticks = 0;
        for (int i = 0; i < 100; ++i) {
            ++ticks;
            Vec3 next = new Vec3(pos.xCoord + mx, pos.yCoord + my, pos.zCoord + mz);
            MovingObjectPosition mop = mc.theWorld.rayTraceBlocks(pos, next, false, true, false);
            if (mop != null && mop.hitVec != null) {
                next = hit = mop.hitVec;
                if (mop.getBlockPos() != null) block = mop.getBlockPos();
                GL11.glVertex3d(next.xCoord - cx, next.yCoord - cy, next.zCoord - cz);
                break;
            }
            GL11.glVertex3d(next.xCoord - cx, next.yCoord - cy, next.zCoord - cz);
            pos = next;
            mx *= 0.99;
            my *= 0.99;
            mz *= 0.99;
            my -= 0.03;
            if (pos.yCoord < 0.0) break;
        }
        GL11.glEnd();
        if (hit != null) {
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);
            if (block != null) {
                outline(block, cx, cy, cz, r, g, b, true);
                timeLabel(block, ticks / 20.0, pitch, yaw, cx, cy, cz);
            }
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(true);
        }
    }

    private void outline(BlockPos pos, double cx, double cy, double cz, float r, float g, float b, boolean fill) {
        AxisAlignedBB bb = new AxisAlignedBB(pos.getX() - cx, pos.getY() - cy, pos.getZ() - cz, pos.getX() + 1 - cx, pos.getY() + 1 - cy, pos.getZ() + 1 - cz);
        if (fill) {
            GlStateManager.color(r, g, b, 0.5f);
            GL11.glLineWidth(2.0f);
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glVertex3d(bb.minX, bb.minY, bb.minZ);
            GL11.glVertex3d(bb.maxX, bb.minY, bb.minZ);
            GL11.glVertex3d(bb.maxX, bb.maxY, bb.minZ);
            GL11.glVertex3d(bb.minX, bb.maxY, bb.minZ);
            GL11.glVertex3d(bb.minX, bb.minY, bb.maxZ);
            GL11.glVertex3d(bb.minX, bb.maxY, bb.maxZ);
            GL11.glVertex3d(bb.maxX, bb.maxY, bb.maxZ);
            GL11.glVertex3d(bb.maxX, bb.minY, bb.maxZ);
            GL11.glVertex3d(bb.minX, bb.minY, bb.minZ);
            GL11.glVertex3d(bb.minX, bb.minY, bb.maxZ);
            GL11.glVertex3d(bb.maxX, bb.minY, bb.maxZ);
            GL11.glVertex3d(bb.maxX, bb.minY, bb.minZ);
            GL11.glVertex3d(bb.minX, bb.maxY, bb.minZ);
            GL11.glVertex3d(bb.maxX, bb.maxY, bb.minZ);
            GL11.glVertex3d(bb.maxX, bb.maxY, bb.maxZ);
            GL11.glVertex3d(bb.minX, bb.maxY, bb.maxZ);
            GL11.glVertex3d(bb.minX, bb.minY, bb.minZ);
            GL11.glVertex3d(bb.minX, bb.maxY, bb.minZ);
            GL11.glVertex3d(bb.minX, bb.maxY, bb.maxZ);
            GL11.glVertex3d(bb.minX, bb.minY, bb.maxZ);
            GL11.glVertex3d(bb.maxX, bb.minY, bb.minZ);
            GL11.glVertex3d(bb.maxX, bb.minY, bb.maxZ);
            GL11.glVertex3d(bb.maxX, bb.maxY, bb.maxZ);
            GL11.glVertex3d(bb.maxX, bb.maxY, bb.minZ);
            GL11.glEnd();
            GlStateManager.color(r, g, b, 0.8f);
        }
        RenderGlobal.drawSelectionBoundingBox(bb);
    }

    // where a player holding a fire charge would send it: straight along their look, up to 75 blocks
    private void aimLine(EntityLivingBase e, double cx, double cy, double cz, float pt) {
        double x = e.lastTickPosX + (e.posX - e.lastTickPosX) * pt;
        double y = e.lastTickPosY + (e.posY - e.lastTickPosY) * pt + e.getEyeHeight();
        double z = e.lastTickPosZ + (e.posZ - e.lastTickPosZ) * pt;
        float yaw = e.prevRotationYaw + (e.rotationYaw - e.prevRotationYaw) * pt;
        float pitch = e.prevRotationPitch + (e.rotationPitch - e.prevRotationPitch) * pt;
        double ry = Math.toRadians(yaw), rp = Math.toRadians(pitch);
        double dx = -Math.sin(ry) * Math.cos(rp), dy = -Math.sin(rp), dz = Math.cos(ry) * Math.cos(rp);
        Vec3 from = new Vec3(x, y, z);
        Vec3 to = new Vec3(x + dx * REACH, y + dy * REACH, z + dz * REACH);
        hitBlock = null;
        Vec3 end = trace(e, from, to);
        float r = 1.0f, g = 0.45f, b = 0.0f;
        GlStateManager.color(r, g, b, 0.8f);
        GL11.glLineWidth(2.0f);
        GL11.glBegin(GL11.GL_LINES);
        GL11.glVertex3d(from.xCoord - cx, from.yCoord - cy, from.zCoord - cz);
        GL11.glVertex3d(end.xCoord - cx, end.yCoord - cy, end.zCoord - cz);
        GL11.glEnd();
        if (hitBlock != null) {
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);
            outline(hitBlock, cx, cy, cz, r, g, b, false);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(true);
        }
    }

    // the first block or entity on the straight line from..to (entities: the block cell just behind their hitbox)
    private Vec3 trace(Entity entity, Vec3 from, Vec3 to) {
        Vec3 end;
        double best;
        BlockPos start = new BlockPos(MathHelper.floor_double(from.xCoord), MathHelper.floor_double(from.yCoord), MathHelper.floor_double(from.zCoord));
        Block block = mc.theWorld.getBlockState(start).getBlock();
        if (!(block instanceof BlockAir || block instanceof BlockLiquid || block instanceof BlockFire)) {
            hitBlock = start;
            return from;
        }
        MovingObjectPosition mop = mc.theWorld.rayTraceBlocks(from, to, false, true, false);
        if (mop != null && mop.hitVec != null) {
            end = mop.hitVec;
            hitBlock = mop.getBlockPos();
            best = from.squareDistanceTo(end);
        } else {
            end = to;
            best = from.squareDistanceTo(to);
        }
        double dx = to.xCoord - from.xCoord, dy = to.yCoord - from.yCoord, dz = to.zCoord - from.zCoord;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len != 0.0) {
            dx /= len;
            dy /= len;
            dz /= len;
        }
        AxisAlignedBB area = entity.getEntityBoundingBox().addCoord(to.xCoord - from.xCoord, to.yCoord - from.yCoord, to.zCoord - from.zCoord).expand(1.0, 1.0, 1.0);
        for (Entity other : mc.theWorld.getEntitiesWithinAABBExcludingEntity(entity, area)) {
            double d;
            if (!other.canBeCollidedWith()) continue;
            float border = Math.max(other.getCollisionBorderSize(), 0.3f);
            AxisAlignedBB bb = other.getEntityBoundingBox().expand(border, border, border);
            if (bb.isVecInside(from)) {
                if (!(best > 0.0)) continue;
                hitBlock = start;
                end = from;
                best = from.squareDistanceTo(end);
                continue;
            }
            MovingObjectPosition hit = bb.calculateIntercept(from, to);
            if (hit == null || hit.hitVec == null || !((d = from.squareDistanceTo(hit.hitVec)) < best)) continue;
            double step = border + 0.001;
            hitBlock = new BlockPos(MathHelper.floor_double(hit.hitVec.xCoord + dx * step), MathHelper.floor_double(hit.hitVec.yCoord + dy * step),
                MathHelper.floor_double(hit.hitVec.zCoord + dz * step));
            AxisAlignedBB cell = new AxisAlignedBB(hitBlock.getX(), hitBlock.getY(), hitBlock.getZ(), hitBlock.getX() + 1, hitBlock.getY() + 1, hitBlock.getZ() + 1);
            MovingObjectPosition cellHit = cell.calculateIntercept(from, to);
            end = cellHit != null && cellHit.hitVec != null ? cellHit.hitVec : hit.hitVec;
            best = d;
        }
        return end;
    }

    private void render(float partialTicks) {
        if (held != 0) drawHeld(partialTicks);
        if (!thrown.isEmpty() || !aiming.isEmpty()) {
            RenderManager rm = mc.getRenderManager();
            double cx = rm.viewerPosX, cy = rm.viewerPosY, cz = rm.viewerPosZ;
            GL11.glPushMatrix();
            GlStateManager.disableTexture2D();
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(770, 771);
            GL11.glEnable(GL11.GL_LINE_SMOOTH);
            for (EntityLivingBase en : aiming) aimLine(en, cx, cy, cz, partialTicks);
            for (Entity en : thrown) {
                if (en instanceof EntityEnderPearl) {
                    pearlPath(en, cx, cy, cz, rm.playerViewX, rm.playerViewY);
                    continue;
                }
                if (en instanceof EntityLargeFireball) fireballPath(en, cx, cy, cz, rm.playerViewX, rm.playerViewY);
            }
            GL11.glDisable(GL11.GL_LINE_SMOOTH);
            GL11.glDisable(GL11.GL_BLEND);
            GlStateManager.enableTexture2D();
            GL11.glPopMatrix();
            GlStateManager.color(1f, 1f, 1f, 1f);
        }
    }

    // what you hold: launched from your hand at 3 * charge (bow, 0.05 gravity) or 1.5 (thrown, 0.03), in half-tick steps
    private void drawHeld(float partialTicks) {
        float yaw = (float) Math.toRadians(mc.thePlayer.rotationYaw);
        float pitch = (float) Math.toRadians(mc.thePlayer.rotationPitch);
        RenderManager rm = mc.getRenderManager();
        double x = rm.viewerPosX - MathHelper.cos(yaw) * 0.16f;
        double y = rm.viewerPosY + mc.thePlayer.getEyeHeight() - 0.1;
        double z = rm.viewerPosZ - MathHelper.sin(yaw) * 0.16f;
        boolean isBow = held == 2;
        float power = isBow ? 1.0f : 0.4f;
        float mx = -MathHelper.sin(yaw) * MathHelper.cos(pitch) * power;
        float my = -MathHelper.sin(pitch) * power;
        float mz = MathHelper.cos(yaw) * MathHelper.cos(pitch) * power;
        double len = Math.sqrt(mx * mx + my * my + mz * mz);
        mx = (float) (mx / len);
        my = (float) (my / len);
        mz = (float) (mz / len);
        if (isBow) {
            float use = (float) (72000 - mc.thePlayer.getItemInUseCount()) + partialTicks;
            float f = use / 20.0f;
            if ((f = (f * f + f * 2.0f) / 3.0f) > 1.0f) f = 1.0f;
            mx *= (f *= 3.0f);
            my *= f;
            mz *= f;
        } else {
            mx = (float) (mx * 1.5);
            my = (float) (my * 1.5);
            mz = (float) (mz * 1.5);
        }
        GL11.glPushMatrix();
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glBlendFunc(770, 771);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(0x809D); // GL_MULTISAMPLE
        GL11.glDepthMask(false);
        double gravity = isBow ? 0.05 : 0.03;
        List<double[]> points = new ArrayList<>();
        MovingObjectPosition blockHit = null;
        Entity entityHit = null;
        EnumFacing entityFace = null, blockFace = null;
        for (int i = 0; i < 100; ++i) {
            for (int n2 = 0; n2 < 2; ++n2) {
                points.add(new double[]{ x - rm.viewerPosX, y - rm.viewerPosY, z - rm.viewerPosZ });
                Vec3 from = new Vec3(x, y, z);
                Vec3 to = new Vec3(x + mx / 2f, y + my / 2f, z + mz / 2f);
                double sx = mx / 2f, sy = my / 2f, sz = mz / 2f;
                x = to.xCoord;
                y = to.yCoord;
                z = to.zCoord;
                double drag = Math.pow(0.99, 1.0 / 2);
                mx = (float) (mx * drag);
                my = (float) (my * drag);
                mz = (float) (mz * drag);
                my = (float) (my - gravity / 2);
                double h = 0.5;
                AxisAlignedBB area = new AxisAlignedBB(x - h, y - h, z - h, x + h, y + h, z + h).addCoord(sx, sy, sz).expand(1.0, 1.0, 1.0);
                List<Entity> list = mc.theWorld.getEntitiesWithinAABBExcludingEntity(mc.getRenderViewEntity(), area);
                double best = 0.0;
                for (Entity other : list) {
                    if (!(other instanceof EntityLivingBase) || other instanceof EntityArmorStand || !other.canBeCollidedWith() || ((EntityLivingBase) other).deathTime != 0) continue;
                    if (other instanceof EntityPlayer && isBot((EntityPlayer) other)) continue;
                    AxisAlignedBB bb = other.getEntityBoundingBox().expand(0.3f, 0.3f, 0.3f);
                    MovingObjectPosition hit = bb.calculateIntercept(from, to);
                    if (hit == null) continue;
                    double d = from.squareDistanceTo(hit.hitVec);
                    if (entityHit != null && !(d < best)) continue;
                    entityHit = other;
                    entityFace = hit.sideHit;
                    best = d;
                }
                blockHit = mc.theWorld.rayTraceBlocks(from, to);
                if (blockHit != null) blockFace = blockHit.sideHit;
                if (entityHit != null || blockHit != null) break;
            }
            if (entityHit != null || blockHit != null) break;
        }
        if (entityHit != null && blockHit != null) {
            if (mc.thePlayer.getDistanceSqToEntity(entityHit) >= mc.thePlayer.getDistanceSqToCenter(blockHit.getBlockPos())) {
                entityHit = null;
                entityFace = null;
            } else {
                blockHit = null;
                blockFace = null;
            }
        }
        EnumFacing face = entityFace == null ? blockFace : entityFace;
        if (entityHit != null && highlightOnEntity.get()) color(-55770);
        else if (face == EnumFacing.UP) color(-14221530);
        else GlStateManager.color(1.0f, 1.0f, 1.0f);
        GL11.glLineWidth(1.8f);
        GL11.glBegin(GL11.GL_LINE_STRIP);
        for (int i = 0; i < points.size(); ++i) {
            if (i == 0 && shortenLine.get()) continue;
            double[] p = points.get(i);
            GL11.glVertex3d(p[0], p[1], p[2]);
        }
        double ex = x - rm.viewerPosX, ey = y - rm.viewerPosY, ez = z - rm.viewerPosZ;
        double dist = 0.0;
        if (entityHit != null) dist = mc.thePlayer.getDistanceSq(entityHit.getPosition());
        else if (blockHit != null) dist = mc.thePlayer.getDistanceSq(blockHit.getBlockPos());
        if (face != null) {
            double s = autoScaleTarget.get() ? Math.min(0.1 * (1.0 + dist / 500.0), 0.5) : 0.1;
            switch (face) {
                case WEST:
                case EAST:
                    GL11.glVertex3d(ex, ey, ez);
                    GL11.glVertex3d(ex, ey - s, ez - s);
                    GL11.glVertex3d(ex, ey + s, ez + s);
                    GL11.glVertex3d(ex, ey, ez);
                    GL11.glVertex3d(ex, ey - s, ez + s);
                    GL11.glVertex3d(ex, ey + s, ez - s);
                    break;
                case NORTH:
                case SOUTH:
                    GL11.glVertex3d(ex, ey, ez);
                    GL11.glVertex3d(ex - s, ey - s, ez);
                    GL11.glVertex3d(ex + s, ey + s, ez);
                    GL11.glVertex3d(ex, ey, ez);
                    GL11.glVertex3d(ex + s, ey - s, ez);
                    GL11.glVertex3d(ex - s, ey + s, ez);
                    break;
                default:
                    GL11.glVertex3d(ex, ey, ez);
                    GL11.glVertex3d(ex - s, ey, ez - s);
                    GL11.glVertex3d(ex + s, ey, ez + s);
                    GL11.glVertex3d(ex, ey, ez);
                    GL11.glVertex3d(ex + s, ey, ez - s);
                    GL11.glVertex3d(ex - s, ey, ez + s);
            }
        }
        GlStateManager.resetColor();
        GL11.glEnd();
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(0x809D);
        GL11.glDepthMask(true);
        GL11.glDisable(GL11.GL_LINE_SMOOTH);
        GL11.glPopMatrix();
        if (blockHit != null && face != null && drawBlockOutline.get()) faceOutline(blockHit.getBlockPos(), face, face == EnumFacing.UP ? -14221530 : -1);
    }

    // B4's Render3D.face(pos, face, color, outline, no shade): the outline of one face of the block
    private void faceOutline(BlockPos pos, EnumFacing facing, int color) {
        float f = 0f, f2 = 0f, f3 = 0f, f4 = 1f, f5 = 1f, f6 = 1f;
        switch (facing) {
            case DOWN: f5 = 0f; break;
            case UP: f2 = 1f; break;
            case NORTH: f6 = 0f; break;
            case SOUTH: f3 = 1f; break;
            case WEST: f4 = 0f; break;
            default: f = 1f;
        }
        RenderManager rm = mc.getRenderManager();
        double d = pos.getX() - rm.viewerPosX, d2 = pos.getY() - rm.viewerPosY, d3 = pos.getZ() - rm.viewerPosZ;
        GL11.glBlendFunc(770, 771);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(false);
        AxisAlignedBB bb = new AxisAlignedBB(d + f, d2 + f2, d3 + f3, d + f4, d2 + f5, d3 + f6);
        color(color);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glLineWidth(2.0f);
        RenderGlobal.drawSelectionBoundingBox(bb);
        GL11.glDisable(GL11.GL_LINE_SMOOTH);
        GlStateManager.resetColor();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(true);
        GL11.glDisable(GL11.GL_BLEND);
    }

    // a fireball in flight: straight along its motion (they do not fall), up to 75 blocks
    private void fireballPath(Entity e, double cx, double cy, double cz, float pitch, float yaw) {
        float r = 1.0f, g = 0.1f, b = 0.1f;
        GlStateManager.color(r, g, b, 0.8f);
        double dx = e.posX - e.lastTickPosX, dy = e.posY - e.lastTickPosY, dz = e.posZ - e.lastTickPosZ;
        if (dx == 0.0 && dy == 0.0 && dz == 0.0) {
            dx = e.motionX;
            dy = e.motionY;
            dz = e.motionZ;
        }
        double speed = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (speed == 0.0 || Double.isNaN(speed)) return;
        dx /= speed;
        dy /= speed;
        dz /= speed;
        Vec3 from = new Vec3(e.posX, e.posY, e.posZ);
        Vec3 to = new Vec3(e.posX + dx * REACH, e.posY + dy * REACH, e.posZ + dz * REACH);
        hitBlock = null;
        Vec3 end = trace(e, from, to);
        GL11.glLineWidth(3.0f);
        GL11.glBegin(GL11.GL_LINES);
        GL11.glVertex3d(from.xCoord - cx, from.yCoord - cy, from.zCoord - cz);
        GL11.glVertex3d(end.xCoord - cx, end.yCoord - cy, end.zCoord - cz);
        GL11.glEnd();
        if (hitBlock == null) return;
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(false);
        outline(hitBlock, cx, cy, cz, r, g, b, true);
        double seconds = from.distanceTo(end) / speed / 20.0;
        timeLabel(hitBlock, seconds, pitch, yaw, cx, cy, cz);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(true);
    }

    private static void color(int n) {
        GlStateManager.color((n >> 16 & 0xFF) / 255f, (n >> 8 & 0xFF) / 255f, (n & 0xFF) / 255f, (n >> 24 & 0xFF) / 255f);
    }

    // ------------------------------------------------------------------ for the self test

    /** Pearls and fireballs in flight it draws a path for. */
    public int tracked() { return thrown.size(); }

    /** Players holding a fire charge it draws an aim line for. */
    public int aimingPlayers() { return aiming.size(); }

    /** Where a fireball's straight line ends (the block it would hit), or null. */
    public BlockPos fireballTarget(Entity e) {
        double dx = e.motionX, dy = e.motionY, dz = e.motionZ, speed = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (speed == 0) return null;
        hitBlock = null;
        trace(e, new Vec3(e.posX, e.posY, e.posZ), new Vec3(e.posX + dx / speed * REACH, e.posY + dy / speed * REACH, e.posZ + dz / speed * REACH));
        return hitBlock;
    }
}
