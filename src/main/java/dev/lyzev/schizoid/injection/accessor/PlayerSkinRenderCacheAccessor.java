/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.injection.accessor;

import com.google.common.cache.LoadingCache;
import net.minecraft.client.renderer.PlayerSkinRenderCache;
import net.minecraft.client.resources.SkinManager;
import net.minecraft.server.players.ProfileResolver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PlayerSkinRenderCache.class)
public interface PlayerSkinRenderCacheAccessor {

    @Accessor("skinManager")
    @Mutable
    void setSkinManager(SkinManager skinManager);

    @Accessor("profileResolver")
    @Mutable
    void setProfileResolver(ProfileResolver profileResolver);

    @Accessor("renderInfoCache")
    LoadingCache<?, ?> getRenderInfoCache();

    @Accessor("defaultSkinCache")
    LoadingCache<?, ?> getDefaultSkinCache();
}
