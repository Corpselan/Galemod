package gale.kill.gd.client.render;
public final class ScreenAnchor {public static final String DEFAULT="bottom_center";
 public static int resolveCenterX(String a,int offset,int width){return Math.round((a.endsWith("left")?0:a.endsWith("right")?1:.5f)*width)+offset;}
 public static int resolveCenterY(String a,int offset,int height){return Math.round((a.startsWith("top")?0:a.startsWith("bottom")?1:.5f)*height)-offset;}
}
