/*
 * Copyright (c) 2026. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.injection.mixin.minecraft.client.render;

import dev.lyzev.api.opengl.RearViewCamera;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import net.minecraft.client.Options;
import net.minecraft.client.CameraType;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class MixinRearViewCamera {
    @Shadow protected abstract void setRotation(float yaw, float pitch);
    @Shadow public abstract float yRot();
    @Shadow public abstract float xRot();

    @Redirect(method = "alignWithEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Options;getCameraType()Lnet/minecraft/client/CameraType;"))
    private CameraType schizoid$rearFirstPerson(Options options) {
        return (Object) this instanceof RearViewCamera ? CameraType.FIRST_PERSON : options.getCameraType();
    }

    @Inject(method = "update(Lnet/minecraft/client/DeltaTracker;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;alignWithEntity(F)V", shift = At.Shift.AFTER), require = 1)
    private void schizoid$orientRearView(DeltaTracker deltaTracker, CallbackInfo ci) {
        if ((Object) this instanceof RearViewCamera) {
            setRotation(yRot() + 180.0F, -xRot());
        }
    }
}
