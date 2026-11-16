/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.injection.accessor;

import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.yggdrasil.ProfileResult;
import com.mojang.realmsclient.gui.RealmsDataFetcher;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.screens.social.PlayerSocialManager;
import net.minecraft.client.gui.screens.social.RemoteFriendListUpdateHandler;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;
import net.minecraft.client.multiplayer.chat.report.ReportingContext;
import net.minecraft.client.resources.SkinManager;
import net.minecraft.client.telemetry.ClientTelemetryManager;
import net.minecraft.server.Services;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.net.Proxy;
import java.util.concurrent.CompletableFuture;

/**
 * Provides the means to access protected members of the Minecraft client.
 */
@Mixin(Minecraft.class)
public interface MinecraftClientAccessor {
    @Accessor("missTime")
    int getAttackCooldown();

    /**
     * Sets the Minecraft session.
     *
     * @param session new Minecraft session
     */
    @Accessor("user")
    @Mutable
    void setSession(User session);

    /**
     * Sets the game profile.
     *
     * @param future the future of the new game profile
     */
    @Accessor("profileFuture")
    @Mutable
    void setGameProfileFuture(CompletableFuture<ProfileResult> future);

    /**
     * Sets the services used for session joins, profiles and signature validation.
     *
     * @param services new Minecraft services
     */
    @Accessor("services")
    @Mutable
    void setServices(Services services);

    @Accessor("proxy")
    Proxy getNetworkProxy();

    /**
     * Sets the Minecraft user API service.
     *
     * @param userApiService new Minecraft user API service
     */
    @Accessor("userApiService")
    @Mutable
    void setUserApiService(UserApiService userApiService);

    /**
     * Sets the cached permissions and restrictions for the current user.
     *
     * @param future the future of the new user properties
     */
    @Accessor("userPropertiesFuture")
    @Mutable
    void setUserPropertiesFuture(CompletableFuture<UserApiService.UserProperties> future);

    /**
     * Sets the Minecraft skin provider.
     *
     * @param skinProvider new Minecraft skin provider
     */
    @Accessor("skinManager")
    @Mutable
    void setSkinProvider(SkinManager skinProvider);

    /**
     * Sets the Minecraft social interactions manager.
     *
     * @param socialInteractionsManager new Minecraft social interactions manager
     */
    @Accessor("playerSocialManager")
    @Mutable
    void setSocialInteractionsManager(PlayerSocialManager socialInteractionsManager);

    @Accessor("remoteFriendListUpdateHandler")
    RemoteFriendListUpdateHandler getRemoteFriendListUpdateHandler();

    @Accessor("remoteFriendListUpdateHandler")
    @Mutable
    void setRemoteFriendListUpdateHandler(RemoteFriendListUpdateHandler handler);

    /**
     * Sets the Minecraft profile keys.
     *
     * @param profileKeys new Minecraft profile keys
     */
    @Accessor("profileKeyPairManager")
    @Mutable
    void setProfileKeys(ProfileKeyPairManager profileKeys);

    /**
     * Sets the Minecraft abuse report context.
     *
     * @param abuseReportContext new Minecraft abuse report context
     */
    @Accessor("reportingContext")
    @Mutable
    void setAbuseReportContext(ReportingContext abuseReportContext);

    /**
     * Sets the Minecraft Realms periodic checkers.
     *
     * @param realmsPeriodicCheckers new Minecraft Realms periodic checkers
     */
    @Accessor("realmsDataFetcher")
    @Mutable
    void setRealmsPeriodicCheckers(RealmsDataFetcher realmsPeriodicCheckers);

    @Accessor("telemetryManager")
    @Mutable
    void setTelemetryManager(ClientTelemetryManager telemetryManager);
}
