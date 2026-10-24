package gale.devtest;

import java.io.File;
import java.net.URL;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;

/** Exercises real LaunchWrapper CodeSource formats without starting Forge or Minecraft. */
public final class ForgeOriginTest {
    private ForgeOriginTest() { }
    private static void verify(File source, boolean development, String protocol) throws Exception {
        Launch.blackboard = new HashMap<>();
        Launch.blackboard.put("fml.deobfuscatedEnvironment", development);
        try (LaunchClassLoader loader = new LaunchClassLoader(new URL[]{source.toURI().toURL()})) {
            Class<?> integrity = loader.loadClass("gale.security.ReleaseIntegrity");
            URL origin = integrity.getProtectionDomain().getCodeSource().getLocation();
            if (!protocol.equals(origin.getProtocol())) throw new AssertionError("Unexpected LaunchWrapper origin: " + origin);
            integrity.getMethod("requireVerified").invoke(null);
            integrity.getMethod("assertTrustedClass", Class.class).invoke(null, loader.loadClass("gale.security.WatermarkIdentity"));
            integrity.getMethod("assertTrustedClass", Class.class).invoke(null, loader.loadClass("gale.security.WatermarkAuthorization"));
            integrity.getMethod("checkBackgroundNow").invoke(null);
        } catch (InvocationTargetException ex) {
            throw new SecurityException("Real LaunchWrapper verification failed for " + source, ex.getCause());
        }
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected compiled class directory and final release JAR");
        verify(new File(args[0]), true, "file");
        verify(new File(args[1]), false, "jar");
        System.out.println("Real LaunchWrapper development and packaged verification passed; Minecraft was not started.");
    }
}
