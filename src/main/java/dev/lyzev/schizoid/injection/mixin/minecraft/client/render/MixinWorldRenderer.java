/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.injection.mixin.minecraft.client.render;

import dev.lyzev.api.opengl.WorldRenderBridge;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = LevelRenderer.class, priority = 900)
public class MixinWorldRenderer {

    @Shadow
    @Final
    private LevelRenderState levelRenderState;

    @Shadow @Final private net.minecraft.client.renderer.GameRenderer gameRenderer;

    // Sodium's merged renderer hook reads Minecraft's primary GameRenderer; each
    // secondary LevelRenderer is already bound to its own owner.
    @org.spongepowered.asm.mixin.injection.Redirect(method = "*", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;gameRenderer:Lnet/minecraft/client/renderer/GameRenderer;", opcode = org.objectweb.asm.Opcodes.GETFIELD), require = 0)
    private net.minecraft.client.renderer.GameRenderer schizoid$routeSodiumGame(net.minecraft.client.Minecraft mc) {
        return dev.lyzev.api.opengl.RearViewPipeline.isDrawing() ? gameRenderer : mc.gameRenderer;
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void initializeRenderBridge(CallbackInfo ci) {
        WorldRenderBridge.initialize();
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void captureDrawArguments(GraphicsResourceAllocator allocator, DeltaTracker timing, boolean blockOutline,
                                      CameraRenderState camera, Matrix4fc viewMatrix, GpuBufferSlice fogBuffer,
                                      Vector4f fogColor, boolean renderSky, CallbackInfo ci) {
        if (!dev.lyzev.api.opengl.RearViewPipeline.isDrawing()) WorldRenderBridge.beginDraw(levelRenderState, timing, camera, viewMatrix);
    }

    @Inject(method = "resetLevelRenderData", at = @At("HEAD"))
    private void clearRenderSnapshot(CallbackInfo ci) {
        WorldRenderBridge.clear();
    }

    // Fabric's END_MAIN is too early for full-scene effects: clouds and fabulous compositing follow it.
    @Inject(method = "render", at = @At("RETURN"))
    private void onRender(CallbackInfo ci) {
        if (!dev.lyzev.api.opengl.RearViewPipeline.isDrawing()) WorldRenderBridge.drawFinalFrame(levelRenderState);
    }
}
