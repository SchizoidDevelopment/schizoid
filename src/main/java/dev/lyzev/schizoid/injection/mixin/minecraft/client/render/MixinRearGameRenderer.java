package dev.lyzev.schizoid.injection.mixin.minecraft.client.render;

import dev.lyzev.api.opengl.RearViewPipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class MixinRearGameRenderer {
    @Shadow @Final @Mutable private Camera mainCamera;
    @Shadow @Final private net.minecraft.client.renderer.fog.FogRenderer fogRenderer;
    @Shadow @Final private net.minecraft.client.renderer.RenderBuffers renderBuffers;
    @Shadow @Final private com.mojang.blaze3d.resource.CrossFrameResourcePool resourcePool;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void schizoid$installRearCamera(CallbackInfo ci) {
        if (RearViewPipeline.isConstructing()) mainCamera = new dev.lyzev.api.opengl.RearViewCamera();
    }

    @Redirect(method = "extract", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;levelExtractor:Lnet/minecraft/client/renderer/extract/LevelExtractor;", opcode = Opcodes.GETFIELD))
    private LevelExtractor schizoid$routeExtractor(Minecraft minecraft) {
        return RearViewPipeline.extractorFor((GameRenderer) (Object) this, minecraft.levelExtractor);
    }

    @Inject(method = "extract", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;gui:Lnet/minecraft/client/gui/Gui;", opcode = Opcodes.GETFIELD), cancellable = true)
    private void schizoid$skipGuiExtraction(net.minecraft.client.DeltaTracker delta, boolean renderLevel, CallbackInfo ci) {
        if (RearViewPipeline.isRearRenderer((GameRenderer) (Object) this)) ci.cancel();
    }

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V", shift = At.Shift.AFTER), cancellable = true)
    private void schizoid$skipRearGui(net.minecraft.client.DeltaTracker delta, boolean renderLevel, CallbackInfo ci) {
        if (RearViewPipeline.isRearRenderer((GameRenderer) (Object) this)) {
            fogRenderer.endFrame();
            renderBuffers.endFrame();
            resourcePool.endFrame();
            net.minecraft.util.profiling.Profiler.get().pop();
            net.minecraft.util.profiling.Profiler.get().pop();
            ci.cancel();
        }
    }

    @Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V", shift = At.Shift.AFTER), cancellable = true)
    private void schizoid$skipRearHands(net.minecraft.client.DeltaTracker delta, CallbackInfo ci) {
        if (RearViewPipeline.isRearRenderer((GameRenderer) (Object) this)) {
            net.minecraft.util.profiling.Profiler.get().pop();
            ci.cancel();
        }
    }

    @Redirect(method = "renderLevel", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;levelRenderer:Lnet/minecraft/client/renderer/LevelRenderer;", opcode = Opcodes.GETFIELD))
    private LevelRenderer schizoid$routeLevelRenderer(Minecraft minecraft) {
        return RearViewPipeline.rendererFor((GameRenderer) (Object) this, minecraft.levelRenderer);
    }
}
