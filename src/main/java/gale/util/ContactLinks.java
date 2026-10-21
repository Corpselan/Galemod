package gale.util;

import java.awt.Desktop;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.net.URI;

/** Community links and the browser-to-clipboard fallback. */
public final class ContactLinks {
    public static final String QQ = "https://qm.qq.com/q/jjxZQuOUMg";
    public static final String DISCORD = "https://discord.gg/abHKstMPz";

    public enum Result { OPENED, COPIED, FAILED }

    @FunctionalInterface
    public interface LinkAction {
        void run(String url) throws Exception;
    }

    private ContactLinks() { }

    public static Result open(String url) {
        return open(url, link -> {
            if (!Desktop.isDesktopSupported()) throw new UnsupportedOperationException("No desktop browser");
            Desktop desktop = Desktop.getDesktop();
            if (!desktop.isSupported(Desktop.Action.BROWSE)) throw new UnsupportedOperationException("No browser handler");
            desktop.browse(new URI(link));
        }, link -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(link), null));
    }

    /** Platform actions are supplied separately so failure paths can be checked without opening a real browser. */
    public static Result open(String url, LinkAction browse, LinkAction copy) {
        try {
            browse.run(url);
            return Result.OPENED;
        } catch (Exception unavailable) {
            try {
                copy.run(url);
                return Result.COPIED;
            } catch (Exception clipboardUnavailable) {
                return Result.FAILED;
            }
        }
    }
}
