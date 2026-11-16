/*
 * Copyright (c) 2026. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.injection.mixin.minecraft.client.gui;

import dev.lyzev.api.imgui.ImGuiLoader;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces the vanilla crosshair with the world reticle everywhere, not just while the workspace is
 * open: the reticle is the pointer of the spatial GUI and only *interacts* on panels, so having
 * vanilla draw its own crosshair on the same pixel produced two conflicting pointers.
 *
 * Skipped while ImGui is not ready — a failed GUI init must not leave the player without a crosshair.
 */
@Mixin(Hud.class)
public class MixinHud {

    @Inject(method = "extractCrosshair", at = @At("HEAD"), cancellable = true)
    private void onExtractCrosshair(GuiGraphicsExtractor extractor, DeltaTracker delta, CallbackInfo ci) {
        if (ImGuiLoader.INSTANCE.isReady())
            ci.cancel();
    }
}
