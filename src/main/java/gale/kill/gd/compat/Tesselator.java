package gale.kill.gd.compat;
public final class Tesselator {private static final Tesselator INSTANCE=new Tesselator();private final BufferBuilder builder=new BufferBuilder();
 public static Tesselator getInstance(){return INSTANCE;}public BufferBuilder getBuilder(){return builder;}
 public void end(){net.minecraft.client.renderer.Tessellator.getInstance().draw();}}
