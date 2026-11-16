package dev.lyzev.schizoid.injection.accessor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Vanilla reaches the active renderer through these three Minecraft fields from dozens of
 * classes (chunk layer output targets, render types, clouds, particles, ...). The rear pass
 * installs its own instances for its duration instead of redirecting every call site.
 */
@Mixin(Minecraft.class)
public interface RearMinecraftAccessor {
    @Accessor("gameRenderer")
    @Mutable
    void schizoid$setGameRenderer(GameRenderer value);

    @Accessor("levelRenderer")
    @Mutable
    void schizoid$setLevelRenderer(LevelRenderer value);

    @Accessor("levelExtractor")
    @Mutable
    void schizoid$setLevelExtractor(LevelExtractor value);
}
