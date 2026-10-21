/* Adapted from GD656Killicon by Minecraft_GD656 (MIT); see META-INF/GD656-NOTICE.txt. */
package gale.kill.gd.client.textures;

import net.minecraft.util.ResourceLocation;

public class ModTextures {
    public static ResourceLocation get(String path) {
        return ExternalTextureManager.getTexture(path);
    }
}
