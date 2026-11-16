/*
 * Copyright (c) 2023. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid

import com.mojang.blaze3d.opengl.GlBackend
import dev.lyzev.api.events.*
import dev.lyzev.api.events.EventListener
import dev.lyzev.api.imgui.ImGuiLoader
import dev.lyzev.api.imgui.render.ImGuiRenderer
import dev.lyzev.api.imgui.spatial.SpatialHudOverlay
import dev.lyzev.api.imgui.spatial.SpatialWorldHook
import dev.lyzev.api.opengl.Render
import dev.lyzev.api.setting.SettingInitializer
import dev.lyzev.api.theme.OSTheme
import dev.lyzev.schizoid.Schizoid.CI
import dev.lyzev.schizoid.Schizoid.METADATA
import dev.lyzev.schizoid.Schizoid.MOD_AUTHORS
import dev.lyzev.schizoid.Schizoid.MOD_ID
import dev.lyzev.schizoid.Schizoid.MOD_NAME
import dev.lyzev.schizoid.Schizoid.MOD_VERSION
import dev.lyzev.schizoid.Schizoid.logger
import dev.lyzev.schizoid.Schizoid.mc
import dev.lyzev.schizoid.Schizoid.root
import dev.lyzev.schizoid.feature.FeatureManager
import dev.lyzev.schizoid.feature.features.module.modules.render.TorusGeometry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.loader.api.FabricLoader
import net.fabricmc.loader.api.metadata.Person
import net.minecraft.client.Minecraft
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.io.File
import java.security.SecureRandom
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Elevate your Minecraft gameplay with this free and feature-rich client built with Fabric API and utilizing mixin-based injection techniques.
 *
 * @property MOD_ID The unique identifier of the mod.
 * @property METADATA The metadata of the mod.
 * @property MOD_NAME The name of the mod.
 * @property MOD_VERSION The version of the mod.
 * @property MOD_AUTHORS The list of authors contributing to the mod.
 * @property CI Whether the mod is running in a continuous integration environment.
 * @property root The root directory of the Schizoid mod, used for storing mod-specific data.
 * @property logger The logger for the Schizoid mod.
 * @property mc The Minecraft client instance.
 */
object Schizoid : ClientModInitializer, EventListener {

    private val scheduler = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val shutdownFired = AtomicBoolean()

    private fun fireShutdown() {
        if (shutdownFired.compareAndSet(false, true)) EventShutdown.fire()
    }

    private val properties = Properties().apply { load(Schizoid::class.java.getResourceAsStream("/app.properties")) }

    /**
     * The unique identifier of the mod.
     */
    val MOD_ID = properties.getProperty("MOD_ID")

    /**
     * The metadata of the mod.
     */
    val METADATA = FabricLoader.getInstance().getModContainer(MOD_ID).get().metadata

    /**
     * The name of the mod.
     */
    val MOD_NAME = METADATA.name

    /**
     * The version of the mod.
     */
    val MOD_VERSION = METADATA.version.friendlyString

    /**
     * The list of authors contributing to the mod.
     */
    val MOD_AUTHORS: MutableCollection<Person> = METADATA.authors

    /**
     * Whether the mod is running in a continuous integration environment.
     */
    val CI = properties.getProperty("CI")?.toBooleanStrict() ?: false

    /**
     * Whether the mod is running in developer mode.
     */
    val DEVELOPER_MODE = System.getProperty("fabric.development")?.toBoolean() ?: false

    /**
     * The Minecraft client instance.
     */
    val mc = Minecraft.getInstance()

    /**
     * The root directory of the Schizoid mod, used for storing mod-specific data.
     */
    val root =
        File(System.getProperty("user.home") + File.separator + MOD_ID).apply { if (!exists() || !isDirectory) mkdirs() }

    val configDir = root.resolve("config").apply { if (!exists() || !isDirectory) mkdirs() }

    /**
     * The logger for the Schizoid mod.
     */
    val logger: Logger = LogManager.getLogger(MOD_ID)

    val random = SecureRandom.getInstanceStrong()

    /**
     * Initialize the Schizoid mod.
     */
    override fun onInitializeClient() {
        System.setProperty("java.awt.headless", "false")
        on<EventStartup> {
            val init = System.currentTimeMillis()
            runCatching {
                // Initialize the Schizoid mod, log mod initialization information, initialize settings, register a shutdown hook for cleanup, and fire the startup event.
                logger.info("Initializing Schizoid v$MOD_VERSION by ${MOD_AUTHORS.joinToString(" & ") { it.name }}...")
                if (CI)
                    logger.warn("Running in a continuous integration environment!")
                if (DEVELOPER_MODE)
                    logger.warn("Running in developer mode!")
                FeatureManager
                SpatialWorldHook // force object init so it subscribes to EventRenderWorld
                SpatialHudOverlay // reticle + hint line for the world workspace
                SettingInitializer.reload()
                OSTheme.startListenForUpdatesThread()
                Runtime.getRuntime().addShutdownHook(Thread { fireShutdown() })
                logger.info("Initialized ${FeatureManager.features.size} features!")
                scheduler.launch {
                    while (isActive) {
                        launch { EventScheduleTask.fire() }
                        delay(1000)
                    }
                }
            }.onSuccess { // Log successful initialization.
                logger.info("Initialized Schizoid in ${System.currentTimeMillis() - init}ms!")
            }.onFailure { exception -> // Log initialization failure and the associated exception.
                logger.error("Failed to initialize Schizoid!", exception)
            }
        }
        ClientLifecycleEvents.CLIENT_STARTED.register {
            check(mc.window.backend() is GlBackend) {
                "Schizoid's 26.2 port requires the OpenGL graphics backend. Vulkan is not supported."
            }
            ImGuiLoader.initialize(mc.window.handle())
            EventGlfwInit(mc.window.handle()).fire()
            EventStartup.fire()
        }
        ClientLifecycleEvents.CLIENT_STOPPING.register {
            scheduler.cancel()
            fireShutdown()
            if (ImGuiLoader.isReady) {
                runCatching { ImGuiRenderer.dispose() }.onFailure { logger.error("Failed to dispose ImGui", it) }
            }
            runCatching { TorusGeometry.close() }.onFailure { logger.error("Failed to dispose torus geometry", it) }
            runCatching { Render.shutdown() }.onFailure { logger.error("Failed to dispose OpenGL resources", it) }
        }
    }

    override val shouldHandleEvents: Boolean
        get() = true
}
