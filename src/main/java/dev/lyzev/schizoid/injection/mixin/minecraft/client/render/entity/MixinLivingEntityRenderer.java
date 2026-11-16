/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.injection.mixin.minecraft.client.render.entity;

import dev.lyzev.api.events.EventClientPlayerEntityRender;
import dev.lyzev.schizoid.Schizoid;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * This class provides a mixin for the LivingEntityRenderer class in the Minecraft client render entity package.
 * It modifies the behavior of the render method of the LivingEntityRenderer class.
 */
@Mixin(LivingEntityRenderer.class)
public class MixinLivingEntityRenderer {

    @Shadow
    private static float solveBodyRot(LivingEntity entity, float tickDelta, float headYaw) {
        throw new AssertionError("Replaced by Mixin with the vanilla body rotation calculation");
    }

    /** Update the render snapshot, not the live entity used by input and networking. */
    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V", at = @At("TAIL"))
    private void onExtractRenderState(LivingEntity entity, LivingEntityRenderState state, float tickDelta, CallbackInfo ci) {
        if (entity != Schizoid.INSTANCE.getMc().player) return;

        EventClientPlayerEntityRender event = new EventClientPlayerEntityRender(
                entity.yHeadRot, entity.yHeadRotO, entity.getXRot(), entity.xRotO);
        event.fire();
        boolean headChanged = event.getHeadYaw() != entity.yHeadRot || event.getPrevHeadYaw() != entity.yHeadRotO;
        boolean pitchChanged = event.getPitch() != entity.getXRot() || event.getPrevPitch() != entity.xRotO;
        if (headChanged) {
            float headYaw = Mth.rotLerp(tickDelta, event.getPrevHeadYaw(), event.getHeadYaw());
            state.bodyRot = solveBodyRot(entity, tickDelta, headYaw);
            state.yRot = Mth.wrapDegrees(headYaw - state.bodyRot) * (state.isUpsideDown ? -1 : 1);
        }
        if (pitchChanged) {
            state.xRot = Mth.lerp(tickDelta, event.getPrevPitch(), event.getPitch()) * (state.isUpsideDown ? -1 : 1);
        }
    }
}
