package gale.b4script;

import gale.Gale;
import gale.b4port.B4Values;
import gale.bedwars.BedTracker;
import gale.bridge.Bridge;
import gale.bridge.External;
import gale.module.Category;
import gale.module.Module;
import gale.module.setting.BoolSetting;
import gale.module.setting.ModeSetting;
import gale.module.setting.NumberSetting;
import gale.module.setting.Setting;
import gale.util.ChatUtil;
import gale.util.Friends;
import gale.util.Mc;
import gale.util.PacketUtil;
import gale.util.RoundRect;
import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.network.Packet;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What B4's script API asks of B4 itself (OpenB4's ScriptProtected), answered by Gale: modules and settings are Gale's
 * (scripts' own and Gale's modules), with B4's modules seen through GaleLink (on/off); the KillAura / AimAssist /
 * Backtrack / Lag Range targets, Scaffold / Tower and BedAura come from Gale's ports of those modules (gale.b4port), or
 * from B4's own as GaleLink reports them while B4 runs them; friends are Gale's; rotations, drawing and Hypixel come
 * from {@link B4Helpers}. There is no B4 account.
 */
public class ScriptProtected implements Defaults {
    private static final Set<String> ENEMIES = ConcurrentHashMap.newKeySet();

    // ------------------------------------------------------------------ modules and settings

    /** A module by name: a script's or one of Gale's (ignoring case and spaces, as Gale does). */
    private static Module module(String name) { return name == null ? null : Gale.modules().get(name); }

    public static void registerButton(String string, boolean bl) { ScriptManager.pendingSettings.add(new Object[]{ 1, string, bl }); }

    public static void registerSlider(String string, String string2, double d, double d2, double d3, double d4) {
        ScriptManager.pendingSettings.add(new Object[]{ 2, string, string2, d, d2, d3, d4 });
    }

    public static void registerSlider(String string, String string2, int n, String[] stringArray) {
        ScriptManager.pendingSettings.add(new Object[]{ 3, string, string2, n, stringArray });
    }

    public static void registerDescription(String string) { ScriptManager.pendingSettings.add(new Object[]{ 0, string }); }

    public static String getScriptName() { return ScriptInvoker.currentScriptName(); }

    public static boolean getButton(String string, String string2) {
        Module m = module(string);
        Setting<?> s = m == null ? null : ScriptManager.setting(m, string2);
        return s instanceof BoolSetting && ((BoolSetting) s).get();
    }

    public static void setButton(String string, String string2, boolean bl) {
        Module m = module(string);
        Setting<?> s = m == null ? null : ScriptManager.setting(m, string2);
        if (s instanceof BoolSetting) ((BoolSetting) s).set(bl);
    }

    /** {value, shown text, min, max, step}: a mode slider's value is the option's index. */
    public static Object[] getSlider(String string, String string2) {
        Module m = module(string);
        Setting<?> s = m == null ? null : ScriptManager.setting(m, string2);
        if (s instanceof NumberSetting) {
            NumberSetting n = (NumberSetting) s;
            return new Object[]{ n.get(), format(n.get()), n.min, n.max, n.step };
        }
        if (s instanceof ModeSetting) {
            ModeSetting md = (ModeSetting) s;
            return new Object[]{ (double) md.index(), md.get(), 0.0, (double) (md.getModes().size() - 1), 1.0 };
        }
        return null;
    }

    public static void setSlider(String string, String string2, double d) {
        Module m = module(string);
        Setting<?> s = m == null ? null : ScriptManager.setting(m, string2);
        if (s instanceof NumberSetting) ((NumberSetting) s).set(d);
        else if (s instanceof ModeSetting) {
            ModeSetting md = (ModeSetting) s;
            int i = (int) Math.round(d);
            if (i >= 0 && i < md.getModes().size()) md.set(md.getModes().get(i));
        }
    }

    public static Map<String, Object> getSettings(String string) {
        Module m = module(string);
        if (m == null) return null;
        Map<String, Object> out = new HashMap<>();
        for (Setting<?> s : m.getSettings()) {
            Object value = null;
            if (s instanceof NumberSetting) value = ((NumberSetting) s).get();
            else if (s instanceof ModeSetting) value = (double) ((ModeSetting) s).index();
            else if (s instanceof BoolSetting) value = ((BoolSetting) s).get();
            out.put(s.getName(), value);
        }
        return out;
    }

    /** On in Gale, or (GaleLink) on in B4 (also when Gale has a module of that name that gives way to B4's). */
    public static boolean isEnabled(String string) {
        Module m = module(string);
        if (m != null && m.isEnabled()) return true;
        return External.isEnabled("b4", string);
    }

    public static void enable(String string) {
        Module m = module(string);
        if (m != null) {
            if (!m.isEnabled()) m.setEnabled(true);
        } else Bridge.request("b4", string, true);
    }

    public static void disable(String string) {
        Module m = module(string);
        if (m != null) {
            if (m.isEnabled()) m.setEnabled(false);
        } else Bridge.request("b4", string, false);
    }

    public static boolean isHidden(String string) {
        Module m = module(string);
        return m != null && m.isHidden();
    }

    public static Map<String, List<String>> getCategories() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Category c : Category.values()) {
            List<String> names = new ArrayList<>();
            for (Module m : Gale.modules().in(c)) names.add(m.getName());
            if (!names.isEmpty()) out.put(c.display.toLowerCase(Locale.ROOT), names);
        }
        return Collections.unmodifiableMap(out);
    }

    private static String format(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e9) return String.valueOf((long) d);
        String s = String.format(Locale.ROOT, "%.3f", d);
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    // ------------------------------------------------------------------ events between scripts

    public static void invoke(String string, Object... objectArray) {
        ScriptEvent event = ScriptEvent.byName(string);
        if (event != null) ScriptInvoker.dispatch(event, objectArray);
    }

    public static int invokeBoolean(String string, Object... objectArray) {
        ScriptEvent event = ScriptEvent.byName(string);
        return event == null ? -1 : ScriptInvoker.dispatchBoolean(event, objectArray);
    }

    public static float[] invokeFloatArray(String string, Object... objectArray) {
        ScriptEvent event = ScriptEvent.byName(string);
        return event == null ? null : ScriptInvoker.dispatchFloatArray(event, objectArray);
    }

    // ------------------------------------------------------------------ B4's own modules

    // Gale's ports of these modules (gale.b4port), or B4's own as GaleLink reports them while B4 runs them

    public static Entity getKillAuraTarget() { return B4Values.target("KillAura"); }

    public static Entity getAimAssistTarget() { return B4Values.target("AimAssist"); }

    public static Entity getBacktrackTarget() { return B4Values.target("Backtrack"); }

    public static Entity getLagRangeTarget() { return B4Values.target("Lag Range"); }

    public static boolean isScaffolding() { return B4Values.scaffolding(); }

    public static boolean isTowering() { return B4Values.towering(); }

    /** Your own bed: Bed Wars' (Gale's or B4's), else Gale's Bed Wars tracker (one of its two blocks). */
    public static BlockPos getWhitelistedBedPos() {
        BlockPos own = B4Values.ownBed();
        if (own != null) return own;
        BlockPos[] bed = BedTracker.ownBed();
        return bed == null ? null : bed[0];
    }

    public static BlockPos getBedAuraPosition() { return B4Values.bedAuraPosition(); }

    /** BedAura's {progress, vanilla progress}, zeros when it is not breaking. */
    public static float[] getBedAuraProgress() { return B4Values.bedAuraProgress(); }

    public static String getMode() { return B4Helpers.hypixelState(); }

    public static boolean runRavenCommand(String string) { return false; }

    public static String getUser() { return mc.getSession().getUsername(); }

    public static String getVersion() { return "Gale " + Gale.VERSION; }

    public static int getUID() { return -1; }

    public static String debug(String string) { throw new UnsupportedOperationException("client.debug is not available"); }

    public static int getThemeRGB(String string) { return B4Helpers.themeRGB(string); }

    // ------------------------------------------------------------------ friends and enemies

    public static void addFriend(String string) { Friends.add(string); }

    public static void removeFriend(String string) { Friends.remove(string); }

    public static boolean isFriend(String string) { return Friends.is(string); }

    public static void addEnemy(String string) { if (string != null) ENEMIES.add(string.toLowerCase(Locale.ROOT)); }

    public static void removeEnemy(String string) { if (string != null) ENEMIES.remove(string.toLowerCase(Locale.ROOT)); }

    public static boolean isEnemy(String string) { return string != null && ENEMIES.contains(string.toLowerCase(Locale.ROOT)); }

    // ------------------------------------------------------------------ the player

    public static boolean canSeeEntity(Entity entity) { return B4Helpers.canSee(entity); }

    public static boolean isHoldingWeapon(EntityLivingBase entityLivingBase) { return B4Helpers.holdingWeapon(entityLivingBase); }

    public static boolean isConsuming(EntityPlayer entityPlayer) { return B4Helpers.isEating(entityPlayer); }

    public static double distanceToGround(Entity entity) { return B4Helpers.heightAboveGround(entity); }

    public static boolean isOnEdge(Entity entity) { return B4Helpers.isAirBelow(entity); }

    public static String getDirection() { return B4Helpers.direction().name(); }

    public static String getServerDirection(float f) { return B4Helpers.direction(f).name(); }

    public static boolean isDiagonal() { return B4Helpers.isDiagonal(); }

    public static boolean isFacingDiagonal() { return B4Helpers.diagonalYaw(); }

    public static void setSpeed(double d) { B4Helpers.strafe(d); }

    public static void setSpeedForward(double d) { B4Helpers.setSpeedForward(d); }

    public static void setTimer(float f) { Mc.setTimerSpeed(f); }

    public static float[] getRotationsToEntity(Entity entity) { return B4Helpers.toEntityFixed(entity); }

    public static float[] getRotationsToPos(double d, double d2, double d3) { return B4Helpers.toPositionFixed(d, d2, d3); }

    public static float[] getRotationsToBlock(BlockPos blockPos, EnumFacing enumFacing) { return B4Helpers.toBlockFixed(blockPos, enumFacing); }

    public static void leftClick() { B4Helpers.clickMouse(); }

    public static void rightClick() { B4Helpers.rightClickMouse(); }

    public static void attack(Entity entity) { B4Helpers.attack(entity); }

    public static void pling() { B4Helpers.ping(); }

    public static boolean canInteract(Block block) { return B4Helpers.isInteractable(block); }

    public static List<String> getBookContents() { return B4Helpers.bookPages(); }

    public static String getScreen() { return B4Helpers.screenName(mc.currentScreen); }

    public static void guiClick(int n, int n2, int n3) {
        mc.playerController.windowClick(mc.thePlayer.openContainer.windowId, n, n2, n3, mc.thePlayer);
    }

    public static List<String> getSidebarLines() { return B4Helpers.sidebar(); }

    // ------------------------------------------------------------------ packets and chat

    public static void sendPacket(Packet packet) { PacketUtil.send(packet); }

    /** Past Gale's packet listeners (so past the scripts' onPacketSent too). */
    public static void sendPacketNoEvent(Packet packet) { PacketUtil.sendNoEvent(packet); }

    public static String stripString(String string) { return ChatUtil.strip(string); }

    public static void sendMessageNoPrefix(Object object) { ChatUtil.raw(String.valueOf(object)); }

    // ------------------------------------------------------------------ drawing

    public static void roundedRect(float f, float f2, float f3, float f4, float f5, int n) { RoundRect.draw(f, f2, f3, f4, f5, n); }

    public static void renderBlock(int n, int n2, int n3, int n4, boolean bl, boolean bl2) { B4Helpers.blockBox(n, n2, n3, n4, bl, bl2); }

    public static void renderItem(ItemStack itemStack, float f, float f2) { B4Helpers.drawItem(itemStack, f, f2); }

    public static void prepareBlur() { Blur.get().startStencil(); }

    public static void applyBlur(int n, float f) { Blur.get().render(n, f); }

    public static void prepareBloom() { GlowShader.get().startCapture(); }

    public static void applyBloom(int n, float f) { GlowShader.get().render(n, f); }
}
