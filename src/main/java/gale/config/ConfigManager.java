package gale.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import gale.Gale;
import gale.module.Module;
import gale.module.setting.Setting;
import gale.util.Friends;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Saves and loads module states, key binds, settings and friends as JSON in .minecraft/galemod/. The main config is
 * config.json and is saved by itself a second after anything changes; the selected named profile is updated along with config.json.
 */
public final class ConfigManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private boolean loaded, dirty;
    private long dirtySince;
    private long lastAttempt;
    private String activeProfile, lastError;
    private boolean applying;

    public synchronized String activeProfile() { return activeProfile; }
    public synchronized String lastError() { return lastError == null ? "操作失败" : lastError; }
    public synchronized boolean isDirty() { return dirty; }

    private static String profileName(String name) {
        if (name == null || name.equalsIgnoreCase("config")) return null;
        String n = name.trim();
        if (n.isEmpty() || n.length() > 48 || n.equals(".") || n.equals("..") || n.endsWith(".")
            || n.matches(".*[\\\\/:*?\"<>|\\p{Cntrl}].*")
            || n.matches("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\\..*)?"))
            throw new IllegalArgumentException("名称须为 1～48 个字符，不能包含路径符号或保留名称");
        if (n.equalsIgnoreCase("config")) return null;
        return n;
    }

    public File dir() {
        File d = new File(Minecraft.getMinecraft().mcDataDir, "galemod");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    private File file(String profile) {
        String name = profileName(profile);
        return name == null ? new File(dir(), "config.json") : new File(new File(dir(), "profiles"), name + ".json");
    }

    /** Something changed: saved about a second later (after the first load). */
    public synchronized void markDirty() {
        if (applying) return;
        if (!dirty) dirtySince = System.currentTimeMillis();
        dirty = true;
    }

    /** Called every tick by the hooks. */
    public synchronized void tick() {
        long now = System.currentTimeMillis();
        if (loaded && dirty && now - dirtySince >= 1000 && now - lastAttempt >= 1000) save();
    }

    /** Loads config.json (at start). Also makes sure changes are saved when the game closes. */
    public void load() {
        loadFile("config", true);
        loaded = true;
        Runtime.getRuntime().addShutdownHook(new Thread(this::flush, "Gale config save"));
    }

    /** Applies a saved profile; false if there is none or it cannot be read. */
    public synchronized boolean load(String profile) { return loadFile(profile, false); }

    private boolean loadFile(String profile, boolean startup) {
        File f = null;
        try {
            String name = profileName(profile);
            f = file(name);
            if (!f.isFile()) { lastError = "找不到该配置文件"; return false; }
            if (!startup && dirty && !save()) return false;
            JsonObject root = new JsonParser().parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)).getAsJsonObject();
            validate(root);
            String next = name;
            if (startup && root.has("activeProfile") && root.get("activeProfile").isJsonPrimitive()) {
                try {
                    String saved = profileName(root.get("activeProfile").getAsString());
                    if (saved != null && file(saved).isFile()) next = saved;
                } catch (IllegalArgumentException ignored) { }
            }
            applying = true;
            try { apply(root); } finally { applying = false; }
            activeProfile = next;
            lastError = null;
            if (startup) dirty = false;
            else markDirty();
            return true;
        } catch (Exception e) {
            lastError = e instanceof IllegalArgumentException ? e.getMessage() : "配置读取失败，请检查 JSON 格式";
            Gale.LOG.error("Could not read {}", f, e);
            return false;
        }
    }

    private static void validate(JsonObject root) {
        if (!root.has("modules") || !root.get("modules").isJsonObject()) throw new IllegalArgumentException("配置缺少 modules 对象");
        if (root.has("friends") && !root.get("friends").isJsonArray()) throw new IllegalArgumentException("friends 必须是列表");
        for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("modules").entrySet()) {
            if (!entry.getValue().isJsonObject()) throw new IllegalArgumentException("模块配置必须是对象：" + entry.getKey());
            JsonObject module = entry.getValue().getAsJsonObject();
            if (module.has("settings") && !module.get("settings").isJsonObject()) throw new IllegalArgumentException("settings 必须是对象");
            for (String key : new String[]{"enabled", "hidden"}) if (module.has(key)
                && (!module.get(key).isJsonPrimitive() || !module.getAsJsonPrimitive(key).isBoolean()))
                throw new IllegalArgumentException(key + " 必须是 true 或 false");
            if (module.has("key")) module.get("key").getAsInt();
            if (module.has("bindMode") && (!module.get("bindMode").isJsonPrimitive()
                || !module.getAsJsonPrimitive("bindMode").isString())) throw new IllegalArgumentException("bindMode 必须是文字");
        }
        if (root.has("friends")) for (JsonElement friend : root.getAsJsonArray("friends")) {
            if (!friend.isJsonPrimitive() || !friend.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("好友名称必须是文字");
        }
    }

    private void apply(JsonObject root) {
        for (String key : pendingKeys()) pending.remove(key);
        JsonObject modules = root.has("modules") ? root.getAsJsonObject("modules") : new JsonObject();
        for (Map.Entry<String, JsonElement> e : modules.entrySet()) {
            if (!e.getValue().isJsonObject()) continue;
            Module m = Gale.modules().get(e.getKey());
            // not registered (yet): a script module that loads later, or one taken out; kept and saved as it was
            if (m == null) pending.add(e.getKey(), e.getValue());
            else applyModule(m, e.getValue().getAsJsonObject());
        }
        if (root.has("friends")) {
            List<String> names = new ArrayList<>();
            for (JsonElement n : root.getAsJsonArray("friends")) names.add(n.getAsString());
            Friends.set(names);
        }
    }

    /** Saved state of modules that are not registered now (script modules), by name. */
    private final JsonObject pending = new JsonObject();

    private List<String> pendingKeys() {
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : pending.entrySet()) keys.add(entry.getKey());
        return keys;
    }

    /** A module registered after the config was read (a script): gets what was saved for it, if anything. */
    public void applySaved(Module m) {
        if (!pending.has(m.getName())) return;
        JsonElement saved = pending.remove(m.getName());
        if (saved.isJsonObject()) applyModule(m, saved.getAsJsonObject());
    }

    /** A module about to be taken out (a script removed): its state is kept and saved for when it comes back. */
    public void keep(Module m) {
        pending.add(m.getName(), moduleJson(m));
        markDirty();
    }

    /** A module's state as it would be saved now (to carry it over to a reloaded script's new module). */
    public JsonObject snapshot(Module m) { return moduleJson(m); }

    /** Applies a state from {@link #snapshot}. */
    public void restore(Module m, JsonObject saved) {
        if (saved != null) applyModule(m, saved);
    }

    private void applyModule(Module m, JsonObject o) {
        if (o.has("settings")) {
            for (Map.Entry<String, JsonElement> s : o.getAsJsonObject("settings").entrySet()) {
                Setting<?> setting = m.getSetting(s.getKey());
                if (setting != null) {
                    try {
                        setting.fromJson(s.getValue());
                    } catch (Exception ex) {
                        Gale.LOG.warn("Bad value for {}.{}", m.getName(), s.getKey());
                    }
                }
            }
        }
        if (o.has("key")) m.setKey(o.get("key").getAsInt());
        if (o.has("bindMode")) m.setBindMode("HOLD".equals(o.get("bindMode").getAsString()) ? Module.BindMode.HOLD : Module.BindMode.TOGGLE);
        if (o.has("hidden") && !m.isAlwaysOn()) m.setHidden(o.get("hidden").getAsBoolean());
        if (o.has("enabled")) m.setEnabled(o.get("enabled").getAsBoolean());
    }

    private static JsonObject moduleJson(Module m) {
        JsonObject o = new JsonObject();
        o.addProperty("enabled", m.isEnabled());
        o.addProperty("key", m.getKey());
        o.addProperty("bindMode", m.getBindMode().name());
        o.addProperty("hidden", m.isHidden());
        JsonObject settings = new JsonObject();
        for (Setting<?> s : m.getSettings()) settings.add(s.getName(), s.toJson());
        o.add("settings", settings);
        return o;
    }

    public synchronized boolean save() {
        lastAttempt = System.currentTimeMillis();
        JsonObject state = toJson();
        boolean ok = (activeProfile == null || write(file(activeProfile), state)) && write(file(null), state);
        if (ok) dirty = false;
        return ok;
    }

    public synchronized boolean flush() { return !dirty || save(); }

    /** Creates/overwrites a named profile and makes it the one subsequent edits automatically update. */
    public synchronized boolean saveAs(String profile) {
        try {
            String name = profileName(profile);
            if (name == null) { lastError = "请填写一个新配置名称，config 为自动保存文件"; return false; }
            if (dirty && !save()) return false;
            if (!save(name)) return false;
            activeProfile = name;
            markDirty();
            return save();
        } catch (IllegalArgumentException e) { lastError = e.getMessage(); return false; }
    }

    public synchronized boolean save(String profile) {
        try {
            String name = profileName(profile);
            return name == null ? save() : write(file(name), toJson());
        } catch (IllegalArgumentException e) { lastError = e.getMessage(); return false; }
    }

    private boolean write(File f, JsonObject state) {
        try {
            f.getParentFile().mkdirs();
            File tmp = new File(f.getPath() + ".tmp");
            Files.write(tmp.toPath(), GSON.toJson(state).getBytes(StandardCharsets.UTF_8));
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
            lastError = null;
            return true;
        } catch (Exception e) {
            lastError = "保存失败，请检查配置目录是否可写";
            Gale.LOG.error("Could not save {}", f, e);
            return false;
        }
    }

    private JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("version", Gale.VERSION);
        if (activeProfile != null) root.addProperty("activeProfile", activeProfile);
        JsonObject modules = new JsonObject();
        for (Map.Entry<String, JsonElement> e : pending.entrySet()) modules.add(e.getKey(), e.getValue());
        for (Module m : Gale.modules().all()) modules.add(m.getName(), moduleJson(m));
        root.add("modules", modules);
        JsonArray friends = new JsonArray();
        for (String n : Friends.all()) friends.add(new JsonPrimitive(n));
        root.add("friends", friends);
        return root;
    }

    /** Names of the saved profiles. */
    public List<String> profiles() {
        List<String> out = new ArrayList<>();
        File[] fs = new File(dir(), "profiles").listFiles();
        if (fs != null) for (File f : fs) if (f.isFile() && f.getName().endsWith(".json")) {
            String name = f.getName().substring(0, f.getName().length() - 5);
            try { if (profileName(name) != null) out.add(name); } catch (IllegalArgumentException ignored) { }
        }
        Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out;
    }
}
