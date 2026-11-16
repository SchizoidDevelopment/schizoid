/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.api.account.accounts

import com.google.gson.JsonObject
import com.mojang.authlib.Environment
import com.mojang.authlib.exceptions.AuthenticationException
import com.mojang.authlib.exceptions.AuthenticationUnavailableException
import com.mojang.authlib.exceptions.InvalidCredentialsException
import com.mojang.authlib.exceptions.MinecraftClientHttpException
import com.mojang.authlib.minecraft.client.MinecraftClient as AuthClient
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService
import com.mojang.authlib.yggdrasil.YggdrasilEnvironment
import com.mojang.util.UndashedUuid
import dev.lyzev.api.account.Account
import dev.lyzev.api.account.Account.Companion.applyLoginEnvironment
import dev.lyzev.api.account.Account.Companion.registerSession
import dev.lyzev.api.account.Account.Companion.setSession
import dev.lyzev.api.animation.EasingFunction
import dev.lyzev.api.animation.TimeAnimator
import dev.lyzev.api.imgui.font.ImGuiFonts.FONT_AWESOME_SOLID
import dev.lyzev.api.imgui.font.ImGuiFonts.OPEN_SANS_REGULAR
import dev.lyzev.api.imgui.font.icon.FontAwesomeIcons
import dev.lyzev.schizoid.Schizoid
import dev.lyzev.schizoid.feature.features.gui.guis.ImGuiScreenAccountManager
import dev.lyzev.schizoid.feature.features.gui.guis.ImGuiScreenAccountManager.WINDOW_FLAGS
import imgui.ImGui.*
import imgui.flag.ImGuiInputTextFlags
import imgui.flag.ImGuiStyleVar
import imgui.flag.ImGuiWindowFlags
import imgui.type.ImString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import net.minecraft.client.User
import java.net.URI
import java.util.*

/**
 * Represents a TheAltening account.
 */
class AccountTheAltening(val token: String) : Account {

    private var isHidden = true
    private val hidden = "*".repeat(token.length)

    override val type = Account.Types.THE_ALTENING

    override fun getSession(): User? {
        // Originally used WaybackAuthLib (https://github.com/FlorianMichael/WaybackAuthLib).
        // Its response model assumes the pre-record GameProfile and cannot deserialize
        // Authlib 9's required properties field. Keep its Yggdrasil authenticate flow,
        // but read selectedProfile explicitly instead of constructing that old model.
        runCatching {
            val clientToken = UUID.randomUUID().toString()
            val request = JsonObject().apply {
                add("agent", JsonObject().apply {
                    addProperty("name", "Minecraft")
                    addProperty("version", 1)
                })
                addProperty("username", token)
                addProperty("password", "Elevating my Minecraft gameplay with ${Schizoid.MOD_NAME}.")
                addProperty("clientToken", clientToken)
            }
            val response = AuthClient.unauthenticated(Schizoid.mc.proxy).post(
                URI("$AUTH/authenticate").toURL(), request, JsonObject::class.java
            ) ?: throw AuthenticationUnavailableException("TheAltening returned no response.")
            if (response.get("clientToken")?.asString != clientToken) {
                throw AuthenticationException("TheAltening returned a different client token.")
            }
            val profile = response.getAsJsonObject("selectedProfile")
                ?: throw AuthenticationException("TheAltening returned no selected Minecraft profile.")
            val name = profile.get("name")?.asString?.takeIf(String::isNotBlank)
                ?: throw AuthenticationException("TheAltening returned no Minecraft name.")
            val uuid = profile.get("id")?.asString
                ?: throw AuthenticationException("TheAltening returned no Minecraft UUID.")
            val accessToken = response.get("accessToken")?.asString?.takeIf(String::isNotBlank)
                ?: throw AuthenticationException("TheAltening returned no session token.")
            val session = User(name, UndashedUuid.fromStringLenient(uuid), accessToken, Optional.empty(), Optional.empty())
            applyLoginEnvironment(service, service.createMinecraftSessionService())
            msg = null
            return registerSession(session, type)
        }.onFailure {
            msg =
                if (it is InvalidCredentialsException || it is MinecraftClientHttpException && it.status in listOf(401, 403))
                    "The token is invalid." else "An error occurred while logging in."
            Schizoid.logger.error("TheAltening login failed ({})", it.javaClass.simpleName)
        }
        return null
    }

    override fun save() = JsonPrimitive(token)

    override fun render() {
        super.render()
        text(if (isHidden) hidden else token)
        sameLine(getColumnWidth() - OPEN_SANS_REGULAR.size * 3f - getStyle().framePaddingX * 3)
        FONT_AWESOME_SOLID.begin()
        val icon = if (isHidden) FontAwesomeIcons.EyeSlash else FontAwesomeIcons.Eye
        val size = calcTextSize(icon)
        pushStyleVar(
            ImGuiStyleVar.FramePadding,
            ImGuiScreenAccountManager.buttonSize / 2f - size.x / 2f,
            -1f
        )
        if (button(icon, ImGuiScreenAccountManager.buttonSize, ImGuiScreenAccountManager.buttonSize)) {
            isHidden = !isHidden
        }
        OPEN_SANS_REGULAR.begin()
        if (isItemHovered())
            setTooltip("Toggle visibility of the token.")
        OPEN_SANS_REGULAR.end()
        FONT_AWESOME_SOLID.end()
        popStyleVar()
    }

    companion object : Account.Type<AccountTheAltening> {

        private const val AUTH = "http://authserver.thealtening.com"
        private const val SESSION = "http://sessionserver.thealtening.com"

        val environment = Environment(
            SESSION, YggdrasilEnvironment.PROD.environment.servicesHost(),
            YggdrasilEnvironment.PROD.environment.profilesHost(), "TheAltening"
        )
        val service by lazy { YggdrasilAuthenticationService(Schizoid.mc.proxy, environment) }

        private val token = ImString("", 32)
        private var isHidden = true
        private var msg: String? = null
        private val timeAnimator = TimeAnimator(4000)

        override val sessionType = Account.AccountType.MOJANG

        override fun create(json: JsonElement) = AccountTheAltening(json.jsonPrimitive.content)

        override fun render() {
            pushID("thealtening")
            setNextWindowPos(getMainViewport().centerX + 270f, getMainViewport().centerY - 330f)
            setNextWindowSize(250f, 0f)
            if (begin("\"THE ALTENING\"", WINDOW_FLAGS)) {
                renderFormContents()
            }
            end()
            popID()
        }

        override fun renderEmbeddedForm() {
            pushID("thealtening")
            try {
                renderFormContents()
            } finally {
                popID()
            }
        }

        private fun renderFormContents() {
            OPEN_SANS_REGULAR.begin()
            setNextItemWidth(getWindowWidth() - ImGuiScreenAccountManager.buttonSize - getStyle().itemSpacingX - getStyle().windowPaddingX * 2)
            inputTextWithHint(
                "##thealtening-token",
                "Token",
                token,
                if (isHidden) ImGuiInputTextFlags.Password else ImGuiInputTextFlags.None
            )
            sameLine()
            val icon = if (isHidden) FontAwesomeIcons.EyeSlash else FontAwesomeIcons.Eye
            if (ImGuiScreenAccountManager.button(icon, "Toggle visibility of the token.")) {
                isHidden = !isHidden
            }
            if (ImGuiScreenAccountManager.button(FontAwesomeIcons.SignInAlt, "Login to the account.")) {
                if (token.get().isNotBlank()) {
                    val session = AccountTheAltening(token.get()).getSession()
                    if (session != null) {
                        setSession(session)
                    }
                } else {
                    msg = "The provided token is empty."
                }
            }
            sameLine()
            if (ImGuiScreenAccountManager.button(FontAwesomeIcons.Plus, "Add the account.")) {
                if (token.get().isNotBlank()) {
                    ImGuiScreenAccountManager.accounts += AccountTheAltening(token.get())
                } else {
                    msg = "The provided token is empty."
                }
            }
            sameLine()
            if (msg != null) {
                pushStyleVar(ImGuiStyleVar.WindowPadding, 0f, 0f)
                val size = calcTextSize(msg)
                if (beginChild(
                        "##Msg",
                        getColumnWidth(),
                        size.y,
                        false,
                        ImGuiWindowFlags.NoScrollbar or ImGuiWindowFlags.NoBackground
                    )
                ) {
                    if (size.x > getColumnWidth()) {
                        val progress = timeAnimator.getProgressNotClamped()
                        if (progress < -.2 || progress > 1.2) timeAnimator.setReversed(!timeAnimator.reversed)
                        setScrollX((EasingFunction.LINEAR(timeAnimator.getProgress()) * (size.x - getColumnWidth())).toFloat())
                    }
                    textColored(0xFF0000FF.toInt(), msg)
                }
                endChild()
                popStyleVar()
            }
            OPEN_SANS_REGULAR.end()
        }
    }
}
