plugins {
    kotlin("jvm") version "2.0.0"
    id("org.jetbrains.compose") version "1.6.11"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.0"
    kotlin("plugin.serialization") version "2.0.0"
}

group = "ai.boss.plugins"
version = "1.0.0"

repositories {
    mavenCentral()
    google()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation(compose.desktop.currentOs)
    implementation(compose.material)
    implementation(compose.materialIconsExtended)
    
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.2")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.10.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

compose.desktop {
    application {
        mainClass = "ai.boss.guardrail.ui.StandaloneAppKt"
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<Jar>("buildPluginJar") {
    archiveBaseName.set("boss-guardrail-plugin")
    archiveVersion.set("1.0.0")
    
    from(sourceSets.main.get().output)
    
    dependsOn(tasks.classes)
    
    manifest {
        attributes(
            "Implementation-Title" to "BOSS Guardrail Plugin",
            "Implementation-Version" to project.version,
            "Plugin-Class" to "ai.boss.guardrail.GuardrailPlugin",
            "Plugin-Id" to "ai.boss.guardrail"
        )
    }
}
