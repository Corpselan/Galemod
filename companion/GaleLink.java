// GaleLink: connects B4 with the Gale mod.
// Every few ticks it tells Gale which B4 modules are on (Gale's own modules then give way to the B4 module that does
// the same job), and it carries out what Gale asks of B4 (switching a B4 module on or off).
// Every tick it also passes what B4 itself knows: the KillAura / AimAssist / Backtrack / Lag Range targets, BedAura's
// bed and progress (Gale's Island shows it as "Breaking Bed"), whether Scaffold / Tower are placing, and your bed.
// B4's Bed Wars alerts, AntiCheat flags, /locraw answers and IRC messages go to Gale as they happen.
// Needs the Gale mod; without it the commands do nothing.

Map<String, Object> gale;
int tick, linkRetry;
boolean fetching;
String pending = "";
float bedAura = 0f;

void onLoad() {
    modules.registerDescription("Reports B4 modules to Gale");
    modules.registerSlider("Interval", " ticks", 10, 2, 40, 1);
    modules.registerButton("Apply requests", true);
    modules.registerButton("Share state", true);
    modules.registerButton("Share events", true);
    modules.registerSlider("Port", "", 25595, 1024, 65535, 1);
}

void onEnable() {
    linkRetry = 0;
}

void onPreUpdate() {
    tick++;
    if (!pending.isEmpty()) {
        apply(pending);
        pending = "";
        client.runForgeCommand("gale bridge done b4");
    }
    if (gale == null) link();
    if (modules.getButton(getScriptName(), "Share state")) {
        if (gale != null) reportState();
        else reportBedAura();
    }
    if (tick % (int) modules.getSlider(getScriptName(), "Interval") != 0) return;
    report();
    if (modules.getButton(getScriptName(), "Apply requests") && !fetching) fetchRequests();
}

void onDisable() {
    // an empty report: Gale's modules stop giving way at once
    client.runForgeCommand("gale bridge b4");
}

// "gale link" puts Gale's script API in B4's bridge; an older Gale without b4.report keeps the bridge-only path
void link() {
    if (linkRetry-- > 0) return;
    linkRetry = 100;
    client.runForgeCommand("gale link");
    Object g = bridge.get("gale");
    if (g instanceof Map && ((Map<String, Object>) g).containsKey("b4.report")) gale = (Map<String, Object>) g;
}

// b4.report: every tick, what B4's own modules are doing
void reportState() {
    Entity killAura = null, aimAssist = null, backtrack = null, lagRange = null;
    Vec3 bedAuraPos = null, ownBed = null;
    float[] progress = null;
    boolean scaffolding = false, towering = false;
    try { killAura = modules.getKillAuraTarget(); } catch (Exception e) { }
    try { aimAssist = modules.getAimAssistTarget(); } catch (Exception e) { }
    try { backtrack = modules.getBacktrackTarget(); } catch (Exception e) { }
    try { lagRange = modules.getLagRangeTarget(); } catch (Exception e) { }
    try { if (modules.isEnabled("BedAura")) { bedAuraPos = modules.getBedAuraPosition(); progress = modules.getBedAuraProgress(); } } catch (Exception e) { }
    try { scaffolding = modules.isScaffolding(); } catch (Exception e) { }
    try { towering = modules.isTowering(); } catch (Exception e) { }
    try { ownBed = modules.getWhitelistedBedPos(); } catch (Exception e) { }
    if (gale.put("b4.report", new Object[]{ killAura, aimAssist, backtrack, lagRange, bedAuraPos, progress, scaffolding, towering, ownBed }) == null) gale = null;
}

// "gale bridge set b4.bedaura 0.42": only when it changes (for a Gale without b4.report)
void reportBedAura() {
    float p = 0f;
    if (modules.isEnabled("BedAura")) {
        float[] progress = modules.getBedAuraProgress();
        if (progress != null && progress.length >= 1) p = Math.max(0f, Math.min(1f, progress[0]));
    }
    if (Math.abs(p - bedAura) < 0.005f && !(p == 0f && bedAura != 0f)) return;
    bedAura = p;
    client.runForgeCommand("gale bridge set b4.bedaura " + p);
}

void shareEvent(String kind, String text, Entity player) {
    if (gale == null || !modules.getButton(getScriptName(), "Share events")) return;
    gale.put("b4.event", new Object[]{ kind, text, player });
}

void onBedWarsAlert(String type, Entity player) {
    shareEvent("bedwars", type, player);
}

void onAntiCheatFlag(String check, Entity player) {
    shareEvent("anticheat", check, player);
}

void onLocRaw(String json) {
    shareEvent("locraw", json, null);
}

boolean onIRCMessage(String message) {
    shareEvent("irc", message, null);
    return true;
}

// "gale bridge b4 KillAura,Hit Select,Velocity": the B4 modules and scripts that are on
void report() {
    StringBuilder on = new StringBuilder();
    Map<String, List<String>> categories = modules.getCategories();
    for (String category : categories.keySet()) {
        for (String m : categories.get(category)) {
            if (m.equals(getScriptName()) || !modules.isEnabled(m)) continue;
            if (on.length() > 0) on.append(",");
            on.append(m);
        }
    }
    client.runForgeCommand("gale bridge b4 " + on);
}

// Gale publishes "b4.request" as "Velocity:off,KillAura:on"; read it in the background, apply it on the next tick
void fetchRequests() {
    fetching = true;
    int port = (int) modules.getSlider(getScriptName(), "Port");
    client.async(() -> {
        try {
            Request r = new Request("GET", "http://127.0.0.1:" + port + "/gale/get?key=b4.request");
            r.setConnectTimeout(300);
            r.setReadTimeout(300);
            Response res = r.fetch();
            if (res != null && res.code() == 200 && res.string() != null) pending = res.string().trim();
        } finally {
            fetching = false;
        }
    });
}

void apply(String requests) {
    for (String part : requests.split(",")) {
        int colon = part.lastIndexOf(':');
        if (colon <= 0) continue;
        String module = part.substring(0, colon).trim();
        boolean on = part.substring(colon + 1).trim().equals("on");
        if (on && !modules.isEnabled(module)) modules.enable(module);
        if (!on && modules.isEnabled(module)) modules.disable(module);
    }
}
