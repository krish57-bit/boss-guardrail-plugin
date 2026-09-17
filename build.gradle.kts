import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.zip.ZipFile

plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.serialization") version "2.3.0"
    id("org.jetbrains.compose") version "1.10.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0"
}

group = "io.github.krish57bit"
version = "1.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

// ---------------------------------------------------------------------------
// BOSS plugin API. compileOnly: the host serves it at runtime, and bundling it
// breaks the plugin. Distributed only as a GitHub release asset, so it is
// fetched here. Override with -PbossPluginApiJar=/path/to/jar for local builds.
// Keep in step with "apiVersion" in META-INF/boss-plugin/plugin.json.
// ---------------------------------------------------------------------------
val bossPluginApiVersion = "1.0.93"
val bossPluginApiJar: File = (findProperty("bossPluginApiJar") as String?)?.let(::file)
    ?: layout.buildDirectory.file("downloaded-deps/boss-plugin-api-$bossPluginApiVersion.jar").get().asFile

val fetchBossPluginApi by tasks.registering {
    description = "Downloads the pinned boss-plugin-api release jar"
    outputs.file(bossPluginApiJar)
    onlyIf { !bossPluginApiJar.isFile || bossPluginApiJar.length() == 0L }
    doLast {
        val url = "https://github.com/risa-labs-inc/boss-plugin-api/releases/download/" +
            "v$bossPluginApiVersion/boss-plugin-api-$bossPluginApiVersion.jar"
        bossPluginApiJar.parentFile.mkdirs()
        logger.lifecycle("Downloading $url")
        uri(url).toURL().openStream().use { input ->
            bossPluginApiJar.outputStream().use { input.copyTo(it) }
        }
    }
}

dependencies {
    compileOnly(files(bossPluginApiJar).builtBy(fetchBossPluginApi))
    testImplementation(files(bossPluginApiJar).builtBy(fetchBossPluginApi))

    // Provided by the BOSS host at runtime; not bundled into the plugin jar.
    implementation(compose.desktop.currentOs)
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.material)
    implementation(compose.materialIconsExtended)
    implementation("com.arkivanov.decompose:decompose:3.3.0")
    implementation("com.arkivanov.essenty:lifecycle:2.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.2")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.10.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

compose.desktop {
    application {
        // Standalone demo window (simulated commands). Not used by BOSS.
        mainClass = "ai.boss.guardrail.ui.StandaloneAppKt"
    }
}

tasks.named<Jar>("jar") {
    // buildPluginJar is the only artifact; a second jar in build/libs is easy to ship by mistake.
    enabled = false
}

val buildPluginJar by tasks.registering(Jar::class) {
    description = "Builds the BOSS plugin jar (plugin classes and manifest only)"
    archiveFileName.set("boss-guardrail-plugin-${project.version}.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    manifest {
        attributes(
            "Implementation-Title" to "BOSS Agent Guardrail",
            "Implementation-Version" to project.version,
        )
    }
}

// Keep plugin.json's version equal to the Gradle version.
tasks.processResources {
    inputs.property("pluginVersion", project.version)
    filesMatching("**/plugin.json") {
        filter { line ->
            line.replace(Regex(""""version"\s*:\s*"[^"]*""""), """"version": "${project.version}"""")
        }
    }
}

val verifyPluginJar by tasks.registering {
    description = "Checks the plugin jar has what the BOSS loader needs and nothing the host owns"
    dependsOn(buildPluginJar)
    val jarFile = buildPluginJar.flatMap { it.archiveFile }
    doLast {
        ZipFile(jarFile.get().asFile).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            check("META-INF/boss-plugin/plugin.json" in names) { "plugin.json missing from jar" }
            val manifest = zip.getInputStream(zip.getEntry("META-INF/boss-plugin/plugin.json")).reader().readText()
            val mainClass = Regex(""""mainClass"\s*:\s*"([^"]+)"""").find(manifest)!!.groupValues[1]
            check(mainClass.replace('.', '/') + ".class" in names) { "mainClass $mainClass not in jar" }
            val bundledApi = names.filter { it.startsWith("ai/rever/boss/") }
            check(bundledApi.isEmpty()) { "Jar bundles host-owned classes: ${bundledApi.take(5)}" }
            check(names.none { it.startsWith("kotlin/") || it.startsWith("androidx/") }) { "Jar bundles runtime libraries" }
            logger.lifecycle("Plugin jar OK: ${names.size} entries, mainClass=$mainClass")
        }
    }
}

tasks.test {
    useJUnitPlatform()
    dependsOn(buildPluginJar)
    // PluginLoadTest loads the built jar the way the BOSS loader does.
    systemProperty("guardrail.pluginJar", buildPluginJar.get().archiveFile.get().asFile.absolutePath)
    systemProperty("guardrail.apiJar", bossPluginApiJar.absolutePath)
}

tasks.check { dependsOn(verifyPluginJar) }
tasks.build { dependsOn(buildPluginJar) }
