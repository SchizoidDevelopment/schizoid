/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.api.account.accounts

import com.mojang.authlib.Environment
import com.mojang.authlib.exceptions.MinecraftClientHttpException
import com.mojang.authlib.exceptions.InvalidCredentialsException
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import me.lyzev.network.http.HttpClient
import me.lyzev.network.http.HttpHeader
import net.minecraft.client.User
import java.util.*


/**
 * Represents an EasyMC account.
 */
class AccountEasyMC(val token: String) : Account {

    private var isHidden = true
    private val hidden = "*".repeat(token.length)
    private var easyMCResponse: EasyMCResponse? = null

    override val type = Account.Types.EASY_MC

    override fun getSession(): User? {
        if (token.length != 20) {
            msg = "The token must be 20 characters long."
            return null
        }
        runCatching {
            if (easyMCResponse == null) {
                // Redeem the token.
                val response = HttpClient.requestPOST(
                    "https://api.easymc.io/v1/token/redeem",
                    JsonObject(mapOf("token" to JsonPrimitive(token))).toString(),
                    HttpHeader("Content-Type", "application/json"), HttpHeader("User-Agent", "Schizoid")
                )
                easyMCResponse = responseJson.decodeFromString<EasyMCResponse>(response.toString())
            }
            msg = easyMCResponse!!.message
            val session = User(
                easyMCResponse!!.mcName,
                UndashedUuid.fromStringLenient(easyMCResponse!!.uuid),
                easyMCResponse!!.session,
                Optional.empty(),
                Optional.empty()
            )
            applyLoginEnvironment(service, service.createMinecraftSessionService())
            return registerSession(session, type)
        }.onFailure {
            msg =
                if (it is InvalidCredentialsException || it is MinecraftClientHttpException && it.status in listOf(401, 403))
                    "The token is invalid." else "An error occurred while logging in."
            Schizoid.logger.error("EasyMC login failed ({})", it.javaClass.simpleName)
        }
        return null
    }

    override fun save() = JsonPrimitive(token)

    override fun render() {
        super.render()
        text(if (isHidden) hidden else token)
        sameLine(getWindowWidth() - ImGuiScreenAccountManager.buttonSize * 4 - getStyle().itemSpacingX * 3 - getStyle().windowPaddingX)
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

    companion object : Account.Type<AccountEasyMC> {

        private const val SESSION = "https://sessionserver.easymc.io"

        // Authlib now separates session, Minecraft Services and profile lookup hosts.
        val environment = Environment(
            SESSION, YggdrasilEnvironment.PROD.environment.servicesHost(),
            YggdrasilEnvironment.PROD.environment.profilesHost(), "EasyMC"
        )
        val service by lazy { YggdrasilAuthenticationService(Schizoid.mc.proxy, environment) }
        private val responseJson = Json { ignoreUnknownKeys = true }

        private val token = ImString("", 20)
        private var isHidden = true
        private var msg: String? = null
        private val timeAnimator = TimeAnimator(4000)

        override val sessionType = Account.AccountType.MOJANG

        override fun create(json: JsonElement) = AccountEasyMC(json.jsonPrimitive.content)

        override fun render() {
            pushID("easymc")
            setNextWindowPos(getMainViewport().centerX + 270f, getMainViewport().centerY - 470f)
            setNextWindowSize(250f, 0f)
            if (begin("\"EASY MC\"", WINDOW_FLAGS)) {
                renderFormContents()
            }
            end()
            popID()
        }

        override fun renderEmbeddedForm() {
            pushID("easymc")
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
                "##easymc-token",
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
                    val session = AccountEasyMC(token.get()).getSession()
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
                    ImGuiScreenAccountManager.accounts += AccountEasyMC(token.get())
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

    /**
     * Represents an EasyMC response.
     */
    @Serializable
    data class EasyMCResponse(
        val session: String,
        val uuid: String,
        val mcName: String,
        val userId: String = "",
        val microsoft: Boolean = false,
        val msaCredentials: String? = null,
        val message: String? = null
    )

}
