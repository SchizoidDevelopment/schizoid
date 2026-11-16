package dev.lyzev.schizoid.injection.accessor;

import net.fabricmc.fabric.impl.client.rendering.level.LevelExtractionContextImpl;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Fabric 25.3.3 context injected into every LevelExtractor instance. */
@Mixin(value = LevelExtractor.class, priority = 900)
public interface RearFabricExtractorAccessor {
    @Accessor(value = "extractionContext", remap = false)
    LevelExtractionContextImpl schizoid$getFabricExtractionContext();
}
