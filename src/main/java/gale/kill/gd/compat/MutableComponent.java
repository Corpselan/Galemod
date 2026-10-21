package gale.kill.gd.compat;
import java.util.function.UnaryOperator;
public final class MutableComponent extends Component {
 public MutableComponent(String s){super(s);} public MutableComponent append(String s){siblings.add(Component.literal(s));return this;}
 public MutableComponent append(Component c){siblings.add(c);return this;} public MutableComponent setStyle(Style s){style=s;return this;}
 public MutableComponent withStyle(UnaryOperator<Style> f){style=f.apply(style);return this;}
 public MutableComponent withStyle(Style s){return setStyle(s);}
}
