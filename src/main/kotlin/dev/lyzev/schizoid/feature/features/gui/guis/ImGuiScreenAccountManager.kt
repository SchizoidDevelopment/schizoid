/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.feature.features.gui.guis

import dev.lyzev.api.account.Account
import dev.lyzev.api.account.Account.Companion.setSession
import dev.lyzev.api.account.Account.Companion.currentSessionType
import dev.lyzev.api.cryptography.cipher.AES
import dev.lyzev.api.cryptography.hwid.HWID
import dev.lyzev.api.events.*
import dev.lyzev.api.imgui.font.ImGuiFonts.*
import dev.lyzev.api.imgui.font.icon.FontAwesomeIcons
import dev.lyzev.api.opengl.glId
import dev.lyzev.api.opengl.Render
import dev.lyzev.schizoid.Schizoid
import dev.lyzev.schizoid.feature.features.gui.ImGuiScreen
import dev.lyzev.schizoid.feature.features.module.ModuleToggleableRenderImGuiContent
import imgui.ImGui.*
import imgui.flag.ImGuiCond
import imgui.flag.ImGuiStyleVar
import imgui.flag.ImGuiWindowFlags
import kotlinx.serialization.json.*
import com.mojang.authlib.GameProfile
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen
import net.minecraft.client.resources.SkinManager
import net.minecraft.world.entity.player.PlayerSkin
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import java.util.function.Supplier
import kotlin.concurrent.thread

object ImGuiScreenAccountManager : ImGuiScreen("Account Manager", "A screen for managing accounts."), EventListener {

    const val WINDOW_FLAGS = ImGuiWindowFlags.NoResize or ImGuiWindowFlags.NoMove or ImGuiWindowFlags.NoCollapse
    val buttonSize = OPEN_SANS_REGULAR.size * 1.5f
    val accounts: MutableSet<Account> = CopyOnWriteArraySet()
    private var mainAccount: Account? = null
    private var skinProfileId: UUID? = null
    private var skinManager: SkinManager? = null
    private var skinLookup: Supplier<PlayerSkin>? = null

    private fun sessionSkinTextureId(): Int {
        val user = mc.user
        val manager = mc.skinManager
        if (skinProfileId != user.profileId || skinManager !== manager) {
            skinProfileId = user.profileId
            skinManager = manager
            skinLookup = manager.createLookup(GameProfile(user.profileId, user.name), false)
        }
        val skin = checkNotNull(skinLookup).get()
        return Render.isolated { mc.textureManager.getTexture(skin.body().texturePath()).glId }
    }

    fun button(icon: String, tooltip: String? = null, solid: Boolean = true): Boolean {
        if (solid) {
            FONT_AWESOME_SOLID.begin()
        } else {
            FONT_AWESOME_REGULAR.begin()
        }
        val size = calcTextSize(icon)
        pushStyleVar(ImGuiStyleVar.FramePadding, buttonSize - getStyle().itemInnerSpacingX - size.x, -1f)
        val result = button(icon, buttonSize, buttonSize)
        popStyleVar()
        OPEN_SANS_REGULAR.begin()
        if (tooltip != null && isItemHovered())
            setTooltip(tooltip)
        OPEN_SANS_REGULAR.end()
        if (solid) {
            FONT_AWESOME_SOLID.end()
        } else {
            FONT_AWESOME_REGULAR.end()
        }
        return result
    }

    override fun renderImGui() {
        setNextWindowPos(getMainViewport().centerX, getMainViewport().centerY, ImGuiCond.Always, .5f, .6f)
        setNextWindowSize(500f, 780f)
        OPEN_SANS_BOLD.begin()
        if (begin("\"ACCOUNTS\"", WINDOW_FLAGS)) {
            renderAccountsContents()
        }
        end()
        setNextWindowPos(getMainViewport().centerX - 520f, getMainViewport().centerY - 470f)
        setNextWindowSize(250f, 0f)
        if (begin("\"SESSION\"", WINDOW_FLAGS)) {
            renderSessionContents()
        }
        end()
        setNextWindowPos(getMainViewport().centerX, getMainViewport().centerY + 330f, ImGuiCond.Always, 0.5f, 0f)
        setNextWindowSize(250f, 0f)
        if (begin("\"CONTROL\"", WINDOW_FLAGS)) {
            renderControlContents(::onClose)
        }
        end()
        Account.Types.entries.forEach(Account.Type<*>::render)
        OPEN_SANS_BOLD.end()
    }

    private fun renderAccountsContents() {
        OPEN_SANS_REGULAR.begin()
        val remove = mutableSetOf<Account>()
        for (account in accounts) {
            pushID(account.hashCode())
            account.render()
            sameLine(getWindowWidth() - buttonSize * 3 - getStyle().itemSpacingX * 2 - getStyle().windowPaddingX)
            if (button(FontAwesomeIcons.Star, "Set as main account.", mainAccount == account)) {
                mainAccount = if (mainAccount == account) {
                    null
                } else {
                    account
                }
            }
            sameLine(getWindowWidth() - buttonSize * 2 - getStyle().itemSpacingX - getStyle().windowPaddingX)
            if (button(FontAwesomeIcons.SignInAlt, "Login to the account.")) {
                thread {
                    account.getSession()?.let { setSession(it) }
                }
            }
            sameLine(getWindowWidth() - buttonSize - getStyle().windowPaddingX)
            if (button(FontAwesomeIcons.Trash, "Delete the account.")) {
                remove += account
            }
            popID()
            separator()
        }
        accounts -= remove
        OPEN_SANS_REGULAR.end()
    }

    private fun renderSessionContents(messageHeight: Float = -1f) {
        OPEN_SANS_REGULAR.begin()
        val uvMinY = 8f
        val uvMaxY = 8f + uvMinY
        val cursorScreenPos = getCursorScreenPos()
        dummy(50f, 50f)
        getWindowDrawList().addImageRounded(
            sessionSkinTextureId(),
            cursorScreenPos.x,
            cursorScreenPos.y,
            cursorScreenPos.x + 50f,
            cursorScreenPos.y + 50f,
            8f / 64f,
            uvMinY / 64f,
            16f / 64f,
            uvMaxY / 64f,
            -1,
            5f
        )
        sameLine()
        if (beginChild(
                "##Msg",
                getColumnWidth() - buttonSize - getStyle().itemSpacingX,
                messageHeight,
                false,
                ImGuiWindowFlags.NoScrollbar or ImGuiWindowFlags.NoBackground
            )
        ) {
            if (mc.user.name.isNotBlank())
                text(mc.user.name)
            val uuid = mc.user.profileId.toString()
            if (uuid?.isNotBlank() == true) {
                text(uuid.substring(0, uuid.length.coerceAtMost(12)) + if (uuid.length > 12) "..." else "")
                if (isItemHovered())
                    setTooltip(uuid)
            }
        }
        endChild()
        sameLine(getWindowWidth() - buttonSize - getStyle().windowPaddingX)
        if (button(FontAwesomeIcons.Copy, "Copy the session data.")) {
            copy(
                JsonObject(
                    mapOf(
                        "username" to JsonPrimitive(mc.user.name),
                        "uuid" to JsonPrimitive(mc.user.profileId.toString()),
                        "type" to JsonPrimitive(currentSessionType.name)
                    )
                ).toString()
            )
        }
        OPEN_SANS_REGULAR.end()
    }

    private fun renderControlContents(requestClose: () -> Unit) {
        OPEN_SANS_REGULAR.begin()
        if (button("Clear", getColumnWidth(), OPEN_SANS_REGULAR.size + getStyle().framePaddingY * 2)) {
            accounts.clear()
        }
        if (button("Close", getColumnWidth(), OPEN_SANS_REGULAR.size + getStyle().framePaddingY * 2)) {
            requestClose()
        }
        OPEN_SANS_REGULAR.end()
    }

    /** Reuse singleton state/actions without the desktop's positioned windows. */
    internal fun renderEmbeddedContents(requestClose: () -> Unit) {
        OPEN_SANS_BOLD.begin()
        try {
            renderEmbeddedSection("ACCOUNTS", ::renderAccountsContents)
            renderEmbeddedSection("SESSION") { renderSessionContents(50f) }
            renderEmbeddedSection("CONTROL") { renderControlContents(requestClose) }
            for (type in Account.Types.entries) {
                renderEmbeddedSection(type.name.replace('_', ' '), type::renderEmbeddedForm)
            }
        } finally {
            OPEN_SANS_BOLD.end()
        }
    }

    private fun renderEmbeddedSection(label: String, contents: () -> Unit) {
        pushID(label)
        try {
            text("\"$label\"")
            separator()
            contents()
            spacing()
        } finally {
            popID()
        }
    }

    override val shouldHandleEvents = true

    init {
        val file = File(Schizoid.root, "accounts.json")
        val aes = AES(HWID.toString())
        runCatching {
            if (file.exists()) {
                val encrypted = file.readBytes()
                val decrypted = aes.decrypt(encrypted)
                val json = Json.parseToJsonElement(decrypted)
                json.jsonArray.forEach { entry ->
                    val obj = entry.jsonObject
                    val type = obj["type"]!!.jsonPrimitive.content
                    runCatching {
                        val account = Account.Types.valueOf(type).create(obj["data"]!!)
                        if (obj["main"]?.jsonPrimitive?.boolean == true) {
                            mainAccount = account
                            thread { account.getSession()?.let { setSession(it) } }
                        }
                        accounts += account
                    }.onFailure {
                        Schizoid.logger.error("Failed to load account.", it)
                    }
                }
            }
        }.onFailure {
            Schizoid.logger.error("Failed to load accounts.", it)
        }.onSuccess {
            Schizoid.logger.info("Loaded ${accounts.size} accounts.")
        }
        on<EventShutdown> {
            runCatching {
                val json = JsonArray(accounts.map {
                    JsonObject(
                        mapOf(
                            "type" to JsonPrimitive(it.type.name),
                            "data" to it.save(),
                            "main" to JsonPrimitive(mainAccount == it)
                        )
                    )
                })
                val encrypted = aes.encrypt(json.toString())
                file.writeBytes(encrypted)
            }.onFailure {
                Schizoid.logger.error("Failed to save accounts.", it)
            }.onSuccess {
                Schizoid.logger.info("Saved ${accounts.size} accounts.")
            }
        }
        on<EventRenderImGuiContent> {
            if (mc.gui.screen() is JoinMultiplayerScreen && !isIngame) {
                setNextWindowPos(20f, 20f, ImGuiCond.FirstUseEver)
                OPEN_SANS_BOLD.begin()
                if (begin("\"${name.uppercase()}\"", ModuleToggleableRenderImGuiContent.WindowFlags.DEFAULT)) {
                    OPEN_SANS_REGULAR.begin()
                    if (button("Open", 150f, OPEN_SANS_REGULAR.size + getStyle().framePaddingY * 2)) {
                        mc.gui.setScreen(this@ImGuiScreenAccountManager)
                    }
                    OPEN_SANS_REGULAR.end()
                }
                end()
                OPEN_SANS_BOLD.end()
            }
        }
    }
}
