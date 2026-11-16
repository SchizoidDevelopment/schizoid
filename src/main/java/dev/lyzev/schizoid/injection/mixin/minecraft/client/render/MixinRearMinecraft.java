package dev.lyzev.schizoid.injection.mixin.minecraft.client.render;

import dev.lyzev.api.opengl.RearViewPipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MixinRearMinecraft {
    @Redirect(method = "renderFrame", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;extract(Lnet/minecraft/client/DeltaTracker;Z)V"))
    private void schizoid$extractRearFirst(GameRenderer renderer, DeltaTracker delta, boolean renderLevel) {
        RearViewPipeline.extract((Minecraft) (Object) this, delta, renderLevel);
        renderer.extract(delta, renderLevel);
    }
    @Redirect(method = "renderFrame", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render(Lnet/minecraft/client/DeltaTracker;Z)V"))
    private void schizoid$drawRearFirst(GameRenderer renderer, DeltaTracker delta, boolean renderLevel) {
        RearViewPipeline.render(delta);
        renderer.render(delta, renderLevel);
    }
    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;tick()V", shift = At.Shift.AFTER))
    private void schizoid$tickRearCamera(CallbackInfo ci) { RearViewPipeline.tick(); }
    @Inject(method = "close", at = @At("HEAD"))
    private void schizoid$closeRear(CallbackInfo ci) { RearViewPipeline.close(); }
    @Inject(method = "updateLevelInEngines(Lnet/minecraft/client/multiplayer/ClientLevel;Z)V", at = @At("HEAD"))
    private void schizoid$resetRear(net.minecraft.client.multiplayer.ClientLevel level, boolean flag, CallbackInfo ci) { RearViewPipeline.close(); }
}
