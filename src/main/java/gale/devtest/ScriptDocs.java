package gale.devtest;

import gale.Gale;
import gale.module.Category;
import gale.module.Module;
import gale.module.impl.assist.ScriptAssist;
import gale.module.setting.Setting;
import gale.script.Functions;
import gale.script.ScriptEvents;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes the reference half of docs/SCRIPTS.md from what is registered (every function and event, by assist), so the
 * docs cannot drift from the code. The script-API self test writes it to run/SCRIPTS-reference.md.
 */
final class ScriptDocs {
    private ScriptDocs() { }

    static void write(File out) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("<!-- 下面是自动生成的（gradlew runClient -Pscripttest 写到 run/SCRIPTS-reference.md），别手改 -->\n\n");
        sb.append("## 参考\n\n");
        area(sb, "core", "基础（一直开着）", "版本、帮助、监听事件，GaleLink 报来的 B4 状态和事件", null);
        for (Module m : Gale.modules().in(Category.ASSIST)) {
            if (!(m instanceof ScriptAssist)) continue;
            area(sb, ((ScriptAssist) m).area(), m.getName(), m.getDescription(), m);
        }
        Files.write(out.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void area(StringBuilder sb, String area, String title, String description, Module module) {
        sb.append("### ").append(title).append("\n\n").append(description).append("。");
        if (module != null) {
            sb.append("开关：ClickGUI → 辅助 → ").append(module.getName());
            List<String> parts = new ArrayList<>();
            for (Setting<?> s : module.getSettings()) parts.add(s.getName() + "（" + s.getDescription() + "）");
            if (!parts.isEmpty()) sb.append("，里面还能分开关：").append(String.join("、", parts));
            sb.append("。");
        }
        sb.append("\n\n");
        List<Functions.Entry> fns = new ArrayList<>();
        for (Functions.Entry e : Functions.all()) if (in(e.area, area) && !e.name.startsWith("devtest.")) fns.add(e);
        if (!fns.isEmpty()) {
            sb.append("| 函数 | 参数 | 说明 |\n|---|---|---|\n");
            for (Functions.Entry e : fns)
                sb.append("| `").append(e.name).append("` | ").append(e.usage.isEmpty() ? "" : "`" + cell(e.usage) + "`").append(" | ").append(cell(e.doc)).append(part(e.area, area)).append(" |\n");
            sb.append("\n");
        }
        List<ScriptEvents.Kind> evs = new ArrayList<>();
        for (ScriptEvents.Kind k : ScriptEvents.all()) if (in(k.area, area)) evs.add(k);
        if (!evs.isEmpty()) {
            sb.append("| 事件 | args | 说明 |\n|---|---|---|\n");
            for (ScriptEvents.Kind k : evs)
                sb.append("| `").append(k.name).append("` | `").append(cell(k.args)).append("` | ").append(cell(k.doc)).append(part(k.area, area)).append(" |\n");
            sb.append("\n");
        }
    }

    private static boolean in(String a, String area) { return a.equals(area) || a.startsWith(area + "."); }

    /** "（分开关：Files）" for a function or event in a part of the area */
    private static String part(String a, String area) {
        if (a.equals(area)) return "";
        String sub = a.substring(area.length() + 1);
        return "（分开关：" + Character.toUpperCase(sub.charAt(0)) + sub.substring(1) + "）";
    }

    private static String cell(String s) { return s.replace("|", "\\|").replace("\n", " "); }
}
