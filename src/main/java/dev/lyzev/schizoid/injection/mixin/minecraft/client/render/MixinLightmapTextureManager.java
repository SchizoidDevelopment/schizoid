/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.injection.mixin.minecraft.client.render;

import dev.lyzev.api.events.EventGamma;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * This class provides a mixin for the LightmapTextureManager class in the Minecraft client render package.
 * It modifies the behavior of the update method of the LightmapTextureManager class.
 */
@Mixin(LightmapRenderStateExtractor.class)
public class MixinLightmapTextureManager {

    /**
     * This method is a redirect for the getValue method of the SimpleOption class.
     * It creates and fires an EventGamma event when the gamma value is retrieved.
     * The gamma value that was retrieved is then replaced by the gamma value of the event.
     *
     * @param instance The instance of the SimpleOption class.
     * @return The gamma value of the event.
     */
    @Inject(method = "extract", at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/state/LightmapRenderState;brightness:F", opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER))
    private void onExtractBrightness(LightmapRenderState state, float tickDelta, CallbackInfo ci) {
        EventGamma event = new EventGamma(state.brightness);
        event.fire();
        state.brightness = event.getGamma();
    }
}
