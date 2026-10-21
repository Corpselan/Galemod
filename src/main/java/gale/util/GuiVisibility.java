package gale.util;

/** Build edition and shared GUI-only visibility rules. The watermark uses the same number. */
public final class GuiVisibility {
    /** Change only this number: 1 = show all rules, 2 = hide level-2 rules, 3 = hide every marked rule. */
    public static final int LEVEL = 3;

    private GuiVisibility() { }

    public static boolean hideFrom(int firstHiddenLevel) {
        return firstHiddenLevel != 0 && LEVEL >= firstHiddenLevel;
    }

    /** Compatibility for an existing isHide() override returning true; #1 still reveals it. */
    public static boolean isHidden(boolean marked) { return LEVEL >= 2 && marked; }

    public static int checkLevel(int level) {
        if (level != 0 && level != 2 && level != 3)
            throw new IllegalArgumentException("Hidden level must be 0 (off), 2 or 3");
        return level;
    }
}
