/*
 * Copyright (c) 2023. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.feature

import dev.lyzev.api.events.*
import dev.lyzev.api.glfw.GLFWKey
import dev.lyzev.schizoid.Schizoid
import dev.lyzev.schizoid.Schizoid.mc
import dev.lyzev.schizoid.feature.features.module.ModuleToggleable
import net.minecraft.network.chat.Component
import org.reflections.Reflections
import java.lang.reflect.Modifier
import kotlin.jvm.internal.Reflection


/**
 * Singleton object responsible for initializing and managing features.
 */
object FeatureManager : EventListener {

    /**
     * The list of features.
     */
    val features = mutableListOf<IFeature>()

    /**
     * Gets a list of features by their type.
     */
    inline fun <reified T : IFeature> get(): List<T> = features.filterIsInstance<T>()

    /**
     * Gets a feature by its name and type.
     *
     * @param alias The name of the feature.
     */
    inline fun <reified T : IFeature> find(name: String): T? =
        features.filterIsInstance<T>().firstOrNull { feature -> feature.name.equals(name, true) }

    /**
     * Gets a feature by its name.
     */
    operator fun get(name: String): IFeature? = features.firstOrNull { feature -> feature.name.equals(name, true) }

    /**
     * Gets a list of features by their category.
     */
    operator fun get(category: IFeature.Category): List<IFeature> = features.filter { it.category == category }

    /**
     * Gets a list of features by their categories.
     */
    operator fun get(vararg category: IFeature.Category): List<IFeature> =
        features.filter { category.contains(it.category) }

    /**
     * Sends a chat message to the player.
     *
     * @param message The message to send.
     */
    fun sendChatMessage(text: Component) = mc.player?.sendSystemMessage(text)

    override val shouldHandleEvents = true

    init {
        // Initialize features.
        Reflections("${javaClass.packageName}.features").getSubTypesOf(IFeature::class.java)
            .filter { !Modifier.isInterface(it.modifiers) && !Modifier.isAbstract(it.modifiers) }.forEach {
                val feature = Reflection.getOrCreateKotlinClass(it).objectInstance as IFeature
                if (!feature.hide) features += feature
                Schizoid.logger.info("Initialized feature: ${feature.name}.")
            }
        features.sortBy { it.name }

        on<EventKeystroke> { event ->
            if (mc.gui.screen() == null && event.action == 1) features.filter { it.keybinds.contains(GLFWKey[event.key]) }
                .forEach { it.keybindReleased() }
        }

        on<EventMouseClick> { event ->
            if (mc.gui.screen() == null && event.action == 1) features.filter { it.keybinds.contains(GLFWKey[event.button]) }
                .forEach { it.keybindReleased() }
        }

        on<EventUpdateCrosshairTargetTick> {
            features.filterIsInstance<ModuleToggleable>().forEach {
                if (it.isEnabled) {
                    it.disableOn.forEach { disableOn ->
                        if (disableOn.second) {
                            disableOn.first.handle(it)
                        }
                    }
                }
            }
        }
    }
}
