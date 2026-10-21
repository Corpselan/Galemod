package gale.render.model;

import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.item.EnumAction;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MathHelper;

/** Fifteen reusable driver matrices. Model coordinates are metres, Y up, facing -Z. */
public final class JettPose {
    public final float[][] matrices = new float[15][16];
    private final float[][] pivots;
    private final int[] parents;
    public final float[] rightHand = new float[3];
    public float grip;

    public JettPose(float[][] pivots, int[] parents) { this.pivots = pivots; this.parents = parents; }

    public void third(AbstractClientPlayer player, float partial) {
        for (float[] matrix : matrices) identity(matrix);
        boolean sneak = player.isSneaking();
        if (sneak) translate(matrices[0], 0, -0.18f, 0);
        around(1, sneak ? -0.30f : 0, 0, 0);
        float body = interpolateAngle(player.prevRenderYawOffset, player.renderYawOffset, partial);
        float head = interpolateAngle(player.prevRotationYawHead, player.rotationYawHead, partial);
        around(2, -(player.prevRotationPitch + (player.rotationPitch - player.prevRotationPitch) * partial) * 0.017453292f,
            -MathHelper.wrapAngleTo180_float(head - body) * 0.017453292f, 0);
        float amount = player.prevLimbSwingAmount + (player.limbSwingAmount - player.prevLimbSwingAmount) * partial;
        amount = Math.min(1, amount);
        float phase = (player.limbSwing - player.limbSwingAmount * (1 - partial)) * 0.6662f;
        float walk = MathHelper.cos(phase) * amount;
        ItemStack item = player.getHeldItem();
        boolean using = item != null && player.getItemInUseCount() > 0;
        EnumAction action = using ? item.getItemUseAction() : EnumAction.NONE;
        float swing = MathHelper.sin(player.getSwingProgress(partial) * (float) Math.PI);
        float right = (item == null ? 0 : 0.25f) - walk * 0.6f + swing * 1.7f;
        float left = walk * 0.6f;
        if (action == EnumAction.BLOCK) right = 1.1f;
        if (action == EnumAction.BOW) { right = 1.5f; left = 1.5f; }
        around(3, left, 0, 0.04f);
        around(4, 0.05f, 0, 0);
        around(5, 0, 0, 0);
        around(6, right, action == EnumAction.BLOCK ? -0.6f : 0, -0.04f);
        around(7, item == null ? 0.05f : 0.35f, 0, 0);
        around(8, 0, 0, 0);
        float hip = player.isRiding() ? 1.2f : sneak ? 0.75f : 0;
        float knee = player.isRiding() ? -1.2f : sneak ? -1.35f : 0;
        float stride = walk * (sneak ? 0.4f : 0.85f);
        around(9, hip + stride, 0, 0);
        around(10, knee - Math.max(0, -walk) * 0.35f, 0, 0);
        around(11, sneak ? 0.6f : 0, 0, 0);
        around(12, hip - stride, 0, 0);
        around(13, knee - Math.max(0, walk) * 0.35f, 0, 0);
        around(14, sneak ? 0.6f : 0, 0, 0);
        point(matrices[8], pivots[8][0], pivots[8][1] - 0.075f, pivots[8][2], rightHand);
        grip = item == null ? 0.15f : 1f;
    }

    public float itemPitch, itemYaw, itemRoll;
    private final float[] elbow = new float[3];
    private final float[] smoothWrist = new float[6];
    private long lastFirstFrame;
    private float maxArmError;

    public void first(AbstractClientPlayer player, float partial) {
        for (float[] matrix : matrices) identity(matrix);
        float age = player.ticksExisted + partial;
        float swing = player.getSwingProgress(partial);
        float arc = MathHelper.sin(swing * (float) Math.PI);
        ItemStack item = player.getHeldItem();
        EnumAction action = item != null && player.getItemInUseCount() > 0 ? item.getItemUseAction() : EnumAction.NONE;
        boolean gun = item != null && gale.Gale.modules().get(gale.module.impl.render.BowModel.class).replaces(item);
        boolean bow = item != null && item.getItem() instanceof net.minecraft.item.ItemBow && !gun;
        boolean useBow = bow && action == EnumAction.BOW;
        boolean consuming = action == EnumAction.EAT || action == EnumAction.DRINK;
        float breath = MathHelper.sin(age * 0.08f) * 0.0025f;
        float walk = MathHelper.sin(player.distanceWalkedModified * 6) * Math.min(.012f, player.limbSwingAmount * .025f);
        float rx = .33f - arc * .12f, ry = -.31f + breath + walk + arc * .035f, rz = -.57f - arc * .07f;
        float lx = -.39f, ly = -.55f + breath, lz = -.52f;
        boolean rifle=gun && !gale.Gale.modules().get(gale.module.impl.render.BowModel.class).skin.is("Sheriff");
        if (gun) { rx=.24f; ry=-.30f+breath; rz=-.65f; }
        if (rifle) { lx=.18f; ly=-.29f+breath; lz=-.88f; }
        if (item == null) { rx = .34f; ry = -.34f + breath; rz = -.56f; }
        if (bow) {
            lx = -.12f; ly = -.16f + breath; lz = -.64f;
            // The support hand holds the bow; the drawing arm rests outside the camera.
            rx = .58f; ry = -.54f; rz = -.20f;
        }
        if (action == EnumAction.BLOCK) { rx = .12f; ry = -.24f; rz = -.54f; }
        if (consuming) { rx = .12f; ry = -.12f + MathHelper.sin(age * .8f) * .009f; rz = -.42f; }
        long now = System.nanoTime();
        float dt = lastFirstFrame == 0 ? 1 : Math.min(.5f, (now-lastFirstFrame) / 1_000_000_000f);
        float blend = dt >= .5f ? 1 : 1 - (float) Math.exp(-dt * 24);
        lastFirstFrame = now;
        smoothWrist[0] += (rx-smoothWrist[0])*blend; smoothWrist[1] += (ry-smoothWrist[1])*blend;
        smoothWrist[2] += (rz-smoothWrist[2])*blend; smoothWrist[3] += (lx-smoothWrist[3])*blend;
        smoothWrist[4] += (ly-smoothWrist[4])*blend; smoothWrist[5] += (lz-smoothWrist[5])*blend;
        arm(6,7,8, gun?.51f:.57f,-.52f,gun?-.33f:-.22f, smoothWrist[0],smoothWrist[1],smoothWrist[2], false, -.95f);
        arm(3,4,5, rifle?-.12f:-.56f,rifle?-.55f:-.53f,rifle?-.60f:-.22f, smoothWrist[3],smoothWrist[4],smoothWrist[5], true, .65f);
        int hand = bow ? 5 : 8;
        point(matrices[hand], pivots[hand][0], pivots[hand][1]-.065f, pivots[hand][2], rightHand);
        itemPitch = -arc * 32 + (consuming ? 20 : 0);
        itemYaw = (gun?10:-15) - arc * 12;
        itemRoll = (action == EnumAction.BLOCK ? 65 : 12) - arc * 35;
        grip = item == null ? .35f : .95f;
    }

    /** Two-bone IK with the original segment lengths: elbows and wrists stay connected. */
    private void arm(int upper, int lower, int hand, float sx, float sy, float sz,
        float wx, float wy, float wz, boolean left, float roll) {
        float a = distance(pivots[upper],pivots[lower]), b = distance(pivots[lower],pivots[hand]);
        float dx=wx-sx, dy=wy-sy, dz=wz-sz;
        float length=(float)Math.sqrt(dx*dx+dy*dy+dz*dz);
        dx/=length; dy/=length; dz/=length;
        length=MathHelper.clamp_float(length,Math.abs(a-b)+.001f,a+b-.001f);
        wx=sx+dx*length; wy=sy+dy*length; wz=sz+dz*length;
        float hx=left ? -.8f : .8f, hy=-.6f, hz=.1f;
        float dot=hx*dx+hy*dy+hz*dz; hx-=dot*dx; hy-=dot*dy; hz-=dot*dz;
        float hlen=(float)Math.sqrt(hx*hx+hy*hy+hz*hz); hx/=hlen; hy/=hlen; hz/=hlen;
        float along=(a*a-b*b+length*length)/(2*length);
        float height=(float)Math.sqrt(Math.max(0,a*a-along*along));
        elbow[0]=sx+dx*along+hx*height; elbow[1]=sy+dy*along+hy*height; elbow[2]=sz+dz*along+hz*height;
        segment(matrices[upper],pivots[upper],sx,sy,sz,elbow[0]-sx,elbow[1]-sy,elbow[2]-sz);
        segment(matrices[lower],pivots[lower],elbow[0],elbow[1],elbow[2],wx-elbow[0],wy-elbow[1],wz-elbow[2]);
        segment(matrices[hand],pivots[hand],wx,wy,wz,left ? .12f : -.10f,.06f,-1);
        float[] m=matrices[hand], p=pivots[hand];
        translate(m,p[0],p[1],p[2]); rotate(m,roll,2,0); translate(m,-p[0],-p[1],-p[2]);
        // Actual transformed joint positions, rather than requested targets, verify continuity.
        float[] elbowBind=pivots[lower], handBind=pivots[hand];
        for (int row=0;row<3;row++) {
            float ev=matrices[upper][row]*elbowBind[0]+matrices[upper][4+row]*elbowBind[1]+matrices[upper][8+row]*elbowBind[2]+matrices[upper][12+row];
            maxArmError=Math.max(maxArmError,Math.abs(ev-elbow[row]));
            float hv=matrices[lower][row]*handBind[0]+matrices[lower][4+row]*handBind[1]+matrices[lower][8+row]*handBind[2]+matrices[lower][12+row];
            maxArmError=Math.max(maxArmError,Math.abs(hv-(row==0 ? wx : row==1 ? wy : wz)));
        }
    }

    public float maxArmError() { return maxArmError; }
    private static float distance(float[] a,float[] b) {
        float x=a[0]-b[0],y=a[1]-b[1],z=a[2]-b[2]; return (float)Math.sqrt(x*x+y*y+z*z);
    }

    private static void segment(float[] m, float[] pivot, float x, float y, float z, float dx, float dy, float dz) {
        identity(m);
        float length = (float) Math.sqrt(dx*dx + dy*dy + dz*dz);
        dx /= length; dy /= length; dz /= length;
        // Rodrigues rotation from bind direction (0,-1,0) to the posed segment.
        float vx = -dz, vz = dx, c = -dy, inv = 1f / Math.max(0.00001f, 1f + c);
        m[0] = 1 - vz*vz*inv; m[1] = vz; m[2] = vx*vz*inv;
        m[4] = -vz; m[5] = c; m[6] = vx;
        m[8] = vx*vz*inv; m[9] = -vx; m[10] = 1 - vx*vx*inv;
        m[12] = x; m[13] = y; m[14] = z;
        translate(m, -pivot[0], -pivot[1], -pivot[2]);
    }

    private void around(int bone, float rx, float ry, float rz) {
        System.arraycopy(matrices[parents[bone]], 0, matrices[bone], 0, 16);
        float[] m = matrices[bone], p = pivots[bone];
        translate(m, p[0], p[1], p[2]);
        rotate(m, rx, 1, 2); rotate(m, ry, 2, 0); rotate(m, rz, 0, 1);
        translate(m, -p[0], -p[1], -p[2]);
    }

    private void rotate(float[] m, float angle, int a, int b) {
        float c = MathHelper.cos(angle), s = MathHelper.sin(angle);
        for (int row = 0; row < 4; row++) {
            float av = m[a*4+row], bv = m[b*4+row];
            m[a*4+row] = av*c + bv*s; m[b*4+row] = -av*s + bv*c;
        }
    }

    public static void identity(float[] m) {
        java.util.Arrays.fill(m, 0); m[0] = m[5] = m[10] = m[15] = 1;
    }

    public static void translate(float[] m, float x, float y, float z) {
        for (int row = 0; row < 4; row++) m[12+row] += m[row]*x + m[4+row]*y + m[8+row]*z;
    }

    public static void point(float[] m, float x, float y, float z, float[] out) {
        for (int row = 0; row < 3; row++) out[row] = m[row]*x + m[4+row]*y + m[8+row]*z + m[12+row];
    }

    public static float interpolateAngle(float prev, float current, float partial) {
        return prev + MathHelper.wrapAngleTo180_float(current - prev) * partial;
    }
}
