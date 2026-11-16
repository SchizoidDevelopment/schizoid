/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.api.theme

import com.sun.jna.platform.win32.*
import com.sun.jna.platform.win32.WinReg.HKEYByReference
import dev.lyzev.api.events.*
import dev.lyzev.schizoid.Schizoid
import net.minecraft.client.Minecraft
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.concurrent.thread

val theme: OSTheme = when {
    System.getProperty("os.name").lowercase().startsWith("windows") -> WindowsTheme
    System.getProperty("os.name").lowercase().startsWith("mac") -> MacOSTheme
    System.getProperty("os.name").contains("linux", true) -> LinuxTheme
    else -> throw RuntimeException("OS ${System.getProperty("os.name")} not supported")
}

interface OSTheme : EventListener {

    companion object : OSTheme {
        // Resolve the platform implementation at call time: capturing it here can observe
        // the top-level theme before its initialization has completed.
        override fun getCurrentTheme(): Theme = theme.getCurrentTheme()

        override fun startListenForUpdatesThread() = theme.startListenForUpdatesThread()

        override val shouldHandleEvents: Boolean
            get() = theme.shouldHandleEvents
    }

    enum class Theme {
        Dark, Light
    }

    fun getCurrentTheme(): Theme

    fun startListenForUpdatesThread()

    override val shouldHandleEvents: Boolean
        get() = theme == this
}

// Serialize shutdown with event delivery, including work already queued on the client.
private class ThemeUpdateDispatcher(private val minecraft: Minecraft) {
    private var stopped = false

    @Synchronized
    fun stop() {
        stopped = true
    }

    fun publish(theme: OSTheme.Theme) {
        minecraft.execute {
            synchronized(this) {
                if (!stopped) EventOSThemeUpdate(theme).fire()
            }
        }
    }
}

// Windows implementation
object WindowsTheme : OSTheme {

    private const val REG_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize"

    @Volatile
    private var isShuttingDown = false
    private var started = false

    private fun listenForUpdates(dispatcher: ThemeUpdateDispatcher) {
        val keyRef = HKEYByReference()
        val openResult = Advapi32.INSTANCE.RegOpenKeyEx(WinReg.HKEY_CURRENT_USER, REG_KEY, 0, WinNT.KEY_NOTIFY, keyRef)
        if (openResult != WinError.ERROR_SUCCESS) {
            Schizoid.logger.error("Failed to open Windows theme registry key: $openResult")
            return
        }
        var handle: WinNT.HANDLE? = null
        try {
            handle = Kernel32.INSTANCE.CreateEvent(null, false, false, null)
            if (handle == null) {
                Schizoid.logger.error("Failed to create Windows theme notification event: ${Kernel32.INSTANCE.GetLastError()}")
                return
            }
            while (!isShuttingDown) {
                val result = Advapi32.INSTANCE.RegNotifyChangeKeyValue(
                    keyRef.value, true, WinNT.REG_NOTIFY_CHANGE_LAST_SET or WinNT.REG_NOTIFY_CHANGE_NAME, handle, true
                )
                if (result != WinError.ERROR_SUCCESS) {
                    Schizoid.logger.error("Failed to watch Windows theme registry key: $result")
                    break
                }
                // Keep the same outstanding notification across timeouts. Interrupting a
                // Java thread does not interrupt WaitForSingleObject.
                var waitResult: Int
                do {
                    waitResult = Kernel32.INSTANCE.WaitForSingleObject(handle, 1000)
                } while (waitResult == WinError.WAIT_TIMEOUT && !isShuttingDown)
                if (isShuttingDown) break
                if (waitResult != WinBase.WAIT_OBJECT_0) {
                    Schizoid.logger.error("Failed waiting for Windows theme changes: $waitResult")
                    break
                }
                dispatcher.publish(getCurrentTheme())
            }
        } catch (e: Exception) {
            Schizoid.logger.error("Windows theme watcher failed.", e)
        } finally {
            // Cancel the outstanding notification before closing its event.
            Advapi32.INSTANCE.RegCloseKey(keyRef.value)
            if (handle != null) Kernel32.INSTANCE.CloseHandle(handle)
        }
    }

    override fun getCurrentTheme(): OSTheme.Theme {
        val key = if (!Advapi32Util.registryKeyExists(WinReg.HKEY_CURRENT_USER, REG_KEY)) {
            null
        } else if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, REG_KEY, "AppsUseLightTheme")) {
            "AppsUseLightTheme"
        } else if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, REG_KEY, "SystemUsesLightTheme")) {
            "SystemUsesLightTheme"
        } else {
            null
        }
        return if (key == null || Advapi32Util.registryGetIntValue(WinReg.HKEY_CURRENT_USER, REG_KEY, key) == 0) {
            OSTheme.Theme.Dark
        } else {
            OSTheme.Theme.Light
        }
    }

    @Synchronized
    override fun startListenForUpdatesThread() {
        if (started || isShuttingDown) return
        val minecraft = Minecraft.getInstance() ?: return
        val dispatcher = ThemeUpdateDispatcher(minecraft)
        started = true
        on<EventShutdown> {
            dispatcher.stop()
            isShuttingDown = true
        }
        thread(name = "Windows theme watcher", isDaemon = true) { listenForUpdates(dispatcher) }
    }
}

// MacOS implementation
object MacOSTheme : OSTheme {

    private var cantFetch = false
    private var lastTheme = getCurrentTheme()
    private var started = false

    override fun getCurrentTheme(): OSTheme.Theme {
        if (cantFetch) return OSTheme.Theme.Dark
        runCatching {
            val process = ProcessBuilder("defaults", "read", "-g", "AppleInterfaceStyle").start()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val theme = reader.readLine()
            return if (theme == "Dark") OSTheme.Theme.Dark else OSTheme.Theme.Light
        }.onFailure {
            Schizoid.logger.error("Failed to fetch the current theme.", it)
            cantFetch = true
        }
        return OSTheme.Theme.Dark
    }

    @Synchronized
    override fun startListenForUpdatesThread() {
        if (started) return
        val minecraft = Minecraft.getInstance() ?: return
        val dispatcher = ThemeUpdateDispatcher(minecraft)
        started = true
        on<EventShutdown> { dispatcher.stop() }
        on<EventScheduleTask> {
            synchronized(this) {
                val theme = getCurrentTheme()
                if (lastTheme != theme) {
                    dispatcher.publish(theme)
                    lastTheme = theme
                }
            }
        }
    }
}

// Linux implementation
object LinuxTheme : OSTheme {

    private var cantFetch = false
    private var lastTheme = getCurrentTheme()
    private var started = false

    override fun getCurrentTheme(): OSTheme.Theme {
        if (cantFetch) return OSTheme.Theme.Dark
        runCatching {
            val process = ProcessBuilder("gsettings", "get", "org.gnome.desktop.interface", "gtk-theme").start()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val theme = reader.readLine()
            return if (theme.contains("dark", ignoreCase = true)) OSTheme.Theme.Dark else OSTheme.Theme.Light
        }.onFailure {
            Schizoid.logger.error("Failed to fetch the current theme.", it)
            cantFetch = true
        }
        return OSTheme.Theme.Dark
    }

    @Synchronized
    override fun startListenForUpdatesThread() {
        if (started) return
        val minecraft = Minecraft.getInstance() ?: return
        val dispatcher = ThemeUpdateDispatcher(minecraft)
        started = true
        on<EventShutdown> { dispatcher.stop() }
        on<EventScheduleTask> {
            synchronized(this) {
                val theme = getCurrentTheme()
                if (lastTheme != theme) {
                    dispatcher.publish(theme)
                    lastTheme = theme
                }
            }
        }
    }
}
