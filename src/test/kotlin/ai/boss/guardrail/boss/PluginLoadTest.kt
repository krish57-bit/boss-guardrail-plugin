package ai.boss.guardrail.boss

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.Plugin
import ai.rever.boss.plugin.api.PluginManifest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URLClassLoader
import java.util.zip.ZipFile
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Loads the built plugin jar the way BossConsole's `DynamicPluginLoader` does:
 * read `META-INF/boss-plugin/plugin.json`, load `mainClass` in a classloader
 * whose parent serves the plugin API, require it to implement [Plugin], and
 * create it with the no-arg constructor.
 *
 * Plugin classes are loaded child-first from the jar, so this checks the jar,
 * not the test classpath.
 */
class PluginLoadTest {

    private val jarFile = File(System.getProperty("guardrail.pluginJar").orEmpty())
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    @DisplayName("The built jar loads as a BOSS DynamicPlugin whose identity matches its manifest")
    fun jarLoadsAsBossPlugin() {
        assumeTrue(jarFile.isFile, "run through Gradle so the plugin jar is built first")

        val manifestText = ZipFile(jarFile).use { zip ->
            zip.getInputStream(zip.getEntry("META-INF/boss-plugin/plugin.json")).reader().readText()
        }
        val manifest = json.decodeFromString(PluginManifest.serializer(), manifestText)

        assertEquals(
            emptyList(),
            DynamicPlugin.validateManifestForPublishing(manifest).errorMessages,
            "manifest must pass the store's publishing checks",
        )

        ChildFirstLoader(jarFile, javaClass.classLoader).use { loader ->
            val cls = loader.loadClass(manifest.mainClass)
            assertTrue(cls.classLoader === loader, "mainClass must come from the jar")
            // BossConsole DynamicPluginLoader: "Main class does not implement Plugin interface"
            assertTrue(Plugin::class.java.isAssignableFrom(cls), "mainClass must implement Plugin")
            val instance = cls.getDeclaredConstructor().newInstance() as DynamicPlugin
            assertEquals(
                emptyList(),
                instance.validateAgainstManifest(manifest).errorMessages,
                "code and manifest must agree on id, name and version",
            )
        }
    }

    @Test
    @DisplayName("The jar does not bundle classes the host owns")
    fun jarDoesNotBundleHostClasses() {
        assumeTrue(jarFile.isFile, "run through Gradle so the plugin jar is built first")
        val names = ZipFile(jarFile).use { zip -> zip.entries().asSequence().map { it.name }.toList() }
        assertTrue(names.none { it.startsWith("ai/rever/boss/") }, "plugin API must not be bundled")
        assertTrue(names.none { it.startsWith("kotlin/") || it.startsWith("androidx/") || it.startsWith("kotlinx/") })
        assertTrue("ai/boss/guardrail/boss/GuardrailDynamicPlugin.class" in names)
    }

    private class ChildFirstLoader(jar: File, parent: ClassLoader) :
        URLClassLoader(arrayOf(jar.toURI().toURL()), parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(getClassLoadingLock(name)) {
            if (!name.startsWith("ai.boss.guardrail.")) return super.loadClass(name, resolve)
            findLoadedClass(name) ?: findClass(name).also { if (resolve) resolveClass(it) }
        }
    }
}
