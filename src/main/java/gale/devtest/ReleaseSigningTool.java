package gale.devtest;

import gale.security.ReleaseIntegrity;
import gale.security.ProtectionManifest;
import gale.security.WatermarkIdentity;
import gale.security.WatermarkAuthorization;
import java.security.PrivateKey;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.*;
import java.util.jar.*;

/** Build-time tool, excluded from release jars. Passwords stay in the external local signing directory. */
public final class ReleaseSigningTool {
    private ReleaseSigningTool() { }
    public static void main(String[] args) throws Exception {
        if (args.length == 3 && "--authorize-protection".equals(args[0])) {
            ProtectionManifest.authorize(new File(args[1]), signingKey(Paths.get(args[2])));
            System.out.println("Author protection source permission updated; commit the public record and signature"); return;
        }
        if (args.length == 2 && "--check-protection".equals(args[0])) {
            ProtectionManifest.verifySources(new File(args[1]));
            System.out.println("Author-approved protection sources verified; no private key needed"); return;
        }
        if (args.length == 3 && "--authorize-watermark".equals(args[0])) {
            WatermarkAuthorization.authorize(new File(args[1]), signingKey(Paths.get(args[2])));
            System.out.println("Author watermark permission updated; public record and signature may be committed"); return;
        }
        if (args.length == 2 && ("--check-watermark".equals(args[0]) || "--dev-check".equals(args[0]))) {
            Properties approved = WatermarkAuthorization.directory(new File(args[1]));
            WatermarkAuthorization.requireCurrent(approved);
            if (!approved.getProperty("user").equals(gale.ui.raven.RavenWatermark.USER)
                || !approved.getProperty("edition").equals(gale.ui.raven.RavenWatermark.EDITION)
                || !approved.getProperty("developer").equals(gale.ui.raven.RavenWatermark.DEV))
                throw new SecurityException("Raven watermark aliases were changed without author permission");
            System.out.println("Author-approved watermark verified; no private key needed"); return;
        }
        if (args.length == 1) {
            ReleaseIntegrity.Identity id = ReleaseIntegrity.verifyJar(new File(args[0]));
            System.out.println("Verified watermark permission and archive: " + id.user + " #" + id.userId + " / " + id.edition + " / " + id.developer);
            return;
        }
        if (args.length != 3) throw new IllegalArgumentException("Expected unsigned.jar output.jar signing-directory, or one signed.jar to verify");
        Path input = Paths.get(args[0]), output = Paths.get(args[1]), home = Paths.get(args[2]);
        if (input.toAbsolutePath().equals(output.toAbsolutePath())) throw new IllegalArgumentException("Separate unsigned and signed outputs are required");
        Path store = home.resolve("galemod-release.p12"), password = home.resolve("store-password.txt");
        boolean owner = Files.isRegularFile(store) && Files.isRegularFile(password);
        if (Files.exists(store) != Files.exists(password)) throw new IllegalStateException("Incomplete owner signing directory");
        if (owner) signingKey(home);
        Path candidate = output.resolveSibling(output.getFileName().toString() + ".signing-tmp");
        Files.createDirectories(output.toAbsolutePath().getParent());
        try {
            try (JarFile source = new JarFile(input.toFile()); JarOutputStream target = new JarOutputStream(Files.newOutputStream(candidate))) {
                Set<String> names = new HashSet<>(); byte[] buffer = new byte[32768];
                Enumeration<JarEntry> entries = source.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement(); String name = entry.getName(), upper = name.toUpperCase(Locale.ROOT);
                    if (!names.add(name)) throw new SecurityException("Duplicate archive entry: " + name);
                    if (name.equals(ReleaseIntegrity.BRAND_ENTRY) || upper.matches("META-INF/[^/]+\\.(SF|RSA|DSA|EC)")) continue;
                    JarEntry copy = new JarEntry(name); copy.setTime(entry.getTime()); target.putNextEntry(copy);
                    if (!entry.isDirectory()) try (InputStream in = source.getInputStream(entry)) {
                        int n; while ((n = in.read(buffer)) != -1) target.write(buffer, 0, n);
                    }
                    target.closeEntry();
                }
                target.putNextEntry(new JarEntry(ReleaseIntegrity.BRAND_ENTRY));
                WatermarkIdentity.properties().store(target, "Official GaleMod watermark identity; covered by the release signature");
                target.closeEntry();
            }
            addContentDigests(candidate, owner);
            if (owner) sign(candidate, store, password);
            ReleaseIntegrity.verifyJar(candidate.toFile());
            verifyPackagedRuntime(candidate);
            Files.move(candidate, output, StandardCopyOption.REPLACE_EXISTING);
            System.out.println((owner ? "Owner-signed release verified: " : "Community build with author-approved watermark verified: ") + output.getFileName());
        } finally { Files.deleteIfExists(candidate); }
    }

    private static void verifyPackagedRuntime(Path jar) throws Exception {
        try (java.net.URLClassLoader loader = new java.net.URLClassLoader(new java.net.URL[]{jar.toUri().toURL()}, null)) {
            Class<?> verifier = loader.loadClass("gale.security.ReleaseIntegrity");
            verifier.getMethod("requireVerified").invoke(null);
            verifier.getMethod("checkBackgroundNow").invoke(null);
        } catch (java.lang.reflect.InvocationTargetException ex) {
            throw new SecurityException("Packaged verification runtime did not pass its isolated Java 8 startup check", ex.getCause());
        }
    }

    private static void addContentDigests(Path jar, boolean owner) throws Exception {
        Path digested = jar.resolveSibling(jar.getFileName().toString() + ".digests");
        try {
            try (JarFile input = new JarFile(jar.toFile())) {
                Manifest manifest = input.getManifest(); if (manifest == null) manifest = new Manifest();
                manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
                manifest.getMainAttributes().putValue("Gale-Build-Type", owner ? "owner" : "community"); manifest.getEntries().clear();
                Enumeration<JarEntry> entries = input.entries(); byte[] buffer = new byte[32768];
                while (entries.hasMoreElements()) {
                    JarEntry e = entries.nextElement(); if (e.isDirectory() || e.getName().equals("META-INF/MANIFEST.MF")) continue;
                    java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
                    try (InputStream in = input.getInputStream(e)) { int n; while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n); }
                    Attributes attrs = new Attributes(); attrs.putValue("SHA-256-Digest", Base64.getEncoder().encodeToString(digest.digest()));
                    manifest.getEntries().put(e.getName(), attrs);
                }
                try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(digested), manifest)) {
                    entries = input.entries();
                    while (entries.hasMoreElements()) {
                        JarEntry e = entries.nextElement(); if (e.getName().equals("META-INF/MANIFEST.MF")) continue;
                        JarEntry copy = new JarEntry(e.getName()); copy.setTime(e.getTime()); out.putNextEntry(copy);
                        if (!e.isDirectory()) try (InputStream in = input.getInputStream(e)) { int n; while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n); }
                        out.closeEntry();
                    }
                }
            }
            Files.move(digested, jar, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(digested); }
    }

    private static PrivateKey signingKey(Path home) throws Exception {
        Path store = home.resolve("galemod-release.p12"), password = home.resolve("store-password.txt");
        if (!Files.isRegularFile(store) || !Files.isRegularFile(password))
            throw new IllegalStateException("Owner signing key is missing; restore the external signing directory");
        char[] pass = new String(Files.readAllBytes(password), StandardCharsets.UTF_8).trim().toCharArray();
        try {
            KeyStore keys = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(store)) { keys.load(in, pass); }
            Certificate cert = keys.getCertificate("galedev");
            if (cert == null || !ReleaseIntegrity.CERTIFICATE_SHA256.equals(ReleaseIntegrity.sha256(cert.getEncoded())))
                throw new SecurityException("This signing key does not match the pinned official certificate");
            return (PrivateKey) keys.getKey("galedev", pass);
        } finally { Arrays.fill(pass, '\0'); }
    }

    static void sign(Path jar, Path store, Path password) throws Exception {
        File jdk = new File(System.getProperty("java.home"));
        if (jdk.getName().equalsIgnoreCase("jre")) jdk = jdk.getParentFile();
        String executable = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "jarsigner.exe" : "jarsigner";
        List<String> command = Arrays.asList(new File(jdk, "bin/" + executable).getAbsolutePath(),
            "-keystore", store.toString(), "-storetype", "PKCS12", "-storepass:file", password.toString(),
            "-sigalg", "SHA256withRSA", "-digestalg", "SHA-256", "-sigfile", "GALEDEV", jar.toString(), "galedev");
        int status = new ProcessBuilder(command).inheritIO().start().waitFor();
        if (status != 0) throw new IllegalStateException("Release signing failed with status " + status);
    }
}
