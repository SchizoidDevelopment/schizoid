/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.injection.mixin.minecraft.client.render;

import dev.lyzev.api.events.EventClipToSpace;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * This class provides a mixin for the Camera class in the Minecraft client render package.
 * It modifies the behavior of the clipToSpace method of the Camera class.
 */
@Mixin(Camera.class)
public class MixinCamera {

    /**
     * This method is a mixin for the clipToSpace method of the Camera class.
     * It creates and fires an EventClipToSpace event after the desired camera distance is calculated.
     * The desired camera distance that was calculated is then replaced by the desired camera distance of the event.
     *
     * @param desiredCameraDistance The desired camera distance that was calculated.
     * @param cir                   The callback information, which includes the return value of the desired camera distance calculation.
     */
    @Inject(method = "getMaxZoom", at = @At("RETURN"), cancellable = true)
    private void onClipToSpace(float desiredCameraDistance, CallbackInfoReturnable<Float> cir) {
        EventClipToSpace event = new EventClipToSpace(cir.getReturnValue());
        event.fire();
        cir.setReturnValue(event.getDesiredCameraDistance());
    }
}
