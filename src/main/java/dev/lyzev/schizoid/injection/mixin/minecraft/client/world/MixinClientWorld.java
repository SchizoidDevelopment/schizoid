/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.injection.mixin.minecraft.client.world;

import dev.lyzev.api.events.EventBlockParticle;
import net.minecraft.world.level.block.Block;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * This class provides a mixin for the ClientWorld class in the Minecraft client world package.
 * It modifies the behavior of the getBlockParticle method of the ClientWorld class.
 */
@Mixin(ClientLevel.class)
public class MixinClientWorld {

    /**
     * This method is a mixin for the getBlockParticle method of the ClientWorld class.
     * It creates and fires an EventBlockParticle event after the block particle is retrieved.
     * The block particle that was retrieved is then replaced by the block particle of the event.
     *
     * @param cir The callback information, which includes the return value of the block particle retrieval.
     */
    @Inject(method = "getMarkerParticleTarget", at = @At("RETURN"), cancellable = true)
    private void onGetBlockParticle(CallbackInfoReturnable<Block> cir) {
        EventBlockParticle event = new EventBlockParticle(cir.getReturnValue());
        event.fire();
        cir.setReturnValue(event.getBlock());
    }
}
