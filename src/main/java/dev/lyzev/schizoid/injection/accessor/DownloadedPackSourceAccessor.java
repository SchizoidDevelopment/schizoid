/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.injection.accessor;

import net.minecraft.client.User;
import net.minecraft.client.resources.server.DownloadedPackSource;
import net.minecraft.client.resources.server.PackDownloader;
import net.minecraft.client.resources.server.ServerPackManager;
import net.minecraft.server.packs.DownloadQueue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.net.Proxy;
import java.util.concurrent.Executor;

@Mixin(DownloadedPackSource.class)
public interface DownloadedPackSourceAccessor {

    @Accessor("manager")
    ServerPackManager getManager();

    @Accessor("downloadQueue")
    DownloadQueue getDownloadQueue();

    @Invoker("createDownloader")
    PackDownloader invokeCreateDownloader(DownloadQueue queue, Executor executor, User user, Proxy proxy);
}
