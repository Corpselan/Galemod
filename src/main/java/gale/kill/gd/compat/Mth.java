package gale.kill.gd.compat;
public final class Mth { public static final float TWO_PI=(float)(Math.PI*2);
 public static float clamp(float v,float a,float b){return Math.max(a,Math.min(b,v));}
 public static int clamp(int v,int a,int b){return Math.max(a,Math.min(b,v));}
 public static double clamp(double v,double a,double b){return Math.max(a,Math.min(b,v));}
 public static float lerp(float p,float a,float b){return a+p*(b-a);}
 public static double lerp(double p,double a,double b){return a+p*(b-a);}
 public static float sin(float v){return (float)Math.sin(v);} public static float cos(float v){return (float)Math.cos(v);}
 public static int floor(double v){return (int)Math.floor(v);} public static int ceil(float v){return (int)Math.ceil(v);}
 public static float sqrt(float v){return (float)Math.sqrt(v);}
}
