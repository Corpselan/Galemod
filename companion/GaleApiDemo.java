// GaleApiDemo: a few things the Gale mod adds to B4 scripts (all of them: galemod docs/SCRIPTS.md).
// Needs the Gale mod; without it the script does nothing.
// - Hit log: B4 scripts cannot read entity status packets (S19); Gale gives every packet's fields
// - Reach: changes what this tick's click hits
// - Blink on B: holds your movement packets while B is down, sends them all when you let go
// - Notes on N: a screen of the script's own, saved to a file

Map<String, Object> gale;
int linkRetry;
boolean blinking, reachHooked;
ConcurrentHashMap<Integer, Integer> hurt = new ConcurrentHashMap<>();

void onLoad() {
    modules.registerDescription("Gale API demo");
    modules.registerButton("Hit log", true);
    modules.registerButton("Reach", false);
    modules.registerSlider("Reach distance", " blocks", 3.3, 3, 6, 0.1);
    modules.registerButton("Blink on B", true);
    modules.registerButton("Notes on N", true);
}

void onEnable() {
    linkRetry = 0;
}

void onDisable() {
    if (gale != null) {
        gale.put("off", new Object[]{ getScriptName() });
        if (blinking) gale.put("packet.hold", new Object[]{ "out", false });
    }
    blinking = false;
    reachHooked = false;
}

void onPreUpdate() {
    if (gale == null) {
        link();
        return;
    }
    // the hit log: entities hurt (S19, status 2) that you hit in the last 10 ticks
    for (Integer id : hurt.keySet()) {
        hurt.remove(id);
        Object ticks = gale.get("entity.attackedTicks " + id);
        Entity e = world.getEntityById(id);
        if (e != null && ticks instanceof Number && ((Number) ticks).intValue() < 10) client.print("&7hit &f" + e.getName());
    }
    // listen to "pick" only while Reach is on: Gale takes over the player controller while someone listens
    boolean reach = modules.getButton(getScriptName(), "Reach");
    if (reach && !reachHooked) {
        gale.put("on", new Object[]{ "pick", getScriptName(), (Comparator<Object>) (n, a) -> {
            Object[] args = (Object[]) a;
            if ("entity".equals(args[0])) return 0;
            Object hit = gale.put("pick.entity", new Object[]{ modules.getSlider(getScriptName(), "Reach distance") });
            if (hit != null) {
                args[0] = "entity";
                args[1] = ((Object[]) hit)[0];
                args[2] = ((Object[]) hit)[1];
            }
            return 0;
        } });
        reachHooked = true;
    } else if (!reach && reachHooked) {
        gale.put("off", new Object[]{ "pick", getScriptName() });
        reachHooked = false;
    }
}

void link() {
    if (linkRetry-- > 0) return;
    linkRetry = 100;
    client.runForgeCommand("gale link");
    Object g = bridge.get("gale");
    if (!(g instanceof Map)) return;
    gale = (Map<String, Object>) g;
    reachHooked = false;
    // network thread: only note it here, print on the game thread in onPreUpdate
    gale.put("on", new Object[]{ "packetIn", getScriptName(), (Comparator<Object>) (n, a) -> {
        Object[] args = (Object[]) a;
        if (!"S19PacketEntityStatus".equals(args[1]) || !modules.getButton(getScriptName(), "Hit log")) return 0;
        Map<String, Object> f = (Map<String, Object>) gale.put("packet.fields", new Object[]{ args[0] });
        if (f != null && Integer.valueOf(2).equals(f.get("logicOpcode"))) hurt.put((Integer) f.get("entityId"), 1);
        return 0;
    } });
    gale.put("on", new Object[]{ "screenButton", getScriptName(), (Comparator<Object>) (n, a) -> {
        Object[] args = (Object[]) a;
        if ("notes".equals(args[0]) && "save".equals(args[1])) {
            gale.put("file.write", new Object[]{ "notes.txt", gale.get("screen.text notes text") });
            gale.put("screen.close", new Object[]{ "notes" });
            client.print("&aNotes saved");
        }
        return 0;
    } });
    gale.put("on", new Object[]{ "chatClick", getScriptName(), (Comparator<Object>) (n, a) -> {
        if ("help".equals(((Object[]) a)[0])) {
            List<String> all = (List<String>) gale.get("help");
            client.print("&7Gale " + gale.get("version") + ": " + all.size() + " functions, gale.get(\"help name\") explains one");
        }
        return 0;
    } });
    gale.put("chat.link", new Object[]{ "&b[GaleApiDemo] &7linked with Gale, &nclick here", "callback", "help", "What the Gale API has" });
}

void onKey(String key, int keyCode, boolean pressed, boolean inGui) {
    if (gale == null || inGui) return;
    if (key.equals("B") && modules.getButton(getScriptName(), "Blink on B")) {
        if (pressed && !blinking) {
            blinking = Boolean.TRUE.equals(gale.put("packet.hold", new Object[]{ "out", true, "C03,C04,C05,C06" }));
        } else if (!pressed && blinking) {
            gale.put("packet.hold", new Object[]{ "out", false });
            blinking = false;
        }
    }
    if (key.equals("N") && pressed && modules.getButton(getScriptName(), "Notes on N")) openNotes();
}

void openNotes() {
    Object saved = gale.put("file.read", new Object[]{ "notes.txt" });
    int[] size = (int[]) gale.get("screen.size");
    if (size == null) return;
    int x = size[0] / 2 - 100;
    gale.put("screen.field", new Object[]{ "notes", "text", x, 60, 200, 20, saved == null ? "" : saved });
    gale.put("screen.button", new Object[]{ "notes", "save", x, 90, 200, 20, "Save" });
    gale.put("screen.open", new Object[]{ "notes", "&eNotes" });
}
