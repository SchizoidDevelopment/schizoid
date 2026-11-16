/*
 * Copyright (c) 2023-2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.injection.mixin.minecraft.client.util;

import dev.lyzev.api.events.EventSwapBuffers;
import com.mojang.blaze3d.opengl.GlSurface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The OpenGL surface owns buffer swapping in 26.2. GLFW initialization is handled once by CLIENT_STARTED.
 */
@Mixin(GlSurface.class)
public class MixinWindow {
    @Inject(method = "present", at = @At("HEAD"))
    private void onSwapBuffers(CallbackInfo ci) {
        EventSwapBuffers.INSTANCE.fire();
    }
}
