package gale.meowtils.api.event;

import gale.meowtils.api.event.api.Event;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.RenderPlayer;

public class RenderPlayerEvent extends Event {
    private final RenderPlayer renderer;
    private final AbstractClientPlayer player;
    private final double x;
    private final double y;
    private final double z;
    private final float yaw;
    private final float partialTicks;
    private final Stage stage;

    public RenderPlayerEvent(RenderPlayer renderer, AbstractClientPlayer player, double x, double y, double z, float yaw, float partialTicks, Stage stage) {
        this.renderer = renderer;
        this.player = player;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.partialTicks = partialTicks;
        this.stage = stage;
    }

    public RenderPlayer getRenderer() { return renderer; }

    public AbstractClientPlayer getPlayer() { return player; }

    public double getX() { return x; }

    public double getY() { return y; }

    public double getZ() { return z; }

    public float getYaw() { return yaw; }

    public float getPartialTicks() { return partialTicks; }

    public Stage getStage() { return stage; }

    public enum Stage { PRE, POST }
}
