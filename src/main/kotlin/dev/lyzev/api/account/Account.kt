/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.api.account

import com.mojang.authlib.minecraft.MinecraftSessionService
import com.mojang.authlib.minecraft.UserApiService
import com.mojang.authlib.yggdrasil.ProfileResult
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService
import com.mojang.realmsclient.client.RealmsClient
import com.mojang.realmsclient.gui.RealmsDataFetcher
import dev.lyzev.api.account.accounts.AccountCracked
import dev.lyzev.api.account.accounts.AccountEasyMC
import dev.lyzev.api.account.accounts.AccountMicrosoft
import dev.lyzev.api.account.accounts.AccountTheAltening
import dev.lyzev.api.imgui.font.ImGuiFonts
import dev.lyzev.api.imgui.render.ImGuiRenderable
import dev.lyzev.schizoid.Schizoid
import dev.lyzev.schizoid.injection.accessor.*
import imgui.ImGui.text
import kotlinx.serialization.json.JsonElement
import net.minecraft.client.User
import net.minecraft.client.gui.screens.social.PlayerSocialManager
import net.minecraft.client.gui.screens.social.RemoteFriendListUpdateHandler
import net.minecraft.client.multiplayer.ProfileKeyPairManager
import net.minecraft.client.multiplayer.chat.report.ReportingContext
import net.minecraft.client.player.LocalPlayerResolver
import net.minecraft.client.renderer.texture.SkinTextureDownloader
import net.minecraft.client.resources.SkinManager
import net.minecraft.client.telemetry.ClientTelemetryManager
import net.minecraft.server.Services
import net.minecraft.server.players.CachedUserNameToIdResolver
import net.minecraft.server.players.NameAndId
import net.minecraft.server.players.ProfileResolver
import net.minecraft.util.Util
import java.nio.file.Files
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.CompletableFuture

/**
 * Represents an account.
 */
interface Account : ImGuiRenderable {

    /**
     * The type of the account.
     */
    val type: Types

    /**
     * Returns the session of the account.
     */
    fun getSession(): User?

    /**
     * Saves the account to a JSON element.
     *
     * @return the JSON element
     */
    fun save(): JsonElement

    override fun render() {
        ImGuiFonts.OPEN_SANS_BOLD.begin()
        text(type.name)
        ImGuiFonts.OPEN_SANS_BOLD.end()
    }

    companion object {

        private data class LoginEnvironment(
            val authenticationService: YggdrasilAuthenticationService,
            val sessionService: MinecraftSessionService,
            val type: Types? = null
        )

        private val pendingLoginEnvironment = ThreadLocal<LoginEnvironment>()
        private val sessionEnvironments = Collections.synchronizedMap(WeakHashMap<User, LoginEnvironment>())

        @Volatile
        private var appliedSessionType: AccountType? = null

        internal val officialAuthenticationService by lazy {
            YggdrasilAuthenticationService(Schizoid.mc.proxy)
        }

        /** Minecraft 26.2 no longer stores an account type in User. */
        val currentSessionType: AccountType
            get() = appliedSessionType ?: if (Schizoid.mc.user.accessToken.isBlank()) AccountType.LEGACY else AccountType.MSA

        /** Keep the environment attached to its User even when the caller changes threads. */
        internal fun registerSession(session: User, type: Types): User {
            val environment = pendingLoginEnvironment.get() ?: LoginEnvironment(
                officialAuthenticationService, officialAuthenticationService.createMinecraftSessionService()
            )
            pendingLoginEnvironment.remove()
            sessionEnvironments[session] = environment.copy(type = type)
            return session
        }

        /**
         * Sets the Minecraft session to the specified session.
         * Credit to [Auth Me](https://github.com/axieum/authme) for the original [code](https://github.com/axieum/authme/blob/799718b2ed7986139e480131b8e0827f80e0307e/src/main/java/me/axieum/mcmod/authme/api/util/SessionUtils.java#L58).
         * This code is licensed under the MIT license.
         *
         * @param session the session to set
         */
        fun setSession(session: User) {
            val mc = Schizoid.mc
            val environment = sessionEnvironments.remove(session) ?: pendingLoginEnvironment.get() ?: LoginEnvironment(
                officialAuthenticationService, officialAuthenticationService.createMinecraftSessionService()
            )
            pendingLoginEnvironment.remove()
            val sessionType = environment.type?.sessionType ?: if (session.accessToken.isBlank()) AccountType.LEGACY else AccountType.MSA

            // Only Microsoft sessions carry a Minecraft Services bearer token. Third-party
            // sessions still use their real session server, but must not fetch Mojang keys
            // or friend data with an EasyMC/TheAltening token.
            val userApiService = if (sessionType == AccountType.MSA && session.accessToken.isNotBlank()) {
                environment.authenticationService.createUserApiService(session.accessToken)
            } else UserApiService.OFFLINE
            val userProperties = runCatching { userApiService.fetchProperties() }.getOrElse {
                Schizoid.logger.warn("Failed to fetch user properties; using Minecraft offline defaults ({})", it.javaClass.simpleName)
                UserApiService.OFFLINE_PROPERTIES
            }

            // Separate provider caches instead of rewriting the launcher's usercache.json.
            val cacheDirectory = Schizoid.root.toPath().resolve("profile-cache")
                .resolve(environment.type?.name?.lowercase(Locale.ROOT) ?: sessionType.name.lowercase(Locale.ROOT))
            Files.createDirectories(cacheDirectory)
            val profileRepository = environment.authenticationService.createProfileRepository()
            val nameToIdCache = CachedUserNameToIdResolver(profileRepository, cacheDirectory.resolve("usercache.json").toFile())
            nameToIdCache.add(NameAndId(session.profileId, session.name))
            val services = Services(
                environment.sessionService,
                environment.authenticationService.servicesKeySet,
                profileRepository,
                nameToIdCache,
                ProfileResolver.Cached(environment.sessionService, nameToIdCache)
            )
            val profileFuture = CompletableFuture.supplyAsync<ProfileResult>({
                services.sessionService().fetchProfile(session.profileId, true)
            }, Util.nonCriticalIoPool()).exceptionally {
                Schizoid.logger.warn("Failed to fetch the account profile ({})", it.javaClass.simpleName)
                null
            }
            val friendsService = environment.authenticationService.createFriendsService(
                if (userApiService === UserApiService.OFFLINE) "" else session.accessToken
            )

            // Account logins also run on worker threads. Change the client state together,
            // on the client thread, rather than mixing two simultaneous login environments.
            mc.executeBlocking {
                check(mc.level == null && mc.connection == null) { "Disconnect from the world before switching accounts." }
                val accessor = mc as MinecraftClientAccessor
                val skinTextures = (mc.skinManager as PlayerSkinProviderAccessor).skinCache
                val skinCachePath = (skinTextures as FileCacheAccessor).directory
                val skinManager = SkinManager(
                    skinCachePath, services, SkinTextureDownloader(mc.proxy, mc.textureManager, mc), mc
                )
                val profileKeys = if (userApiService === UserApiService.OFFLINE) ProfileKeyPairManager.EMPTY_KEY_MANAGER
                else ProfileKeyPairManager.create(userApiService, session, mc.gameDirectory.toPath())
                val reportingContext = ReportingContext.create(
                    (mc.reportingContext as AbuseReportContextAccessor).environment, userApiService
                )

                accessor.remoteFriendListUpdateHandler.close()
                mc.playerSocialManager.stopOnlineMode()
                mc.telemetryManager.close()
                accessor.setSession(session)
                accessor.setServices(services)
                accessor.setGameProfileFuture(profileFuture)
                accessor.setUserApiService(userApiService)
                accessor.setUserPropertiesFuture(CompletableFuture.completedFuture(userProperties))
                (mc.gui.splashManager() as SplashTextResourceSupplierAccessor).setSession(session)
                accessor.setSkinProvider(skinManager)
                accessor.setProfileKeys(profileKeys)
                accessor.setAbuseReportContext(reportingContext)
                accessor.setTelemetryManager(ClientTelemetryManager(mc, userApiService, session))

                val friendListHandler = RemoteFriendListUpdateHandler(friendsService, mc)
                val socialManager = PlayerSocialManager(mc, userApiService, friendsService, friendListHandler)
                accessor.setRemoteFriendListUpdateHandler(friendListHandler)
                accessor.setSocialInteractionsManager(socialManager)
                if (socialManager.isFriendListEnabled) friendListHandler.start()

                // Font, model and entity renderers all retain this cache object. Update its
                // dependencies in place; replacing only Minecraft.skinManager leaves them stale.
                val renderCache = mc.playerSkinRenderCache() as PlayerSkinRenderCacheAccessor
                renderCache.setSkinManager(skinManager)
                renderCache.setProfileResolver(LocalPlayerResolver(mc, services.profileResolver()))
                renderCache.renderInfoCache.invalidateAll()
                renderCache.defaultSkinCache.invalidateAll()

                val downloadedPacks = mc.downloadedPackSource as DownloadedPackSourceAccessor
                (downloadedPacks.manager as ServerPackManagerAccessor).setDownloader(
                    downloadedPacks.invokeCreateDownloader(downloadedPacks.downloadQueue, mc, session, mc.proxy)
                )

                // RealmsClient now caches the user itself, not just the availability result.
                RealmsClientAccessor.setInstance(null)
                RealmsAvailabilityAccessor.setCurrentFuture(null)
                accessor.setRealmsPeriodicCheckers(RealmsDataFetcher(RealmsClient.getOrCreate(mc)))
                appliedSessionType = sessionType
                Schizoid.logger.info("Minecraft session for {} (uuid={}) has been applied", session.name, session.profileId)
            }
        }

        /**
         * Prepares the login environment for the next session on this thread.
         * Credit to [Meteor Client](https://github.com/MeteorDevelopment/meteor-client) for the original [code](https://github.com/MeteorDevelopment/meteor-client/blob/546d0b945a98c61cafd4cd9330db3936eee286d1/src/main/java/meteordevelopment/meteorclient/systems/accounts/Account.java#L78).
         * This code is licensed under the GPL-3.0 license.
         * The environment is installed together with User in setSession so a failed or
         * concurrent login cannot change the active account's services prematurely.
         *
         * @param authService the authentication service
         * @param sessService the session service
         */
        fun applyLoginEnvironment(authService: YggdrasilAuthenticationService, sessService: MinecraftSessionService?) {
            pendingLoginEnvironment.set(LoginEnvironment(authService, sessService ?: authService.createMinecraftSessionService()))
        }
    }

    /** Account metadata retained after Minecraft removed User.Type. */
    enum class AccountType {
        LEGACY, MOJANG, MSA
    }

    /**
     * Represents a type of account.
     */
    interface Type<T : Account> : ImGuiRenderable {

        /**
         * The session type of the account.
         */
        val sessionType: AccountType

        /**
         * Creates an account from a JSON element.
         */
        fun create(json: JsonElement): T

        /** Render the original provider form in the caller-owned window/child. */
        fun renderEmbeddedForm()
    }

    /**
     * Contains all types of accounts.
     */
    enum class Types(private val type: Type<*>) : Type<Account> {
        CRACKED(AccountCracked.Companion),
        EASY_MC(AccountEasyMC.Companion),
        THE_ALTENING(AccountTheAltening.Companion),
        MICROSOFT(AccountMicrosoft.Companion);

        override val sessionType = type.sessionType

        override fun create(json: JsonElement) = type.create(json)

        override fun render() = type.render()

        override fun renderEmbeddedForm() = type.renderEmbeddedForm()
    }
}
