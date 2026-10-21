package gale.util;

import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.EnumCreatureAttribute;
import net.minecraft.entity.IEntityMultiPart;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.boss.EntityDragonPart;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraft.stats.AchievementList;
import net.minecraft.stats.StatList;
import net.minecraft.util.DamageSource;
import net.minecraft.util.MathHelper;

import java.lang.reflect.Method;

/**
 * Your side of a hit, without the game: what {@code EntityPlayer.attackTargetEntityWithCurrentItem} does after the C02
 * is sent, with the slowdown and the sprint stop as parameters. And a swing that sends nothing.
 */
public final class ClientAttack {
    private static final Method SWING_END = ReflectUtil.method(EntityLivingBase.class, new String[]{ "getArmSwingAnimationEnd", "func_82166_i" });

    private ClientAttack() { }

    /**
     * The game's attackTargetEntityWithCurrentItem (1.8.9), step for step, except: Forge's attack event is not fired,
     * and a hit that knocks back multiplies your motion by {@code slowdown} (the game: 0.6) and stops your sprint only
     * when {@code keepSprint} is false. Sends nothing. On the client, only hits on other players "land" (the game's
     * client-side mobs refuse damage), so only those slow you down, as in the game.
     */
    public static void perform(EntityPlayer p, Entity target, double slowdown, boolean keepSprint) {
        if (p == null || target == null || !target.canAttackWithItem() || target.hitByEntity(p)) return;
        float damage = (float) p.getEntityAttribute(SharedMonsterAttributes.attackDamage).getAttributeValue();
        float bonus = EnchantmentHelper.getModifierForCreature(p.getHeldItem(),
            target instanceof EntityLivingBase ? ((EntityLivingBase) target).getCreatureAttribute() : EnumCreatureAttribute.UNDEFINED);
        int knockback = EnchantmentHelper.getKnockbackModifier(p);
        if (p.isSprinting()) knockback++;
        if (damage <= 0 && bonus <= 0) return;
        boolean crit = p.fallDistance > 0 && !p.onGround && !p.isOnLadder() && !p.isInWater() && !p.isPotionActive(Potion.blindness)
            && p.ridingEntity == null && target instanceof EntityLivingBase;
        if (crit && damage > 0) damage *= 1.5F;
        damage += bonus;
        boolean lit = false;
        int fire = EnchantmentHelper.getFireAspectModifier(p);
        if (target instanceof EntityLivingBase && fire > 0 && !target.isBurning()) {
            lit = true;
            target.setFire(1);
        }
        if (!target.attackEntityFrom(DamageSource.causePlayerDamage(p), damage)) {
            if (lit) target.extinguish();
            return;
        }
        if (knockback > 0) {
            float yaw = p.rotationYaw * (float) Math.PI / 180.0F;
            target.addVelocity(-MathHelper.sin(yaw) * knockback * 0.5F, 0.1D, MathHelper.cos(yaw) * knockback * 0.5F);
            p.motionX *= slowdown;
            p.motionZ *= slowdown;
            if (!keepSprint) p.setSprinting(false);
        }
        if (crit) p.onCriticalHit(target);
        if (bonus > 0) p.onEnchantmentCritical(target);
        if (damage >= 18.0F) p.triggerAchievement(AchievementList.overkill);
        p.setLastAttacker(target);
        if (target instanceof EntityLivingBase) EnchantmentHelper.applyThornEnchantments((EntityLivingBase) target, p);
        EnchantmentHelper.applyArthropodEnchantments(p, target);
        ItemStack stack = p.getCurrentEquippedItem();
        Entity hit = target;
        if (target instanceof EntityDragonPart) {
            IEntityMultiPart owner = ((EntityDragonPart) target).entityDragonObj;
            if (owner instanceof EntityLivingBase) hit = (EntityLivingBase) owner;
        }
        if (stack != null && hit instanceof EntityLivingBase) {
            stack.hitEntity((EntityLivingBase) hit, p);
            if (stack.stackSize <= 0) p.destroyCurrentEquippedItem();
        }
        if (target instanceof EntityLivingBase) {
            p.addStat(StatList.damageDealtStat, Math.round(damage * 10.0F));
            if (fire > 0) target.setFire(fire * 4);
        }
        p.addExhaustion(0.3F);
    }

    /** Starts the arm swing on your screen (and others' view of you in third person) without sending a C0A. */
    public static void swingLocal(EntityLivingBase e) {
        if (e == null) return;
        ItemStack stack = e.getHeldItem();
        if (stack != null && stack.getItem() != null && stack.getItem().onEntitySwing(e, stack)) return;
        Integer end = ReflectUtil.invoke(SWING_END, e);
        int half = (end == null ? 6 : end) / 2;
        if (!e.isSwingInProgress || e.swingProgressInt >= half || e.swingProgressInt < 0) {
            e.swingProgressInt = -1;
            e.isSwingInProgress = true;
        }
    }
}
