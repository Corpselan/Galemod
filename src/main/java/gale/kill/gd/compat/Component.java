package gale.kill.gd.compat;
import java.util.*; import java.util.function.UnaryOperator;
public class Component {
 protected String text; protected Style style=Style.EMPTY; protected final List<Component> siblings=new ArrayList<>();
 protected Component(String s){text=s;} public static MutableComponent literal(String s){return new MutableComponent(s);}
 public static MutableComponent empty(){return literal("");}
 public static MutableComponent translatable(String key,Object... args){return literal(net.minecraft.client.resources.I18n.format(key,args));}
 public String getString(){StringBuilder s=new StringBuilder(text);for(Component c:siblings)s.append(c.getString());return s.toString();}
 public List<Component> getSiblings(){return siblings;} public Style getStyle(){return style;}
 public MutableComponent copy(){MutableComponent c=new MutableComponent(text);c.style=style;for(Component s:siblings)c.siblings.add(s.copy());return c;}
 public String formatted(){StringBuilder s=new StringBuilder(style.isBold()?"§l":"").append(text);for(Component c:siblings)s.append(c.formatted());return s.toString();}
 public MutableComponent withStyle(UnaryOperator<Style> f){MutableComponent c=copy();c.style=f.apply(style);return c;}
}
