/* Adapted from GD656Killicon by Minecraft_GD656 (MIT); see META-INF/GD656-NOTICE.txt. */
package gale.kill.gd.client.render.effect;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.util.ResourceLocation;
import gale.kill.gd.client.config.ClientConfigManager;

public final class IconTextureFilterEffect {
    private IconTextureFilterEffect() {
    }

    public static void apply(ResourceLocation textureLocation) {
        if (textureLocation == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null || minecraft.getTextureManager() == null) {
            return;
        }
        ITextureObject texture = minecraft.getTextureManager().getTexture(textureLocation);
        if (texture != null) {
            texture.setBlurMipmap(ClientConfigManager.isEnableIconAntialiasing(), false);
        }
    }
}
