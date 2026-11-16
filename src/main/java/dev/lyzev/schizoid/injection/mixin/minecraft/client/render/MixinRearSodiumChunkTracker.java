package dev.lyzev.schizoid.injection.mixin.minecraft.client.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.lyzev.api.opengl.RearViewPipeline;
import it.unimi.dsi.fastutil.longs.LongSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Keep Sodium's pending chunk events for the primary renderer after rear extraction. */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTracker", remap = false)
public abstract class MixinRearSodiumChunkTracker {
    @WrapOperation(method = "forEachEvent", at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/longs/LongSet;clear()V", remap = false), require = 0, remap = false)
    private void schizoid$preserveForPrimary(LongSet set, Operation<Void> original) {
        if (!RearViewPipeline.isExtracting()) original.call(set);
    }
}
