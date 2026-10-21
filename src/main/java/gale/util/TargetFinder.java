package gale.util;

import gale.Gale;
import gale.module.impl.client.Targets;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.passive.EntityAnimal;
import net.minecraft.entity.passive.EntityVillager;
import net.minecraft.entity.passive.EntityWaterMob;
import net.minecraft.entity.player.EntityPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * Finds entities to target. Starts from the Targets module's settings (players, mobs, teams, bots, friends...);
 * change anything for one search:
 *
 * <pre>
 * EntityLivingBase t = TargetFinder.create().range(4).fov(90).sort(TargetFinder.Sort.ANGLE).first();
 * </pre>
 */
public final class TargetFinder {
    public enum Sort { DISTANCE, ANGLE, HEALTH, HURT_TIME }

    private static final Minecraft mc = Minecraft.getMinecraft();
    private double range = 6;
    private float fov = 360;
    private boolean players, mobs, animals, invisibles, skipTeams, skipBots, skipFriends, walls;
    private TeamUtil.Check teamCheck;
    private Sort sort = Sort.DISTANCE;
    private Predicate<EntityLivingBase> extra = e -> true;

    private TargetFinder() {
        Targets t = Gale.modules().get(Targets.class);
        players = t.players.get();
        mobs = t.mobs.get();
        animals = t.animals.get();
        invisibles = t.invisibles.get();
        skipTeams = t.teams.get();
        skipBots = t.bots.get();
        skipFriends = t.friends.get();
        walls = t.walls.get();
        teamCheck = t.teamCheck();
    }

    public static TargetFinder create() { return new TargetFinder(); }

    /** Reach distance: eyes to the nearest point of the hitbox. */
    public TargetFinder range(double range) { this.range = range; return this; }

    /** Whole cone in degrees around where the camera looks (360: all around). */
    public TargetFinder fov(float fov) { this.fov = fov; return this; }

    public TargetFinder players(boolean on) { players = on; return this; }

    public TargetFinder mobs(boolean on) { mobs = on; return this; }

    public TargetFinder animals(boolean on) { animals = on; return this; }

    public TargetFinder invisibles(boolean on) { invisibles = on; return this; }

    /** Skip teammates. */
    public TargetFinder skipTeams(boolean on) { skipTeams = on; return this; }

    public TargetFinder skipBots(boolean on) { skipBots = on; return this; }

    public TargetFinder skipFriends(boolean on) { skipFriends = on; return this; }

    /** Allow targets you cannot see. */
    public TargetFinder walls(boolean on) { walls = on; return this; }

    public TargetFinder sort(Sort sort) { this.sort = sort; return this; }

    /** One more test every target must pass. */
    public TargetFinder filter(Predicate<EntityLivingBase> test) { extra = extra.and(test); return this; }

    public boolean accepts(EntityLivingBase e) {
        if (e == mc.thePlayer || !EntityUtil.isAlive(e) || e instanceof EntityArmorStand) return false;
        if (e instanceof EntityPlayer) {
            EntityPlayer p = (EntityPlayer) e;
            if (!players) return false;
            if (skipBots && EntityUtil.isBot(p)) return false;
            if (skipFriends && Friends.is(p)) return false;
            if (skipTeams && (teamCheck == null ? TeamUtil.isTeammate(p) : TeamUtil.isTeammate(p, teamCheck))) return false;
        } else if (e instanceof IMob) {
            if (!mobs) return false;
        } else if (e instanceof EntityAnimal || e instanceof EntityWaterMob || e instanceof EntityVillager) {
            if (!animals) return false;
        } else {
            return false;
        }
        if (!invisibles && e.isInvisible()) return false;
        if (EntityUtil.reachDistance(e) > range) return false;
        if (!RotationUtil.inFov(e, fov)) return false;
        if (!walls && !RaycastUtil.canSee(e)) return false;
        return extra.test(e);
    }

    public List<EntityLivingBase> list() {
        List<EntityLivingBase> out = new ArrayList<>();
        if (mc.theWorld == null || mc.thePlayer == null) return out;
        for (EntityLivingBase e : EntityUtil.living()) if (accepts(e)) out.add(e);
        out.sort(comparator());
        return out;
    }

    /** The best target, or null. */
    public EntityLivingBase first() {
        List<EntityLivingBase> l = list();
        return l.isEmpty() ? null : l.get(0);
    }

    private Comparator<EntityLivingBase> comparator() {
        switch (sort) {
            case ANGLE: {
                float yaw = RotationUtil.camera()[0];
                return Comparator.comparingDouble(e -> Math.abs(MathUtil.angleDiff(yaw, RotationUtil.to(e, RotationUtil.AimPoint.CENTER)[0])));
            }
            case HEALTH: return Comparator.comparingDouble(EntityUtil::health);
            case HURT_TIME: return Comparator.<EntityLivingBase>comparingInt(e -> e.hurtTime).thenComparingDouble(EntityUtil::reachDistance);
            default: return Comparator.comparingDouble(EntityUtil::reachDistance);
        }
    }
}
