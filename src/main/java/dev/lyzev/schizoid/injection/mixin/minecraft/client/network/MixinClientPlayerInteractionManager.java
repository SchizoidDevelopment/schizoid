/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.injection.mixin.minecraft.client.network;

import dev.lyzev.api.events.EventAttackEntityPost;
import dev.lyzev.api.events.EventAttackEntityPre;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * This class provides mixins for the ClientPlayerInteractionManager class in the Minecraft client network package.
 * It modifies the behavior of the attackEntity method of the ClientPlayerInteractionManager class.
 */
@Mixin(MultiPlayerGameMode.class)
public class MixinClientPlayerInteractionManager {

    /**
     * This method is a mixin for the attackEntity method of the ClientPlayerInteractionManager class.
     * It fires an EventAttackEntity event at the start of the method.
     *
     * @param player The player entity.
     * @param target The target entity.
     * @param ci     The callback information.
     */
    @Inject(method = "attack", at = @At("HEAD"))
    private void onAttackEntityPre(Player player, Entity target, CallbackInfo ci) {
        EventAttackEntityPre event = new EventAttackEntityPre(player, target);
        event.fire();
    }

    @Inject(method = "attack", at = @At("RETURN"))
    private void onAttackEntityPost(Player player, Entity target, CallbackInfo ci) {
        EventAttackEntityPost event = new EventAttackEntityPost(player, target);
        event.fire();
    }
}
