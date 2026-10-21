package gale.util;

import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;

/** Low-arc solution for vanilla 1.8.9's discrete air drag and gravity, without random spread. */
public final class BowBallistics {
    private static final double DRAG=(double).99f,GRAVITY=(double).05f;
    private BowBallistics(){}
    public static final class Aim {
        public final float yaw,pitch;
        public final double flightTicks;
        Aim(float yaw,float pitch,double ticks){this.yaw=yaw;this.pitch=pitch;flightTicks=ticks;}
    }
    public static Aim solve(Vec3 eye,Vec3 target,double charge){
        if(eye==null || target==null || !Double.isFinite(charge) || charge<.1)return null;
        double speed=((float)Math.min(1,charge)*2f)*1.5f;
        double yaw=Math.atan2(-(target.xCoord-eye.xCoord),target.zCoord-eye.zCoord);
        double x=0,z=0;
        // The arrow starts 0.16 blocks beside the eye, along its final launch yaw.
        for(int i=0;i<5;i++){
            float angle=(float)Math.toDegrees(yaw)/180f*(float)Math.PI;
            x=eye.xCoord-MathHelper.cos(angle)*.16f;z=eye.zCoord-MathHelper.sin(angle)*.16f;
            yaw=Math.atan2(-(target.xCoord-x),target.zCoord-z);
        }
        double d=Math.hypot(target.xCoord-x,target.zCoord-z),dy=target.yCoord-(eye.yCoord-.1);
        if(!Double.isFinite(d) || !Double.isFinite(dy) || d<.2 || d>128)return null;
        double low=Math.atan2(dy,d),high=low;
        if(low>=Math.toRadians(89))return null;
        boolean found=false;
        for(int i=1;i<=360;i++){
            high=Math.min(Math.toRadians(89),low+i*Math.toRadians(.5));
            double t=time(d,speed*Math.cos(high));
            if(Double.isFinite(t) && height(t,speed*Math.sin(high))>=dy){found=true;break;}
            if(high>=Math.toRadians(89))break;
        }
        if(!found)return null;
        for(int i=0;i<36;i++){
            double mid=(low+high)*.5,t=time(d,speed*Math.cos(mid));
            if(Double.isFinite(t) && height(t,speed*Math.sin(mid))>=dy)high=mid;else low=mid;
        }
        double theta=(low+high)*.5,t=time(d,speed*Math.cos(theta));
        return new Aim((float)Math.toDegrees(yaw),(float)-Math.toDegrees(theta),t);
    }
    private static double time(double distance,double horizontalSpeed){
        if(horizontalSpeed<=0 || distance*(1-DRAG)>=horizontalSpeed)return Double.NaN;
        double continuous=Math.log(1-distance*(1-DRAG)/horizontalSpeed)/Math.log(DRAG);
        int n=(int)Math.floor(continuous);
        if(n<0 || n>200)return Double.NaN;
        double decay=Math.pow(DRAG,n),travel=horizontalSpeed*(1-decay)/(1-DRAG);
        return n+(distance-travel)/(horizontalSpeed*decay);
    }
    private static double height(double ticks,double vy){
        int n=(int)Math.floor(ticks);double decay=Math.pow(DRAG,n),sum=(1-decay)/(1-DRAG);
        return (vy+GRAVITY/(1-DRAG))*sum-GRAVITY*n/(1-DRAG)+(ticks-n)*(vy*decay-GRAVITY*sum);
    }
}
