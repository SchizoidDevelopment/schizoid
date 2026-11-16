/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.injection.mixin.minecraft.client;

import dev.lyzev.api.events.*;
import net.minecraft.client.Minecraft;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/**
 * Gameplay hooks that are not exposed by Fabric lifecycle events.
 * Startup is handled by Schizoid's client entrypoint and CLIENT_STARTED.
 */
@Mixin(Minecraft.class)
public class MixinMinecraftClient {

    @Shadow
    private int rightClickDelay;

    /**
     * This method is a mixin for the onResolutionChanged method of the MinecraftClient class.
     * It fires an EventWindowResize event at the end of the method.
     *
     * @param ci The callback information.
     */
    @Inject(method = "framebufferSizeChanged", at = @At("RETURN"))
    private void onResolutionChanged(CallbackInfo ci) {
        EventWindowResize.INSTANCE.fire();
    }

    /**
     * This method is a mixin for the doItemUse method of the MinecraftClient class.
     * It creates and fires an EventItemUse event before the isRiding method of the ClientPlayerEntity class is invoked.
     * The item use cooldown of the MinecraftClient instance is then replaced by the item use cooldown of the event.
     *
     * @param ci The callback information.
     */
    @Inject(method = "startUseItem", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;rightClickDelay:I", opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER, ordinal = 0))
    private void onItemUse(CallbackInfo ci) {
        EventItemUse event = new EventItemUse(this.rightClickDelay);
        event.fire();
        this.rightClickDelay = event.getItemUseCooldown();
    }

    @Inject(method = "reloadResourcePacks()Ljava/util/concurrent/CompletableFuture;", at = @At(value = "HEAD"))
    private void onReloadResources(CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        EventReloadShader.INSTANCE.fire();
    }

    @Inject(method = "pick", at = @At("RETURN"))
    private void onTick(CallbackInfo ci) {
        EventUpdateCrosshairTargetTick.INSTANCE.fire();
    }
}
