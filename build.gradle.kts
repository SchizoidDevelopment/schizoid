/*
 * Copyright (c) 2023. Schizoid
 * All rights reserved.
 */

import com.google.gson.JsonParser
import groovy.util.Node
import groovy.xml.XmlParser
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.net.URI
import java.util.*

plugins {
    idea
    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.dokka)
    alias(libs.plugins.shadow)
    alias(libs.plugins.fabric.loom)
}

idea {
	module {
		isDownloadSources = true
	}
}

base { archivesName.set(project.extra["archives_base_name"] as String) }

val lastCommitHash = providers.exec {
    commandLine("git", "rev-parse", "--short", "HEAD")
}.standardOutput.asText.get().trim()

val ci = System.getenv("CI")?.toBooleanStrictOrNull() ?: false

version = project.extra["mod_version"] as String
if (ci)
    version = "$version-$lastCommitHash"
version = "$version+${libs.minecraft.get().version}"
group = project.extra["maven_group"] as String

repositories {
    mavenCentral()
    maven("https://api.modrinth.com/maven")
    maven("https://jitpack.io")
}

configurations {
    create("shadowDependencies")
    create("minecraftDependencies")
    create("imguiBindingForShading") { isTransitive = false }
}

dependencies {
    minecraft(libs.minecraft)
    implementation(libs.bundles.fabric)
    add("minecraftDependencies", libs.bundles.fabric)

    // Libraries (required)
    add("shadowDependencies", libs.lyzev.events)
    add("shadowDependencies", libs.lyzev.settings)
    add("shadowDependencies", libs.lyzev.network)

    add("shadowDependencies", libs.reflections)
    // Use the patched ImFontAtlas class and exactly one matching Windows x64 native.
    implementation(libs.imgui.binding)
    add("imguiBindingForShading", libs.imgui.binding)
    add("shadowDependencies", "io.github.spair:imgui-java-lwjgl3:${libs.versions.imgui.get()}") {
        exclude(group = "io.github.spair", module = "imgui-java-binding")
    }
    add("shadowDependencies", libs.fuzzywuzzy)
    add("shadowDependencies", libs.mpi)
    add("shadowDependencies", libs.discordipc)
    add("shadowDependencies", libs.waybackauthlib)
    add("shadowDependencies", libs.minecraftauth)
    add("shadowDependencies", libs.kotlin.csv)

    // Mods (optional)
    // Load a user's existing pack read-only into the isolated Loom run directory.
    val compatModsDir = providers.gradleProperty("compatModsDir").orNull
    if (compatModsDir != null) {
        require(file(compatModsDir).isDirectory) { "compatModsDir is not a directory: $compatModsDir" }
        runtimeOnly(fileTree(compatModsDir) {
            include("*.jar")
            // These are already provided by the version catalog, not shaded into our jar.
            exclude("fabric-api-*.jar", "fabric-language-kotlin-*.jar")
        })
    } else if (providers.gradleProperty("withOptionalMods").orNull == "true") {
        runtimeOnly(libs.bundles.modrinth)
    }

    configurations.getByName("shadowDependencies").dependencies.forEach { dependency ->
        implementation(dependency)
    }
}

loom {
    accessWidenerPath = file("src/main/resources/${project.extra["archives_base_name"] as String}.accesswidener")
    mods {
        create(base.archivesName.get()) {
            sourceSet(sourceSets.main.get())
        }
    }
}

tasks.register("fixRunResources") {
    group = project.extra["archives_base_name"] as String
    description = "Fix Run Resources"
    dependsOn("processResources")
    doLast {
        copy {
            from("build/resources/main")
            into("out/production/Schizoid.main")
        }
    }
}

tasks.register("generateProperties") {
    group = project.extra["archives_base_name"] as String
    description = "Generate Properties"
    doLast {
        val props = Properties()
        props.setProperty("MOD_ID", project.extra["archives_base_name"] as String)
        props.setProperty("CI", ci.toString())
        file("src/main/resources/app.properties").outputStream().use { props.store(it, null) }
    }
}

tasks.register("updateFabric") {
    group = project.extra["archives_base_name"] as String
    description = "Update Fabric Library Versions"
    doLast {
        val gameVersion = libs.versions.minecraft.get()

        val loaderVersion =
            requestText("https://meta.fabricmc.net/v2/versions/loader").let { data ->
                JsonParser.parseString(data.toString()).asJsonArray.first { version -> version.asJsonObject["stable"].asBoolean }!!.asJsonObject["version"].asString
            }

        val apiVersion = requestText(
            "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml"
        ).let { data ->
            ((XmlParser().parseText(data.toString()).children()
                .first { node -> (node as Node).name() == "versioning" } as Node).children()
                .first { node -> (node as Node).name() == "versions" } as Node).children()
                .map { node -> (node as Node).text() }.last { version -> version.endsWith(gameVersion) }
        }

        val klVersion = requestText(
            "https://maven.fabricmc.net/net/fabricmc/fabric-language-kotlin/maven-metadata.xml"
        ).let { data ->
            ((XmlParser().parseText(data.toString()).children()
                .first { node -> (node as Node).name() == "versioning" } as Node).children()
                .first { node -> (node as Node).name() == "latest" } as Node).text()
        }

        val loomVersion = requestText(
            "https://maven.fabricmc.net/net/fabricmc/fabric-loom/net.fabricmc.fabric-loom.gradle.plugin/maven-metadata.xml"
        ).let { data ->
            ((XmlParser().parseText(data.toString()).children()
                .first { node -> (node as Node).name() == "versioning" } as Node).children()
                .first { node -> (node as Node).name() == "versions" } as Node).children()
                .map { node -> (node as Node).text() }.last { version -> version.matches("\\d+\\.\\d+\\.\\d+".toRegex()) }
        }

        val versions = mapOf(
            "minecraft" to gameVersion,
            "fabric_loader" to loaderVersion,
            "fabric_api" to apiVersion,
            "fabric_kl" to klVersion,
            "fabric_loom" to loomVersion,
        ).also { println("-------LATEST-VERSIONS-------\n$it") }

        val tomlFile = file("gradle/libs.versions.toml")
        val tomlContent = tomlFile.readText()
        val updatedTomlContent =
            updateTomlVersions(tomlContent, versions).also { println("-------UPDATED-LIBS-------\n$it") }
        tomlFile.writeText(updatedTomlContent)
    }
}

tasks.register("updateKotlin") {
    group = project.extra["archives_base_name"] as String
    description = "Update Kotlin and Dokka Versions"
    doLast {
        val kotlinVersion = requestText(
            "https://api.github.com/repos/JetBrains/kotlin/releases/latest"
        ).let { data ->
            JsonParser.parseString(data.toString()).asJsonObject["tag_name"].asString.removePrefix("v")
        }

        val dokkaVersion = requestText(
            "https://api.github.com/repos/Kotlin/dokka/releases/latest"
        ).let { data ->
            JsonParser.parseString(data.toString()).asJsonObject["tag_name"].asString.removePrefix("v")
        }

        val versions = mapOf(
            "kotlin" to kotlinVersion,
            "dokka" to dokkaVersion,
        ).also { println("-------LATEST-VERSIONS-------\n$it") }

        val tomlFile = file("gradle/libs.versions.toml")
        val tomlContent = tomlFile.readText()
        val updatedTomlContent =
            updateTomlVersions(tomlContent, versions).also { println("-------UPDATED-LIBS-------\n$it") }
        tomlFile.writeText(updatedTomlContent)
    }
}

fun requestText(url: String): String = URI.create(url).toURL().openConnection().run {
    connectTimeout = 30_000
    readTimeout = 30_000
    getInputStream().bufferedReader().use { it.readText() }
}

fun updateTomlVersions(tomlContent: String, versions: Map<String, String>): String {
    val lines = tomlContent.lines()
    val updatedLines = lines.map { line ->
        versions.entries.fold(line) { acc, (key, value) ->
            acc.replace("$key = \"[^\"]+\"".toRegex(), "$key = \"$value\"")
        }
    }
    return updatedLines.joinToString("\n")
}

val dependencyConfigurations = configurations

tasks {
    val javaVersion = JavaVersion.toVersion((project.extra["java_version"] as String).toInt())

    withType<JavaCompile> {
        options.encoding = "UTF-8"
        sourceCompatibility = javaVersion.toString()
        targetCompatibility = javaVersion.toString()
        options.release.set(javaVersion.toString().toInt())
    }

    withType<KotlinCompile> { compilerOptions { jvmTarget.set(JvmTarget.fromTarget(javaVersion.toString())) } }

    jar {
        archiveClassifier.set("plain")
        from("LICENSE") { rename { "${it}_${base.archivesName.get()}" } }
    }

    processResources {
        dependsOn("generateProperties")
        filesMatching("fabric.mod.json") {
            expand(
                mutableMapOf(
                    "java" to project.extra["java_version"] as String,
                    "id" to project.extra["archives_base_name"] as String,
                    "version" to project.version.toString(),
                    "name" to project.extra["mod_name"] as String,
                    "minecraft" to libs.versions.minecraft.get(),
                    "fabricloader" to libs.versions.fabric.loader.min.get(), // use min version for compatibility
                    "fabric_api" to libs.versions.fabric.api.get(),
                    "fabric_language_kotlin" to libs.versions.fabric.kl.get(),
                )
            )
        }
        filesMatching("*.mixins.json") { expand(mutableMapOf("java" to javaVersion.toString())) }
    }

    // The layout persistence check is a plain main() by design: it needs no game, no GL context
    // and no test framework. Gradle still counts src/test as test sources and refuses to run an
    // empty `test` task, so the check gets its own task and `check` pulls it in.
    val spatialLayoutCheck by registering(JavaExec::class) {
        group = "verification"
        description = "Runs the standalone spatial layout persistence checks."
        classpath = sourceSets["test"].runtimeClasspath
        mainClass.set("dev.lyzev.api.imgui.spatial.SpatialLayoutFilesTest")
    }

    test { failOnNoDiscoveredTests = false }
    check { dependsOn(spatialLayoutCheck) }

    runClient {
        dependsOn("generateProperties")
        jvmArgs("-Xmx2G", "--enable-native-access=ALL-UNNAMED")
        // Dev affordance: hand the client -D flags without touching the launcher, e.g.
        //   ./gradlew runClient -PgameProp=schizoid.spatial.autotest=rear
        // (the spatial workspace reads schizoid.spatial.autotest for screenshot runs).
        (project.findProperty("gameProp") as String?)?.split(',')?.filter { it.isNotBlank() }?.forEach { entry ->
            jvmArgs("-D${entry.trim()}")
        }
        // Join a world on launch so an unattended run needs no clicks. Passed as a real program
        // argument list, not through --args, so a save name with spaces survives.
        (project.findProperty("quickPlaySave") as String?)?.let { save ->
            args("--quickPlaySingleplayer", save)
        }
    }

    // Run `./gradlew wrapper --gradle-version <newVersion>` or `gradle wrapper --gradle-version <newVersion>` to update gradle scripts
	// BIN distribution should be sufficient for the majority of mods
	wrapper {
		distributionType = Wrapper.DistributionType.BIN
	}

    java {
        toolchain { languageVersion.set(JavaLanguageVersion.of(javaVersion.toString())) }
        sourceCompatibility = javaVersion
        targetCompatibility = javaVersion
        withSourcesJar()
    }

    shadowJar {
        archiveClassifier.set("")
        from("LICENSE") { rename { "${it}_${base.archivesName.get()}" } }

        configurations = listOf(project.configurations.getByName("shadowDependencies"))
        from({ project.configurations.getByName("imguiBindingForShading").map { zipTree(it) } }) {
            exclude("imgui/ImFontAtlas.class", "META-INF/MANIFEST.MF")
        }
        from("vendor/imgui-patch/licenses") { into("META-INF/licenses/imgui-patch") }
        doFirst {
            check(file("src/main/resources/io/imgui/java/native-bin/imgui-java64.dll").isFile) {
                "Patched Windows x64 ImGui DLL is missing"
            }
        }

        dependencies {
            // Loom already knows the game's libraries. Don't fetch Mojang's manifest
            // again or bundle old Kotlin/LWJGL/ASM copies from transitive dependencies.
            listOf("minecraftDependencies", "minecraftRuntimeLibraries", "loaderLibraries")
                .flatMap { name ->
                    dependencyConfigurations.getByName(name).resolvedConfiguration.resolvedArtifacts
                        .map { "${it.moduleVersion.id.group}:${it.moduleVersion.id.name}" }
                }
                .toSet()
                .forEach { coordinate -> exclude(dependency("$coordinate:.*")) }
        }
    }

    assemble { dependsOn(shadowJar) }

    dokkaHtml.configure {
		moduleName.set(project.extra["archives_base_name"] as String)
		dokkaSourceSets {
			configureEach {
				includes.from("dokka-docs.md")
				jdkVersion.set(javaVersion.toString().toInt())
			}
		}
	}
}
