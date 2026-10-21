package gale.module.impl.world;

import gale.bedwars.BedTracker;
import gale.core.RotationManager;
import gale.core.ServerState;
import gale.event.impl.Render3DEvent;
import gale.event.impl.UpdateEvent;
import gale.module.Category;
import gale.module.Module;
import gale.module.setting.BoolSetting;
import gale.module.setting.ModeSetting;
import gale.module.setting.NumberSetting;
import gale.util.ChatUtil;
import gale.util.KeyUtil;
import gale.util.PlayerUtil;
import gale.util.RaycastUtil;
import gale.util.Render3D;
import net.minecraft.block.Block;
import net.minecraft.block.BlockBed;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Walls your bed in with a preset defense pattern (the BedDefender script, ported from OpenOnyx through B4). It finds
 * your bed (BedTracker's own bed with "Bedwars only", else the nearest bed within 16 blocks), works out which spots of
 * the pattern are still open and which it can place now (the block in the hotbar, something to click on, nobody
 * standing there, within 6 blocks). Spots standing in front of other open spots are left for last. For the chosen
 * spot it turns a silent rotation toward a face of a neighbouring block, at most "Aim speed" degrees a tick, and
 * right-clicks once the rotation the server already has hits that face. It switches itself off when the pattern is
 * done or nothing is left to place.
 */
public final class AutoDef extends Module {
    private static final String[] DEFENSES = { "EndWoolCorners", "EndWoolCorners2", "EndGlassNoCorners", "WoodWoolCorners", "EndGlassWool",
        "Wool1Layer", "EndWoodLongCorners", "EndWoolLong" };
    // pattern steps per defense as "<block><x>,<y>,<z>" separated by ';' (e end_stone, w wool, g stained_glass, p planks),
    // relative to the bed half next to you with the other half at +z
    private static final String[] DEFENSE_DATA = {
        // EndWoolCorners (30 blocks)
        "e0,0,2;w0,0,3;w0,0,-2;e0,0,-1;w1,0,-1;w1,1,-1;w0,1,-1;w-1,0,-1;w-1,1,-1;e-2,0,0;e-2,0,1;e-1,0,0;e-1,0,1;w-1,1,0;w-1,1,1;w-1,0,2;e2,0,0;e2,0,1;e1,0,0;e1,0,1;w1,1,0;w1,1,1;w1,0,2;e0,1,0;e0,1,1;w0,2,0;w0,2,1;w-1,1,2;w1,1,2;w0,1,2",
        // EndWoolCorners2 (30 blocks)
        "e-2,0,0;e-2,0,1;e-1,0,0;e-1,0,1;e0,0,2;e1,0,0;e1,0,1;e2,0,0;e2,0,1;e0,1,1;e0,1,0;e0,0,-1;w1,0,-1;w0,1,-1;w0,0,-2;w-1,0,-1;w1,1,1;w1,1,0;w1,1,-1;w0,2,1;w0,2,0;w-1,1,1;w-1,1,0;w-1,1,-1;w-1,0,2;w-1,1,2;w0,1,2;w1,1,2;w1,0,2;w0,0,3",
        // EndGlassNoCorners (26 blocks)
        "e0,0,2;g0,0,3;g-1,0,-1;g0,0,-2;g1,0,-1;e0,0,-1;e-2,0,0;e-2,0,1;e-1,0,0;e-1,0,1;e2,0,0;e2,0,1;e1,0,0;e1,0,1;g-1,1,0;g-1,1,1;g-1,0,2;g0,1,-1;g1,1,0;g1,1,1;g1,0,2;e0,1,0;e0,1,1;g0,2,0;g0,2,1;g0,1,2",
        // WoodWoolCorners (30 blocks)
        "p-1,0,-1;p-1,0,0;p-1,0,1;p-1,0,2;p1,0,-1;p1,0,0;p1,0,1;p1,0,2;p0,0,2;p0,0,3;p0,0,-2;p0,0,-1;p0,1,-1;p0,1,0;p0,1,1;p0,1,2;w-2,0,0;w-2,0,1;w-1,1,-1;w-1,1,0;w-1,1,1;w-1,1,2;w0,2,0;w0,2,1;w1,1,-1;w1,1,0;w1,1,1;w1,1,2;w2,0,0;w2,0,1",
        // EndGlassWool (58 blocks)
        "e-2,0,0;e-2,0,1;e-1,0,0;e-1,0,1;e0,0,2;e1,0,0;e1,0,1;e2,0,0;e2,0,1;e0,1,1;e0,1,0;e0,0,-1;g1,0,-1;g0,1,-1;g0,0,-2;g-1,0,-1;g1,1,1;g1,1,0;g0,2,1;g0,2,0;g-1,1,1;g-1,1,0;g-1,0,2;g0,1,2;g1,0,2;g0,0,3;w-3,0,0;w-3,0,1;w-2,0,2;w-2,1,0;w-2,1,1;w-1,1,2;w-1,0,3;w0,1,3;w0,0,4;w1,0,3;w1,1,2;w2,1,0;w2,1,1;w2,0,2;w3,0,0;w3,0,1;w1,2,0;w1,2,1;w0,2,2;w-1,2,0;w-1,2,1;w-2,0,-1;w-1,0,-2;w0,0,-3;w1,0,-2;w2,0,-1;w1,1,-1;w0,1,-2;w-1,1,-1;w0,2,-1;w0,3,0;w0,3,1",
        // Wool1Layer (8 blocks)
        "w-1,0,0;w-1,0,1;w0,0,2;w1,0,0;w1,0,1;w0,1,1;w0,1,0;w0,0,-1",
        // EndWoodLongCorners (28 blocks)
        "e0,0,2;e-1,0,-1;e-1,0,0;e-1,0,1;e0,0,-2;e0,0,-1;e1,0,-1;e1,0,0;e1,0,1;e0,1,-1;e0,1,0;e0,1,1;p-2,0,0;p-2,0,1;p-1,1,-1;p-1,1,0;p-1,1,1;p0,2,0;p0,2,1;p1,1,-1;p1,1,0;p1,1,1;p2,0,0;p2,0,1;p1,0,2;p0,1,2;p0,0,3;p-1,0,2",
        // EndWoolLong (35 blocks)
        "e0,0,2;e-1,0,-1;e-1,0,0;e-1,0,1;e0,0,-2;e0,0,-1;e1,0,-1;e1,0,0;e1,0,1;e0,1,-1;e0,1,0;e0,1,1;w-2,0,-1;w-2,0,0;w-2,0,1;w-1,1,-1;w-1,1,0;w-1,1,1;w0,2,-1;w0,2,0;w0,2,1;w1,1,-1;w1,1,0;w1,1,1;w2,0,-1;w2,0,0;w2,0,1;w-1,0,2;w0,1,2;w0,0,3;w1,0,2;w1,0,-2;w0,1,-2;w0,0,-3;w-1,0,-2"
    };
    private static final String BLOCK_CODES = "ewgp";
    private static final String[] BLOCK_NAMES = { "end_stone", "wool", "stained_glass", "planks" };
    // faces in EnumFacing order: DOWN, UP, NORTH, SOUTH, WEST, EAST
    private static final int[] FACE_DX = { 0, 0, 0, 0, -1, 1 }, FACE_DY = { -1, 1, 0, 0, 0, 0 }, FACE_DZ = { 0, 0, -1, 1, 0, 0 };
    // aim candidates: the neighbour on each side of the spot and the face of it that points at the spot,
    // in Onyx order DOWN (block above), UP (block below), SOUTH, NORTH, WEST, EAST
    private static final int[] AIM_FACE = { 0, 1, 3, 2, 4, 5 }, AIM_DX = { 0, 0, 0, 0, 1, -1 }, AIM_DY = { 1, -1, 0, 0, 0, 0 }, AIM_DZ = { 0, 0, -1, 1, 0, 0 };
    // an open spot: { x, y, z, block, pattern index, squared distance from the eyes, open spots it stands in front of }
    private static final int C_X = 0, C_Y = 1, C_Z = 2, C_BLOCK = 3, C_INDEX = 4, C_DIST = 5, C_FRONT = 6;
    private static final float[] HOLD = { -999f, -999f };

    private final ModeSetting defense = mode("Defense", "EndGlassNoCorners", DEFENSES).describe("围床的图案");
    private final ModeSetting order = mode("Order", "Bottom Up", "Bottom Up", "Furthest", "Nearest", "Pattern").describe("先放哪个（挡住别的空位的总是最后放）");
    private final BoolSetting bedwarsOnly = bool("Bedwars only", true).describe("只守起床战争里认出来的你自己的床；关掉就守 16 格内最近的床");
    private final BoolSetting onlyTopBeds = bool("Only top beds", true).describe("贴着床放方块时只点床的顶面");
    private final NumberSetting aimSpeed = number("Aim speed", 20, 1, 180, 1).suffix("°/tick").describe("静默转头每 tick 最多转多少");
    private final NumberSetting fov = number("FOV", 180, 0, 180, 1).suffix("°").describe("只点离你视线这么多度以内的面");
    private final NumberSetting swapDelay = number("Delay after swap", 0, 0, 10, 1).suffix(" ticks").describe("换到方块后等几 tick 再放");
    private final NumberSetting aimDelay = number("Delay after aiming", 0, 0, 10, 1).suffix(" ticks").describe("对准后等几 tick 再放");
    private final NumberSetting sneakHold = number("Sneak hold", 5, 0, 20, 1).suffix(" ticks").describe("点床放方块时要蹲（不然会去睡觉），蹲多久");
    private final NumberSetting perTick = number("Targets per tick", 4, 1, 16, 1).describe("每 tick 最多试几个空位");
    private final NumberSetting lockTicks = number("Target lock", 30, 5, 100, 5).suffix(" ticks").describe("盯住一个空位最多多久");
    private final NumberSetting retry = number("Retry cooldown", 20, 0, 100, 5).suffix(" ticks").describe("放不了（或刚放完）的空位隔多久再试");
    private final NumberSetting stuckTimeout = number("Stuck timeout", 100, 0, 400, 20).suffix(" ticks").describe("一直放不了多久就关掉（0 = 不关）");
    private final ModeSetting moveFix = mode("Move fix", "Silent", "Silent", "Strict", "Exact").describe("Silent：按键跟着转，照常往你看的方向走；Strict：按服务器朝向走");
    private final BoolSetting showTarget = bool("Show target", true).describe("把正在放的空位框出来");
    private final BoolSetting debug = bool("Debug", false).describe("在聊天里说每一步");

    private final List<int[]> steps = new ArrayList<>();
    private BlockPos bedPos;
    private boolean bedLocked, disableRequested, sneaking;
    private String lockedDirection = "";
    private int[] lockedTarget, renderTarget;
    private int lockTicksLeft, swapTicks, aimTicks, stuckTicks, sneakTicks;
    private final Map<Long, Integer> cooldowns = new HashMap<>();
    private final Set<String> hotbarBlocks = new HashSet<>();
    private final Map<String, Integer> slotCache = new HashMap<>();
    private final Random rng = new Random();

    public AutoDef() {
        super("AutoDef", Category.WORLD, "自动守床：照预设图案把床围起来，围完或没方块就自己关（BedDefender 的移植）");
        complements("BedDefender");
        on(UpdateEvent.class, e -> update());
        on(Render3DEvent.class, e -> {
            if (showTarget.get() && renderTarget != null)
                Render3D.block(new BlockPos(renderTarget[0], renderTarget[1], renderTarget[2]), 0x4000FF00, true, true, false);
        });
    }

    @Override
    public String getSuffix() { return defense.get(); }

    @Override
    protected void onEnable() {
        steps.clear();
        steps.addAll(parseDefense(defense.index()));
        disableRequested = false;
        if (steps.isEmpty()) {
            debug("enabled, but no defenses loaded.");
        } else {
            debug("enabled. defense='" + defense.get() + "' steps=" + steps.size());
            if (mc.thePlayer != null && !hotbarHasDefenseBlock()) {
                debug("no required blocks for selected defense in hotbar (disabling)");
                disableRequested = true;
            }
        }
        renderTarget = lockedTarget = null;
        lockTicksLeft = stuckTicks = swapTicks = 0;
        cooldowns.clear();
        hotbarBlocks.clear();
        slotCache.clear();
        bedLocked = sneaking = false;
        lockedDirection = "";
        aimTicks = aimDelay.getInt();
    }

    @Override
    protected void onDisable() {
        if (sneaking) {
            KeyUtil.restore(mc.gameSettings.keyBindSneak);
            sneaking = false;
        }
        renderTarget = null;
        debug("disabled.");
    }

    // one tick of the defense, before you move, from the rotation the server has now
    private void update() {
        if (!disableRequested) {
            tickSneak();
            float[] server = serverRotation();
            float[] rotation = computeRotation(server[0], server[1]);
            if (!disableRequested) {
                if (rotation != null) RotationManager.request(this, rotation[0], rotation[1], 50, fix());
                return;
            }
        }
        disableRequested = false;
        setEnabled(false);
    }

    private RotationManager.MoveFix fix() {
        return moveFix.is("Strict") ? RotationManager.MoveFix.NONE : moveFix.is("Exact") ? RotationManager.MoveFix.EXACT : RotationManager.MoveFix.LEGIT;
    }

    // what the server last got (what it checks a placement against); your own rotation before the first packet
    private static float[] serverRotation() {
        if (ServerState.lastMovementPacket() == 0) return new float[]{ mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch };
        return new float[]{ ServerState.yaw(), ServerState.pitch() };
    }

    // ------------------------------------------------------------------ the defense, every tick

    private float[] computeRotation(float yaw, float pitch) {
        float fovDeg = fov.getFloat(), pitchFov = Math.min(fovDeg, 90f);
        if (!bedLocked) {
            BlockPos bed;
            if (bedwarsOnly.get()) {
                BlockPos[] own = BedTracker.ownBed();
                if (own == null) {
                    debug("your bed is not known yet (Bed Wars only). disabling.");
                    return stop();
                }
                bed = nearerHalf(own[0], own[1]);
            } else {
                bed = findBed(16);
                if (bed == null) {
                    debug("no bed in range. disabling.");
                    return stop();
                }
            }
            if (!(mc.theWorld.getBlockState(bed).getBlock() instanceof BlockBed)) {
                debug("bed pos is stale/not a bed. disabling.");
                return stop();
            }
            debug("bed found at " + bed.getX() + "," + bed.getY() + "," + bed.getZ());
            bedPos = bed;
            switch (bedState(bed)) {
                case 0: case 10: lockedDirection = "north"; break;
                case 1: case 11: lockedDirection = "east"; break;
                case 2: case 8: lockedDirection = "south"; break;
                case 3: case 9: lockedDirection = "west"; break;
                default: lockedDirection = "";
            }
            debug("lockedDirection=" + lockedDirection);
            bedLocked = true;
        }

        tickCooldowns();
        refreshHotbarBlocks();
        int open = 0;
        boolean anyUsable = false;
        List<double[]> candidates = new ArrayList<>();
        List<int[]> openSpots = new ArrayList<>();
        Vec3 eye = PlayerUtil.eyes();
        for (int i = 0; i < steps.size(); i++) {
            int[] step = steps.get(i);
            int[] offset = rotateOffset(step[1], step[2], step[3], lockedDirection);
            int x = bedPos.getX() + offset[0], y = bedPos.getY() + offset[1], z = bedPos.getZ() + offset[2];
            if (!isAir(x, y, z)) continue;
            open++;
            openSpots.add(new int[]{ x, y, z });
            boolean usable = hotbarBlocks.contains(BLOCK_NAMES[step[0]]);
            anyUsable |= usable;
            if (usable && hasNeighbour(x, y, z) && noEntityInside(x, y, z)) {
                double dx = x + 0.5 - eye.xCoord, dy = y + 0.5 - eye.yCoord, dz = z + 0.5 - eye.zCoord;
                double distance = dx * dx + dy * dy + dz * dz;
                if (distance <= 36.0) candidates.add(new double[]{ x, y, z, step[0], i, distance, 0 });
            }
        }
        if (open == 0) {
            debug("done (pattern filled).");
            return stop();
        }
        if (!anyUsable) {
            debug("no block left in the hotbar for anything still open (disabling)");
            return stop();
        }
        for (double[] c : candidates) c[C_FRONT] = countBlockedBehind(c, openSpots, eye);
        int orderIndex = order.index();
        candidates.sort((a, b) -> compareCandidates(a, b, orderIndex));
        float[] aim = pickTarget(candidates, yaw, pitch, fovDeg, pitchFov);
        if (aim != null) return aim;
        stuckTicks++;
        int timeout = stuckTimeout.getInt();
        if (timeout > 0 && stuckTicks > timeout) {
            debug("nothing placeable for " + stuckTicks + " ticks (disabling)");
            return stop();
        }
        renderTarget = candidates.isEmpty() ? null : cell(candidates.get(0));
        return null;
    }

    private float[] stop() {
        disableRequested = true;
        return null;
    }

    // spots in front of others last, then the chosen order
    private static int compareCandidates(double[] a, double[] b, int order) {
        int c = Double.compare(a[C_FRONT], b[C_FRONT]);
        if (c != 0) return c;
        if (order == 0) {
            c = Double.compare(a[C_Y], b[C_Y]);
            if (c != 0) return c;
            c = Double.compare(b[C_DIST], a[C_DIST]);
        } else if (order == 1) {
            c = Double.compare(b[C_DIST], a[C_DIST]);
        } else if (order == 2) {
            c = Double.compare(a[C_DIST], b[C_DIST]);
        }
        return c != 0 ? c : Double.compare(a[C_INDEX], b[C_INDEX]);
    }

    // how many farther open spots this spot would hide from your eyes
    private static int countBlockedBehind(double[] c, List<int[]> openSpots, Vec3 eye) {
        int count = 0;
        for (int[] other : openSpots) {
            if (other[0] == (int) c[C_X] && other[1] == (int) c[C_Y] && other[2] == (int) c[C_Z]) continue;
            double ox = other[0] + 0.5, oy = other[1] + 0.5, oz = other[2] + 0.5;
            double dx = ox - eye.xCoord, dy = oy - eye.yCoord, dz = oz - eye.zCoord;
            if (dx * dx + dy * dy + dz * dz <= c[C_DIST]) continue;
            if (segmentHitsCell(eye, ox, oy, oz, (int) c[C_X], (int) c[C_Y], (int) c[C_Z])) count++;
        }
        return count;
    }

    // keep turning toward the locked spot while the lock lasts, else try the best few spots this tick
    private float[] pickTarget(List<double[]> candidates, float yaw, float pitch, float fovDeg, float pitchFov) {
        double[] locked = null;
        if (lockedTarget != null) {
            for (double[] c : candidates) {
                if (sameCell(c, lockedTarget)) {
                    locked = c;
                    break;
                }
            }
        }
        if (locked != null && lockTicksLeft > 0) {
            lockTicksLeft--;
            float[] aim = aimAt(locked, yaw, pitch, fovDeg, pitchFov);
            if (aim != null) {
                renderTarget = cell(locked);
                stuckTicks = 0;
                return aim;
            }
            debug("lost the aim on " + describe(locked) + ", moving on");
            cooldown(locked);
        }
        lockedTarget = null;
        int tried = 0, limit = perTick.getInt();
        for (double[] c : candidates) {
            if (cooldowns.containsKey(key((int) c[C_X], (int) c[C_Y], (int) c[C_Z]))) continue;
            if (tried >= limit) break;
            tried++;
            float[] aim = aimAt(c, yaw, pitch, fovDeg, pitchFov);
            if (aim != null) {
                lockedTarget = cell(c);
                lockTicksLeft = lockTicks.getInt();
                renderTarget = cell(c);
                stuckTicks = 0;
                return aim;
            }
            cooldown(c);
        }
        if (tried == 0 && !candidates.isEmpty()) cooldowns.clear();
        return null;
    }

    // the rotation for this tick toward a face that places the spot, or null when no face can be aimed at
    private float[] aimAt(double[] c, float yaw, float pitch, float fovDeg, float pitchFov) {
        int x = (int) c[C_X], y = (int) c[C_Y], z = (int) c[C_Z], block = (int) c[C_BLOCK];
        float[] direct = tryPlace(yaw, pitch, x, y, z, block, yaw, pitch);
        if (direct != null) return direct == HOLD ? stepToward(yaw, pitch, yaw, pitch) : direct;

        Vec3 eye = PlayerUtil.eyes();
        float wrappedYaw = wrap180(yaw);
        float viewYaw = wrap180(RotationManager.cameraYaw()), viewPitch = RotationManager.cameraPitch();
        double inset = 0.05, step = 0.2, jitter = 0.2;
        double far = 1.0 - inset - 0.001, near = inset + 0.001;
        int cells = (int) Math.round(1.0 / step);
        boolean onlyTop = onlyTopBeds.get();
        List<float[]> aims = new ArrayList<>();
        for (int f = 0; f < 6; f++) {
            int sx = x + AIM_DX[f], sy = y + AIM_DY[f], sz = z + AIM_DZ[f];
            if (isAir(sx, sy, sz) || onlyTop && isBedAt(sx, sy, sz) && AIM_FACE[f] != 1) continue;
            for (int a = 0; a <= cells; a++) {
                boolean forwardRow = (a & 1) == 0;
                double u = clamp01(a * step + randomBetween(-step * jitter, step * jitter));
                for (int b = 0; b <= cells; b++) {
                    double v = clamp01(b * step + randomBetween(-step * jitter, step * jitter));
                    double w = forwardRow ? v : 1.0 - v;
                    double px, py, pz;
                    if (f < 2) {
                        px = sx + w;
                        pz = sz + u;
                        py = sy + (f == 1 ? far : near);
                    } else if (f < 4) {
                        px = sx + w;
                        py = sy + u;
                        pz = sz + (f == 2 ? far : near);
                    } else {
                        pz = sz + w;
                        py = sy + u;
                        px = sx + (f == 5 ? far : near);
                    }
                    float[] rotation = rotationTo(eye, px, py, pz);
                    if (Math.abs(angleDiff(viewYaw, rotation[0])) > fovDeg || Math.abs(rotation[1] - viewPitch) > pitchFov || Math.abs(rotation[1]) > 90f) continue;
                    float cost = Math.abs(angleDiff(wrappedYaw, rotation[0])) + Math.abs(rotation[1] - pitch) + (f == 1 ? -0.25f : 0f);
                    aims.add(new float[]{ cost, rotation[0], rotation[1] });
                }
            }
        }
        if (aims.isEmpty()) {
            debug("no aim candidates (FOV/angles?)");
            return null;
        }
        aims.sort((p, q) -> Float.compare(p[0], q[0]));
        for (float[] aim : aims) {
            float aimYaw = nearestAngle(aim[1], yaw);
            float[] result = tryPlace(aimYaw, aim[2], x, y, z, block, yaw, pitch);
            if (result != null) return result == HOLD ? stepToward(yaw, pitch, aimYaw, aim[2]) : result;
        }
        return null;
    }

    // null when (aimYaw, aimPitch) does not click a face that places the spot; HOLD to keep turning toward it;
    // otherwise the rotation the server has, after right-clicking with it
    private float[] tryPlace(float aimYaw, float aimPitch, int tx, int ty, int tz, int block, float curYaw, float curPitch) {
        MovingObjectPosition ray = RaycastUtil.block(aimYaw, aimPitch, 4.5);
        if (ray == null) return null;
        BlockPos hit = ray.getBlockPos();
        int face = ray.sideHit.getIndex();
        if (hit.getX() + FACE_DX[face] != tx || hit.getY() + FACE_DY[face] != ty || hit.getZ() + FACE_DZ[face] != tz) return null;
        boolean hitBed = isBedAt(hit.getX(), hit.getY(), hit.getZ());
        boolean onlyTop = onlyTopBeds.get();
        if (onlyTop && face != 1 && hitBed) return null;
        String blockName = BLOCK_NAMES[block];
        int slot = findSlot(blockName);
        if (slot == -1) return null;
        EntityPlayerSP p = mc.thePlayer;
        if (p.inventory.currentItem != slot) {
            debug("switching to slot " + slot + " for " + blockName);
            p.inventory.currentItem = slot;
            swapTicks = swapDelay.getInt();
        }
        if (!isAir(tx, ty, tz)) return null;
        if (swapTicks > 0) {
            debug("swap delay... (" + swapTicks + " left)");
            swapTicks--;
            return HOLD;
        }
        KeyBinding sneakKey = mc.gameSettings.keyBindSneak;
        if (!sneakKey.isKeyDown() && hitBed) {
            // right-clicking a bed without sneaking would try to sleep in it
            KeyUtil.setPressed(sneakKey, true);
            sneaking = true;
            sneakTicks = sneakHold.getInt();
            debug("sneaking over bed for placement");
            return HOLD;
        }
        MovingObjectPosition current = RaycastUtil.block(curYaw, curPitch, 4.5);
        boolean ready = false;
        if (current != null) {
            BlockPos ch = current.getBlockPos();
            int cf = current.sideHit.getIndex();
            if (ch.getX() + FACE_DX[cf] == tx && ch.getY() + FACE_DY[cf] == ty && ch.getZ() + FACE_DZ[cf] == tz)
                ready = !onlyTop || cf == 1 || !isBedAt(ch.getX(), ch.getY(), ch.getZ());
        }
        if (aimTicks <= 0 && ready) {
            aimTicks = aimDelay.getInt();
            ItemStack held = p.getHeldItem();
            if (held != null && mc.playerController.onPlayerRightClick(p, mc.theWorld, held, current.getBlockPos(), current.sideHit, current.hitVec)) {
                p.swingItem();
                debug("placed " + blockName + " at " + tx + "," + ty + "," + tz);
                cooldown(tx, ty, tz);
                lockedTarget = null;
            } else {
                debug("place failed at " + tx + "," + ty + "," + tz);
            }
            return new float[]{ curYaw, curPitch };
        }
        if (aimTicks > 0) aimTicks--;
        return HOLD;
    }

    private float[] stepToward(float yaw, float pitch, float targetYaw, float targetPitch) {
        float speed = aimSpeed.getFloat();
        return new float[]{ yaw + Math.max(-speed, Math.min(speed, targetYaw - yaw)), pitch + Math.max(-speed, Math.min(speed, targetPitch - pitch)) };
    }

    private void tickSneak() {
        if (!sneaking) return;
        if (sneakTicks > 0) {
            sneakTicks--;
        } else {
            KeyUtil.restore(mc.gameSettings.keyBindSneak);
            sneaking = false;
            debug("released sneak.");
        }
    }

    // ------------------------------------------------------------------ bed and pattern

    private static List<int[]> parseDefense(int defense) {
        List<int[]> list = new ArrayList<>();
        if (defense < 0 || defense >= DEFENSE_DATA.length) return list;
        for (String part : DEFENSE_DATA[defense].split(";")) {
            if (part.isEmpty()) continue;
            int block = BLOCK_CODES.indexOf(part.charAt(0));
            String[] xyz = part.substring(1).split(",");
            if (block == -1 || xyz.length != 3) continue;
            list.add(new int[]{ block, Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2]) });
        }
        return list;
    }

    // the half of the bed nearer to you (the pattern is laid out from it)
    private static BlockPos nearerHalf(BlockPos a, BlockPos b) {
        BlockPos me = new BlockPos(mc.thePlayer);
        return a.distanceSq(me) <= b.distanceSq(me) ? a : b;
    }

    // the nearest bed half within range, measured from your block
    private BlockPos findBed(int range) {
        BlockPos me = new BlockPos(mc.thePlayer);
        double best = Double.MAX_VALUE;
        BlockPos found = null;
        for (int x = me.getX() - range; x <= me.getX() + range; x++) {
            for (int y = me.getY() - range; y <= me.getY() + range; y++) {
                for (int z = me.getZ() - range; z <= me.getZ() + range; z++) {
                    BlockPos p = new BlockPos(x, y, z);
                    BlockPos head, foot;
                    switch (bedState(p)) {
                        case 0: foot = p; head = p.south(); break;
                        case 1: foot = p; head = p.west(); break;
                        case 2: foot = p; head = p.north(); break;
                        case 3: foot = p; head = p.east(); break;
                        case 8: head = p; foot = p.north(); break;
                        case 9: head = p; foot = p.east(); break;
                        case 10: head = p; foot = p.south(); break;
                        case 11: head = p; foot = p.west(); break;
                        default: continue;
                    }
                    double toHead = head.distanceSq(me), toFoot = foot.distanceSq(me);
                    double nearest = Math.min(toHead, toFoot);
                    if (nearest < best) {
                        best = nearest;
                        found = toHead <= toFoot ? head : foot;
                    }
                }
            }
        }
        return found;
    }

    // facing (0 south, 1 west, 2 north, 3 east) plus 8 for the head half; -1 when not a bed
    private static int bedState(BlockPos p) {
        IBlockState s = mc.theWorld.getBlockState(p);
        Block b = s.getBlock();
        return b instanceof BlockBed ? b.getMetaFromState(s) & 11 : -1;
    }

    private static int[] rotateOffset(int x, int y, int z, String direction) {
        switch (direction) {
            case "south": return new int[]{ -x, y, -z };
            case "east": return new int[]{ -z, y, x };
            case "west": return new int[]{ z, y, -x };
            default: return new int[]{ x, y, z };
        }
    }

    private static boolean isAir(int x, int y, int z) { return mc.theWorld.isAirBlock(new BlockPos(x, y, z)); }

    private static boolean isBedAt(int x, int y, int z) { return mc.theWorld.getBlockState(new BlockPos(x, y, z)).getBlock() instanceof BlockBed; }

    private static boolean hasNeighbour(int x, int y, int z) {
        for (int f = 0; f < 6; f++) if (!isAir(x + FACE_DX[f], y + FACE_DY[f], z + FACE_DZ[f])) return true;
        return false;
    }

    // the game's own test for placing a full block: no living thing, boat, minecart, lit TNT or falling block inside
    private static boolean noEntityInside(int x, int y, int z) {
        return mc.theWorld.checkNoEntityCollision(new AxisAlignedBB(x, y, z, x + 1, y + 1, z + 1));
    }

    private static String itemName(ItemStack stack) {
        if (stack == null || stack.getItem() == null) return null;
        ResourceLocation r = Item.itemRegistry.getNameForObject(stack.getItem());
        return r == null ? null : r.getResourcePath();
    }

    private boolean hotbarHasDefenseBlock() {
        Set<String> needed = new HashSet<>();
        for (int[] step : steps) needed.add(BLOCK_NAMES[step[0]]);
        for (int i = 0; i < 9; i++) {
            String name = itemName(mc.thePlayer.inventory.getStackInSlot(i));
            if (name != null && needed.contains(name)) return true;
        }
        return false;
    }

    private void refreshHotbarBlocks() {
        hotbarBlocks.clear();
        for (int i = 0; i < 9; i++) {
            String name = itemName(mc.thePlayer.inventory.getStackInSlot(i));
            if (name != null) hotbarBlocks.add(name);
        }
    }

    private int findSlot(String name) {
        Integer cached = slotCache.get(name);
        if (cached != null && name.equals(itemName(mc.thePlayer.inventory.getStackInSlot(cached)))) return cached;
        for (int i = 0; i < 9; i++) {
            if (name.equals(itemName(mc.thePlayer.inventory.getStackInSlot(i)))) {
                slotCache.put(name, i);
                return i;
            }
        }
        return -1;
    }

    private void tickCooldowns() {
        Iterator<Map.Entry<Long, Integer>> it = cooldowns.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Integer> entry = it.next();
            entry.setValue(entry.getValue() - 1);
            if (entry.getValue() <= 0) it.remove();
        }
    }

    private void cooldown(double[] c) { cooldown((int) c[C_X], (int) c[C_Y], (int) c[C_Z]); }

    // skip a spot for "Retry cooldown" ticks (also after placing, in case the server takes the block back)
    private void cooldown(int x, int y, int z) {
        int ticks = retry.getInt();
        if (ticks > 0) cooldowns.put(key(x, y, z), ticks);
    }

    // ------------------------------------------------------------------ helpers

    private static float[] rotationTo(Vec3 eye, double x, double y, double z) {
        double dx = x - eye.xCoord, dy = y - eye.yCoord, dz = z - eye.zCoord;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = wrap180((float) Math.toDegrees(Math.atan2(dz, dx)) - 90f);
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, horizontal));
        return new float[]{ yaw, pitch };
    }

    // does the segment from the eyes to (tx, ty, tz) pass through the block cell
    private static boolean segmentHitsCell(Vec3 eye, double tx, double ty, double tz, int x, int y, int z) {
        double[] start = { eye.xCoord, eye.yCoord, eye.zCoord };
        double[] delta = { tx - eye.xCoord, ty - eye.yCoord, tz - eye.zCoord };
        double[] min = { x, y, z };
        double enter = 0.0, exit = 1.0;
        for (int i = 0; i < 3; i++) {
            if (Math.abs(delta[i]) < 1.0E-9) {
                if (start[i] < min[i] || start[i] > min[i] + 1) return false;
                continue;
            }
            double t1 = (min[i] - start[i]) / delta[i], t2 = (min[i] + 1 - start[i]) / delta[i];
            enter = Math.max(enter, Math.min(t1, t2));
            exit = Math.min(exit, Math.max(t1, t2));
            if (enter > exit) return false;
        }
        return true;
    }

    private static float wrap180(float angle) {
        float a = (angle % 360f + 360f) % 360f;
        return a > 180f ? a - 360f : a;
    }

    // signed difference from a to b in (-180, 180]
    private static float angleDiff(float a, float b) {
        float d = b - a;
        while (d <= -180f) d += 360f;
        while (d > 180f) d -= 360f;
        return d;
    }

    // the angle equal to a that is closest to b
    private static float nearestAngle(float a, float b) { return b + (((a - b + 180f) % 360f + 360f) % 360f - 180f); }

    private static double clamp01(double v) { return v < 0.0 ? 0.0 : (v > 1.0 ? 1.0 : v); }

    private double randomBetween(double a, double b) { return a + rng.nextDouble() * (b - a); }

    private static int[] cell(double[] c) { return new int[]{ (int) c[C_X], (int) c[C_Y], (int) c[C_Z] }; }

    private static boolean sameCell(double[] c, int[] p) { return (int) c[C_X] == p[0] && (int) c[C_Y] == p[1] && (int) c[C_Z] == p[2]; }

    private static String describe(double[] c) { return (int) c[C_X] + "," + (int) c[C_Y] + "," + (int) c[C_Z]; }

    private static long key(int x, int y, int z) { return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (long) (z & 0x3FFFFFF); }

    private void debug(String message) {
        if (!debug.get()) return;
        ChatUtil.print("&7[&cautodef&7] &f" + message);
        gale.Gale.LOG.info("[AutoDef] {}", message);
    }

    // ------------------------------------------------------------------ for the self test

    /** The open spot it works on now, or null. */
    public BlockPos target() { return renderTarget == null ? null : new BlockPos(renderTarget[0], renderTarget[1], renderTarget[2]); }

    /** The spots of the chosen pattern around a bed half, in the pattern's order (for checking what got placed). */
    public List<BlockPos> patternAround(BlockPos bed) {
        String direction;
        switch (bedState(bed)) {
            case 0: case 10: direction = "north"; break;
            case 1: case 11: direction = "east"; break;
            case 2: case 8: direction = "south"; break;
            case 3: case 9: direction = "west"; break;
            default: direction = "";
        }
        List<BlockPos> out = new ArrayList<>();
        for (int[] step : parseDefense(defense.index())) {
            int[] o = rotateOffset(step[1], step[2], step[3], direction);
            out.add(bed.add(o[0], o[1], o[2]));
        }
        return out;
    }
}
