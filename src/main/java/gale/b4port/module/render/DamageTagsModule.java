package gale.b4port.module.render;

import net.minecraft.entity.Entity;
import net.minecraft.entity.monster.EntityGolem;
import net.minecraft.entity.monster.EntityMob;
import net.minecraft.entity.monster.EntitySlime;

/** From B4's Damage Tags: what counts as a mob. */
public final class DamageTagsModule {
    private DamageTagsModule() {
    }

    public static boolean isMob(Entity e) {
        return e instanceof EntityMob || e instanceof EntityGolem || e instanceof EntitySlime;
    }
}
