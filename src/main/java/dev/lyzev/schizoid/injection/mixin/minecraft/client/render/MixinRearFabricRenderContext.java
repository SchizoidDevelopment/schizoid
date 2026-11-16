package dev.lyzev.schizoid.injection.mixin.minecraft.client.render;

import dev.lyzev.api.opengl.RearViewPipeline;
import net.fabricmc.fabric.impl.client.rendering.level.LevelRenderContextImpl;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Fabric prepares secondary LevelRenderer events using the primary GameRenderer by default. */
@Mixin(value = LevelRenderContextImpl.class, remap = false)
public abstract class MixinRearFabricRenderContext {
    @ModifyVariable(method = "prepare", at = @At("HEAD"), argsOnly = true, ordinal = 0, remap = false)
    private GameRenderer schizoid$rearGameRenderer(GameRenderer original) {
        return RearViewPipeline.isDrawing() ? RearViewPipeline.secondaryGame() : original;
    }
}
