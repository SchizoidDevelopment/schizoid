/*
 * Copyright (c) 2026. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.injection.mixin.minecraft.client.render;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.lyzev.api.events.EventGetFOV;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Modify the calculated value so vanilla and other mods share the same projection/FOV. */
@Mixin(Camera.class)
public class MixinCameraFov {

    @ModifyReturnValue(method = {"calculateFov", "calculateHudFov"}, at = @At("RETURN"))
    private float onGetFov(float original) {
        EventGetFOV event = new EventGetFOV(original);
        event.fire();
        return (float) event.getFov();
    }
}
