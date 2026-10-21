package gale.kill.gd.compat;
public final class TextColor {private final int value; private TextColor(int v){value=v;}
 public static TextColor fromRgb(int v){return new TextColor(v);} public int getValue(){return value;}}
