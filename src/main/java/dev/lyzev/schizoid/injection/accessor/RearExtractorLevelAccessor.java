package dev.lyzev.schizoid.injection.accessor;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LevelExtractor.class)
public interface RearExtractorLevelAccessor {
    @Accessor("level") void schizoid$setRearLevel(ClientLevel level);
    @Accessor("shouldResetLevelRenderData") void schizoid$setResetLevelRenderData(boolean value);
}
