package gale.kill.gd.compat;
public final class Style {
 public static final Style EMPTY=new Style(null,false); private final TextColor color; private final boolean bold;
 private Style(TextColor c,boolean b){color=c;bold=b;} public TextColor getColor(){return color;}
 public Style withColor(TextColor c){return new Style(c,bold);} public Style withColor(int c){return withColor(TextColor.fromRgb(c));}
 public Style withBold(boolean b){return new Style(color,b);} public boolean isBold(){return bold;}
}
