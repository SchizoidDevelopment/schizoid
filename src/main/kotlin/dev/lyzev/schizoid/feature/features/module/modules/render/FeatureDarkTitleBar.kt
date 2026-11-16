/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.feature.features.module.modules.render

import com.sun.jna.Pointer
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.ptr.IntByReference
import dev.lyzev.api.events.EventListener
import dev.lyzev.api.events.EventOSThemeUpdate
import dev.lyzev.api.events.on
import dev.lyzev.api.glfw.GLFWKey
import dev.lyzev.api.jna.DwmApi
import dev.lyzev.api.setting.settings.OptionEnum
import dev.lyzev.api.setting.settings.option
import dev.lyzev.api.theme.OSTheme
import dev.lyzev.api.theme.WindowsTheme
import dev.lyzev.api.theme.theme
import dev.lyzev.schizoid.Schizoid
import dev.lyzev.schizoid.feature.IFeature
import org.lwjgl.glfw.GLFW
import org.lwjgl.glfw.GLFWNativeWin32

object FeatureDarkTitleBar : IFeature, EventListener {

    val mode by option("Mode", "Mode", Mode.System, enumValues<Mode>().toList(), change = ::setTitleBarColor)
    private var prevMode: Mode? = null

    private fun setTitleBarColor(value: Mode) {
        if (prevMode == value) return
        if (hide) return
        val dark = when (value) {
            Mode.Light -> false
            Mode.Dark -> true
            Mode.System -> OSTheme.getCurrentTheme() == OSTheme.Theme.Dark
        }
        val win32 = GLFWNativeWin32.glfwGetWin32Window(mc.window.handle())
        val hwnd = HWND(Pointer(win32))
        DwmApi.DwmSetWindowAttribute(hwnd, 20, IntByReference(if (dark) 1 else 0), 4)
        // Workaround: Reload window
        GLFW.glfwHideWindow(mc.window.handle())
        GLFW.glfwShowWindow(mc.window.handle())
        prevMode = value
    }

    override val name = "Dark Title Bar"
    override val desc = "Changes the title bar color."
    override var keybinds: Set<GLFWKey> = emptySet()
    override val category = IFeature.Category.RENDER
    override val hide = theme != WindowsTheme

    override fun keybindReleased() {
        // Not needed
    }

    override val shouldHandleEvents = mode == Mode.System

    init {
        if (hide) {
            Schizoid.logger.warn("Dark Title Bar is only supported on Windows.")
        } else {
            setTitleBarColor(mode)
            on<EventOSThemeUpdate> {
                // GLFW window operations must run on the Minecraft thread, not the registry watcher.
                mc.execute {
                    if (mode == Mode.System) setTitleBarColor(Mode.System)
                }
            }
        }
    }

    enum class Mode : OptionEnum {
        Light, Dark, System;

        override val key = name
    }
}
