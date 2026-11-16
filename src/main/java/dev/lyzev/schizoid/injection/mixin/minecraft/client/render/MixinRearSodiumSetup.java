package dev.lyzev.schizoid.injection.mixin.minecraft.client.render;

import dev.lyzev.api.opengl.RearViewPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Signal only after Sodium finishes preparing its secondary terrain state. */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer", remap = false)
public abstract class MixinRearSodiumSetup {
    @Inject(method = "setupTerrain", at = @At("RETURN"), require = 0, remap = false)
    private void schizoid$terrainPrepared(CallbackInfo ci) {
        if (RearViewPipeline.isExtracting()) RearViewPipeline.markSodiumTerrainPrepared();
    }
}
