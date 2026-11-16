/*
 * Copyright (c) 2026. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.injection.mixin.minecraft.client.render;

import dev.lyzev.api.opengl.WorldRenderBridge;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(GameRenderer.class)
public class MixinProjectionGameRenderer {
    @Shadow @Final
    private GameRenderState gameRenderState;

    // This is the world buffer upload after hurt/view bob and portal/nausea transforms.
    @ModifyArg(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;", ordinal = 0),
            index = 0, require = 1, allow = 1)
    private Matrix4f captureWorldProjection(Matrix4f projection) {
        if (!dev.lyzev.api.opengl.RearViewPipeline.isRearRenderer((GameRenderer) (Object) this)) {
            WorldRenderBridge.captureFinalProjection(gameRenderState.levelRenderState, projection);
        }
        return projection;
    }
}
