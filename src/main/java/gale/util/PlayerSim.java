package gale.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.potion.Potion;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Predicts your movement tick by tick with vanilla 1.8.9 rules against the real world's blocks: sprinting, jumping,
 * ground and air friction, sneaking at edges, step-up, collisions, fall distance. Not handled: water, lava, ladders,
 * webs, soul sand and knockback that has not arrived yet.
 *
 * <pre>
 * PlayerSim sim = PlayerSim.of(player());           // now, with the keys you hold
 * sim.input(1, 0, false, false).tick();             // one tick walking forward
 * if (!sim.onGround &amp;&amp; player().onGround) ...       // you would walk off an edge
 * </pre>
 */
public final class PlayerSim {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public double x, y, z, motionX, motionY, motionZ;
    /** the yaw movement and jumps go toward */
    public float yaw;
    public boolean onGround, sprinting, collidedHorizontally, collidedVertically;
    public float fallDistance;
    public int jumpTicks;
    /** keys for the next ticks, as the game uses them (forward/strafe already times 0.3 when sneaking) */
    public float forward, strafe;
    public boolean jump, sneak, sprintKey;
    public int ticks;

    private float width = 0.6f, height = 1.8f;
    private double walkSpeed = 0.1;
    private int jumpBoost;
    private EntityPlayerSP owner;

    private PlayerSim() { }

    /** Starts from where you are now, with your motion and the movement keys of this tick. */
    public static PlayerSim of(EntityPlayerSP p) {
        PlayerSim s = new PlayerSim();
        s.owner = p;
        s.x = p.posX;
        s.y = p.posY;
        s.z = p.posZ;
        s.motionX = p.motionX;
        s.motionY = p.motionY;
        s.motionZ = p.motionZ;
        s.yaw = p.rotationYaw;
        s.onGround = p.onGround;
        s.sprinting = p.isSprinting();
        s.collidedHorizontally = p.isCollidedHorizontally;
        s.fallDistance = p.fallDistance;
        s.jumpTicks = PlayerUtil.jumpTicks();
        s.width = p.width;
        s.height = p.height;
        s.forward = p.movementInput.moveForward;
        s.strafe = p.movementInput.moveStrafe;
        s.jump = p.movementInput.jump;
        s.sneak = p.movementInput.sneak;
        s.sprintKey = mc.gameSettings.keyBindSprint.isKeyDown() || p.isSprinting();
        double speed = p.getEntityAttribute(SharedMonsterAttributes.movementSpeed).getAttributeValue();
        s.walkSpeed = p.isSprinting() ? speed / 1.3 : speed;
        s.jumpBoost = p.isPotionActive(Potion.jump) ? p.getActivePotionEffect(Potion.jump).getAmplifier() + 1 : 0;
        return s;
    }

    public PlayerSim copy() {
        PlayerSim s = new PlayerSim();
        s.x = x; s.y = y; s.z = z; s.motionX = motionX; s.motionY = motionY; s.motionZ = motionZ; s.yaw = yaw;
        s.onGround = onGround; s.sprinting = sprinting; s.collidedHorizontally = collidedHorizontally; s.collidedVertically = collidedVertically;
        s.fallDistance = fallDistance; s.jumpTicks = jumpTicks; s.forward = forward; s.strafe = strafe; s.jump = jump; s.sneak = sneak;
        s.sprintKey = sprintKey; s.ticks = ticks; s.width = width; s.height = height; s.walkSpeed = walkSpeed; s.jumpBoost = jumpBoost; s.owner = owner;
        return s;
    }

    /** Keys as pressed: forward/strafe -1..1; sneaking slows them to 0.3 as in the game. */
    public PlayerSim input(float forward, float strafe, boolean jump, boolean sneak) {
        float f = sneak ? 0.3f : 1f;
        this.forward = forward * f;
        this.strafe = strafe * f;
        this.jump = jump;
        this.sneak = sneak;
        return this;
    }

    public Vec3 pos() { return new Vec3(x, y, z); }

    public AxisAlignedBB box() { return new AxisAlignedBB(x - width / 2, y, z - width / 2, x + width / 2, y + height, z + width / 2); }

    /** Runs n ticks. */
    public PlayerSim ticks(int n) {
        for (int i = 0; i < n; i++) tick();
        return this;
    }

    /** Positions after each of the next n ticks (this sim is not changed). */
    public List<Vec3> path(int n) {
        PlayerSim s = copy();
        List<Vec3> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(s.tick().pos());
        return out;
    }

    /** Ticks until you are on the ground again, within max ticks; -1 if not. */
    public int ticksToLand(int max) {
        PlayerSim s = copy();
        boolean left = !s.onGround;
        for (int i = 1; i <= max; i++) {
            s.tick();
            if (!s.onGround) left = true;
            else if (left) return i;
        }
        return -1;
    }

    /** One tick of EntityPlayerSP.onLivingUpdate after the keys are read. */
    public PlayerSim tick() {
        ticks++;
        float threshold = 0.8f;
        if (!sprinting && forward >= threshold && sprintKey) sprinting = true;
        if (sprinting && (forward < threshold || collidedHorizontally)) sprinting = false;
        if (jumpTicks > 0) jumpTicks--;
        if (Math.abs(motionX) < 0.005) motionX = 0;
        if (Math.abs(motionY) < 0.005) motionY = 0;
        if (Math.abs(motionZ) < 0.005) motionZ = 0;
        if (jump) {
            if (onGround && jumpTicks == 0) {
                motionY = 0.42f + jumpBoost * 0.1f;
                if (sprinting) {
                    float r = yaw * 0.017453292f;
                    motionX -= MathHelper.sin(r) * 0.2f;
                    motionZ += MathHelper.cos(r) * 0.2f;
                }
                jumpTicks = 10;
            }
        } else {
            jumpTicks = 0;
        }
        moveWithHeading(strafe * 0.98f, forward * 0.98f);
        return this;
    }

    private void moveWithHeading(float strafe, float forward) {
        float friction = 0.91f;
        if (onGround) friction = mc.theWorld.getBlockState(new BlockPos(MathHelper.floor_double(x), MathHelper.floor_double(y) - 1, MathHelper.floor_double(z))).getBlock().slipperiness * 0.91f;
        float speed = onGround ? (float) (walkSpeed * (sprinting ? 1.3 : 1.0)) * (0.16277136f / (friction * friction * friction)) : sprinting ? 0.026f : 0.02f;
        float f = strafe * strafe + forward * forward;
        if (f >= 1.0E-4f) {
            f = MathHelper.sqrt_float(f);
            if (f < 1.0f) f = 1.0f;
            f = speed / f;
            strafe *= f;
            forward *= f;
            float sin = MathHelper.sin(yaw * (float) Math.PI / 180.0f), cos = MathHelper.cos(yaw * (float) Math.PI / 180.0f);
            motionX += strafe * cos - forward * sin;
            motionZ += forward * cos + strafe * sin;
        }
        move(motionX, motionY, motionZ);
        motionY -= 0.08;
        motionY *= 0.98f;
        motionX *= friction;
        motionZ *= friction;
    }

    private List<AxisAlignedBB> boxes(AxisAlignedBB area) { return mc.theWorld.getCollidingBoundingBoxes(owner, area); }

    /** Entity.moveEntity: sneaking at edges, collisions in y, x, z order, 0.6 step-up, ground and fall distance. */
    private void move(double mx, double my, double mz) {
        AxisAlignedBB bb = box();
        double ox = mx, oy = my, oz = mz;
        if (onGround && sneak) {
            double edge = 0.05;
            for (; mx != 0 && boxes(bb.offset(mx, -1, 0)).isEmpty(); ox = mx) {
                if (mx < edge && mx >= -edge) mx = 0; else if (mx > 0) mx -= edge; else mx += edge;
            }
            for (; mz != 0 && boxes(bb.offset(0, -1, mz)).isEmpty(); oz = mz) {
                if (mz < edge && mz >= -edge) mz = 0; else if (mz > 0) mz -= edge; else mz += edge;
            }
            for (; mx != 0 && mz != 0 && boxes(bb.offset(mx, -1, mz)).isEmpty(); oz = mz) {
                if (mx < edge && mx >= -edge) mx = 0; else if (mx > 0) mx -= edge; else mx += edge;
                ox = mx;
                if (mz < edge && mz >= -edge) mz = 0; else if (mz > 0) mz -= edge; else mz += edge;
            }
        }
        List<AxisAlignedBB> list = boxes(bb.addCoord(mx, my, mz));
        AxisAlignedBB start = bb;
        for (AxisAlignedBB c : list) my = c.calculateYOffset(bb, my);
        bb = bb.offset(0, my, 0);
        boolean canStep = onGround || oy != my && oy < 0;
        for (AxisAlignedBB c : list) mx = c.calculateXOffset(bb, mx);
        bb = bb.offset(mx, 0, 0);
        for (AxisAlignedBB c : list) mz = c.calculateZOffset(bb, mz);
        bb = bb.offset(0, 0, mz);
        if (canStep && (ox != mx || oz != mz)) {
            double nx = mx, ny = my, nz = mz;
            AxisAlignedBB noStep = bb;
            bb = start;
            my = 0.6f;
            List<AxisAlignedBB> step = boxes(bb.addCoord(ox, my, oz));
            AxisAlignedBB a = bb, sweep = a.addCoord(ox, 0, oz);
            double ay = my;
            for (AxisAlignedBB c : step) ay = c.calculateYOffset(sweep, ay);
            a = a.offset(0, ay, 0);
            double ax = ox;
            for (AxisAlignedBB c : step) ax = c.calculateXOffset(a, ax);
            a = a.offset(ax, 0, 0);
            double az = oz;
            for (AxisAlignedBB c : step) az = c.calculateZOffset(a, az);
            a = a.offset(0, 0, az);
            AxisAlignedBB b = bb;
            double by = my;
            for (AxisAlignedBB c : step) by = c.calculateYOffset(b, by);
            b = b.offset(0, by, 0);
            double bx = ox;
            for (AxisAlignedBB c : step) bx = c.calculateXOffset(b, bx);
            b = b.offset(bx, 0, 0);
            double bz = oz;
            for (AxisAlignedBB c : step) bz = c.calculateZOffset(b, bz);
            b = b.offset(0, 0, bz);
            if (ax * ax + az * az > bx * bx + bz * bz) { mx = ax; mz = az; my = -ay; bb = a; }
            else { mx = bx; mz = bz; my = -by; bb = b; }
            for (AxisAlignedBB c : step) my = c.calculateYOffset(bb, my);
            bb = bb.offset(0, my, 0);
            if (nx * nx + nz * nz >= mx * mx + mz * mz) { mx = nx; my = ny; mz = nz; bb = noStep; }
        }
        x = (bb.minX + bb.maxX) / 2.0;
        y = bb.minY;
        z = (bb.minZ + bb.maxZ) / 2.0;
        collidedHorizontally = ox != mx || oz != mz;
        collidedVertically = oy != my;
        onGround = collidedVertically && oy < 0;
        if (onGround) fallDistance = 0;
        else if (my < 0) fallDistance -= my;
        if (ox != mx) motionX = 0;
        if (oz != mz) motionZ = 0;
        if (collidedVertically) motionY = 0;
    }
}
