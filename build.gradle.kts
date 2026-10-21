plugins {
    java
    id("gg.essential.loom") version "1.7.35"
    id("dev.architectury.architectury-pack200") version "0.1.3"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "gale"
version = "2.0"

java { toolchain.languageVersion.set(JavaLanguageVersion.of(8)) }

// No mixins: everything goes through Forge events, the Netty pipeline and wrapped vanilla objects.
loom {
    forge {
        pack200Provider.set(dev.architectury.pack200.java.Pack200Adapter())
    }
    runConfigs {
        "client" {
            isIdeConfigGenerated = true
            if (project.hasProperty("guishots")) programArgs("--width", "1920", "--height", "1080")
            if (project.hasProperty("killfxtest")) programArgs("--width", "1280", "--height", "720")
            if (project.hasProperty("ketest")) {
                runDir = "run-ke-preview"
                programArgs("--width", "1280", "--height", "720")
            }
            if (project.hasProperty("jetttest")) programArgs("--width", "1280", "--height", "720")
            if (project.hasProperty("ballisticstest")) programArgs("--width", "1280", "--height", "720")
            if (project.hasProperty("scopetest")) programArgs("--width", "1280", "--height", "720")
            if (project.hasProperty("kuronamitest")) programArgs("--width", "1280", "--height", "720")
            if (project.hasProperty("katanatest")) programArgs("--width", "1280", "--height", "720")
            if (project.hasProperty("islandtest")) programArgs("--width", "1280", "--height", "720")
            if (project.hasProperty("b4scripttest")) programArgs("--width", "1280", "--height", "720")
            if (project.hasProperty("expotest")) programArgs("--width", "1280", "--height", "720")
            if (project.hasProperty("b4porttest")) programArgs("--width", "1280", "--height", "720")
            if (project.hasProperty("jappylaytest")) programArgs("--width", "1280", "--height", "720")
            if (project.hasProperty("b4rendertest")) programArgs("--width", "1280", "--height", "720")
            if (project.hasProperty("ravengui")) programArgs("--width", "1280", "--height", "720")
            if (project.hasProperty("meowtilstest")) programArgs("--width", "1280", "--height", "720")
            if (project.hasProperty("spotifytest")) programArgs("--width", "1280", "--height", "720")
            if (project.hasProperty("hudtest")) programArgs("--width", "1280", "--height", "720")
            if (project.hasProperty("lyricstest")) programArgs("--width", "1280", "--height", "720")
            if (project.hasProperty("fvtest")) programArgs("--width", "1280", "--height", "720")
        }
    }
}

repositories {
    mavenCentral()
}

// libraries packed into the mod jar (moved under gale/lib so they cannot clash with another mod's copy)
val shade: Configuration by configurations.creating
configurations.implementation { extendsFrom(shade) }

dependencies {
    minecraft("com.mojang:minecraft:1.8.9")
    mappings("de.oceanlabs.mcp:mcp_stable:22-1.8.9")
    forge("net.minecraftforge:forge:1.8.9-11.15.1.2318-1.8.9")
    // NetEase Cloud Music: MP3 decoding (gale.netease.NeteasePlayer) and the login QR code (gale.netease.NeteaseLogin)
    shade("javazoom:jlayer:1.0.1")
    shade("com.google.zxing:core:3.5.1")
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("mcmod.info") { expand("version" to project.version) }
}

// Forge 1.8.9 locates a development mod's resources beside its classes.
// Keep the output together so textures/sounds work in runClient as well as in the packaged jar.
sourceSets.main { output.setResourcesDir(layout.buildDirectory.dir("classes/java/main")) }
tasks.processResources { destinationDir = tasks.compileJava.get().destinationDirectory.get().asFile }

tasks.withType<JavaCompile> { options.encoding = "UTF-8" }

// the in-game self test (gale/devtest) and the example modules (gale/example) are for development: not in the mod jar
tasks.jar { exclude("gale/devtest/**", "gale/example/**") }

// the mod's classes plus the packed libraries; this is what gets remapped into the mod jar
tasks.shadowJar {
    configurations = listOf(shade)
    archiveClassifier.set("dev-shadow")
    exclude("gale/devtest/**", "gale/example/**", "META-INF/maven/**", "module-info.class")
    relocate("javazoom.jl", "gale.lib.jlayer")
    relocate("com.google.zxing", "gale.lib.zxing")
}

tasks.named<net.fabricmc.loom.task.RemapJarTask>("remapJar") {
    inputFile.set(tasks.shadowJar.flatMap { it.archiveFile })
    archiveFileName.set("GaleMod-${project.version}.jar")
}

// Minecraft 1.8.9 starts on Java 8; Gradle itself runs on a newer JDK.
// gradlew runClient -Pdevtest: start the game, make a flat world, run the self test, write run/galemod-devtest.txt, quit.
// gradlew runClient -Pguishots: the same, but only the GUI part, in a 1920x1080 window, with screenshots in run/screenshots.
// gradlew runClient -Pscripttest: the same, but only the script API part.
// gradlew runClient -Poptimizetest: the same, but only the frame-time analyzer and Performance (with a benchmark).
tasks.named<JavaExec>("runClient") {
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(8)) })
    if (project.hasProperty("devtest") || project.hasProperty("guishots") || project.hasProperty("scripttest") || project.hasProperty("optimizetest") || project.hasProperty("killfxtest") || project.hasProperty("jetttest")) systemProperty("gale.devtest", "true")
    if (project.hasProperty("jetttest")) systemProperty("gale.jetttest", "true")
    if (project.hasProperty("ballisticstest")) { systemProperty("gale.devtest", "true"); systemProperty("gale.ballisticstest", "true") }
    if (project.hasProperty("scopetest")) { systemProperty("gale.devtest", "true"); systemProperty("gale.scopetest", "true") }
    if (project.hasProperty("kuronamitest")) { systemProperty("gale.devtest", "true"); systemProperty("gale.kuronamitest", "true") }
    if (project.hasProperty("katanatest")) { systemProperty("gale.devtest", "true"); systemProperty("gale.katanatest", "true") }
    // gradlew runClient -Pislandtest: the island and AutoDef in a made-up Bed Wars game, screenshots in run/screenshots
    if (project.hasProperty("islandtest")) { systemProperty("gale.devtest", "true"); systemProperty("gale.islandtest", "true") }
    // gradlew runClient -Pb4scripttest: Trajectories and the B4 script runtime (test scripts, then run/galemod/scripts-all)
    if (project.hasProperty("b4scripttest")) { systemProperty("gale.devtest", "true"); systemProperty("gale.b4scripttest", "true") }
    // gradlew runClient -Pexpotest: the exposcaffold script (run/galemod/scripts-expo) bridging under Gale alone
    if (project.hasProperty("expotest")) { systemProperty("gale.devtest", "true"); systemProperty("gale.expotest", "true") }
    // gradlew runClient -Pb4porttest: the modules ported from B4 (gale.b4port), and their giving way to B4's own
    if (project.hasProperty("b4porttest")) { systemProperty("gale.devtest", "true"); systemProperty("gale.b4porttest", "true") }
    // gradlew runClient -Pjappylaytest: the Jappylay overlay in a made-up Bed Wars lobby, with a local stand-in for Prism's API
    if (project.hasProperty("jappylaytest")) { systemProperty("gale.devtest", "true"); systemProperty("gale.jappylaytest", "true") }
    // gradlew runClient -Pb4rendertest: B4's Nametags, PlayerESP and Chams (gale.b4port) around two made-up players
    if (project.hasProperty("b4rendertest")) { systemProperty("gale.devtest", "true"); systemProperty("gale.b4rendertest", "true") }
    // gradlew runClient -Pravengui: the settings screen in B4's look (gale.ui.raven)
    if (project.hasProperty("ravengui")) { systemProperty("gale.devtest", "true"); systemProperty("gale.ravengui", "true") }
    // gradlew runClient -Pmeowtilstest: Meowtils extensions (gale.meowtils), with Furiah's Visuals in run/galemod/extensions
    if (project.hasProperty("meowtilstest")) { systemProperty("gale.devtest", "true"); systemProperty("gale.meowtilstest", "true") }
    // gradlew runClient -Pspotifytest: Spotify (gale.spotify) against a local stand-in for Spotify (no account needed)
    if (project.hasProperty("spotifytest")) { systemProperty("gale.devtest", "true"); systemProperty("gale.spotifytest", "true") }
    // gradlew runClient -Phudtest: the HUD editor (gale.hud): moving, snapping, resizing, the chat lift, the module list sliding
    if (project.hasProperty("hudtest")) { systemProperty("gale.devtest", "true"); systemProperty("gale.hudtest", "true") }
    // gradlew runClient -Plyricstest: the 3D lyrics (gale.lyrics) on a made-up song, then NetEase Cloud Music (gale.netease) for real, no sound
    if (project.hasProperty("lyricstest")) { systemProperty("gale.devtest", "true"); systemProperty("gale.lyricstest", "true") }
    // gradlew runClient -Pfvtest: Furiah's Visuals' weapon models and kill effects under Gale (with run/config/furiahsvisuals.json)
    if (project.hasProperty("fvtest")) { systemProperty("gale.devtest", "true"); systemProperty("gale.fvtest", "true") }
    if (project.hasProperty("killfxtest")) systemProperty("gale.killfxtest", "true")
    if (project.hasProperty("gdtest")) { systemProperty("gale.devtest", "true"); systemProperty("gale.gdtest", "true") }
    // New KE presets in an isolated, interactive preview world.
    if (project.hasProperty("ketest")) { systemProperty("gale.devtest", "true"); systemProperty("gale.ketest", "true") }
    if (project.hasProperty("guishots")) systemProperty("gale.guishots", "true")
    if (project.hasProperty("scripttest")) systemProperty("gale.scripttest", "true")
    if (project.hasProperty("optimizetest")) systemProperty("gale.optimizetest", "true")
}

// Browser and clipboard fallback paths, without touching external applications.
tasks.register<JavaExec>("contactLinksTest") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("gale.devtest.ContactLinksTest")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(8)) })
}

// gradlew spotifyCoreTest: the Spotify client (login, Web API, player) against a local stand-in for Spotify, without the game
tasks.register<JavaExec>("spotifyCoreTest") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("gale.devtest.SpotifyCoreTest")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(8)) })
}

// gradlew neteaseCoreTest: NetEase Cloud Music and the lyrics without the game (parsing and encryption offline; search,
// lyrics, LRCLIB and a song played with no sound online)
tasks.register<JavaExec>("neteaseCoreTest") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("gale.devtest.NeteaseCoreTest")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(8)) })
}
