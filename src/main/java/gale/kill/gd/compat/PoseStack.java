package gale.kill.gd.compat;
import net.minecraft.client.renderer.GlStateManager;
public final class PoseStack {
 private static final Pose POSE=new Pose();
 public void pushPose(){GlStateManager.pushMatrix();} public void popPose(){GlStateManager.popMatrix();}
 public void translate(double x,double y,double z){GlStateManager.translate(x,y,z);}
 public void scale(float x,float y,float z){GlStateManager.scale(x,y,z);}
 public void mulPose(float degrees){GlStateManager.rotate(degrees,0,0,1);}
 public Pose last(){return POSE;} public static final class Pose{private final Matrix4f matrix=new Matrix4f();public Matrix4f pose(){return matrix;}}
}
