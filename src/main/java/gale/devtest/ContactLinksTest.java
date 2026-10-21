package gale.devtest;

import gale.util.ContactLinks;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** No network, real browser or system clipboard is touched. */
public final class ContactLinksTest {
    public static void main(String[] args) {
        int passed = 0;
        for (String url : new String[]{ContactLinks.QQ, ContactLinks.DISCORD}) {
            List<String> calls = new ArrayList<>();
            ContactLinks.Result result = ContactLinks.open(url, link -> calls.add("open:" + link),
                    link -> { throw new AssertionError("Successful opening must not overwrite the clipboard"); });
            require(result == ContactLinks.Result.OPENED && calls.equals(Arrays.asList("open:" + url)), "browser success");
            passed++;

            calls.clear();
            result = ContactLinks.open(url, link -> { calls.add("open:" + link); throw new IOException("No default browser"); },
                    link -> calls.add("copy:" + link));
            require(result == ContactLinks.Result.COPIED && calls.equals(Arrays.asList("open:" + url, "copy:" + url)), "copy original URL after browser failure");
            passed++;

            result = ContactLinks.open(url, link -> { throw new UnsupportedOperationException("Browse unsupported"); },
                    link -> calls.add("unsupported:" + link));
            require(result == ContactLinks.Result.COPIED && calls.contains("unsupported:" + url), "unsupported desktop falls back");
            passed++;

            result = ContactLinks.open(url, link -> { throw new SecurityException("Blocked"); },
                    link -> { throw new IllegalStateException("Clipboard busy"); });
            require(result == ContactLinks.Result.FAILED, "do not claim a copy when clipboard also fails");
            passed++;
        }
        System.out.println("Contact links: " + passed + " passed, 0 failed");
    }

    private static void require(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
    }
}
