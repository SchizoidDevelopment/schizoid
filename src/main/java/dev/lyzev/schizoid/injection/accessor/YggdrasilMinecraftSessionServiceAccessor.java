/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.injection.accessor;

import com.mojang.authlib.Environment;
import com.mojang.authlib.yggdrasil.ServicesKeySet;
import com.mojang.authlib.yggdrasil.YggdrasilMinecraftSessionService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.net.Proxy;

@Mixin(value = YggdrasilMinecraftSessionService.class, remap = false)
public interface YggdrasilMinecraftSessionServiceAccessor {

    @Invoker(value = "<init>", remap = false)
    static YggdrasilMinecraftSessionService createYggdrasilMinecraftSessionService(final ServicesKeySet servicesKeySet, final Proxy proxy, final Environment env) {
        throw new UnsupportedOperationException();
    }
}
