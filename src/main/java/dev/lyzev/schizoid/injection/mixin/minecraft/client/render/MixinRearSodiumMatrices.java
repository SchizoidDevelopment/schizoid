package dev.lyzev.schizoid.injection.mixin.minecraft.client.render;

import dev.lyzev.api.opengl.RearViewPipeline;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Sodium otherwise reads the primary GameRenderer projection during a rear draw. */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices", remap = false)
public abstract class MixinRearSodiumMatrices {
    @ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true, ordinal = 0, remap = false)
    private static Matrix4fc schizoid$rearProjection(Matrix4fc original) {
        return RearViewPipeline.isDrawing() ? RearViewPipeline.sodiumProjection() : original;
    }
}
