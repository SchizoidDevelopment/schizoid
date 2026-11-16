/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.injection.accessor;

import net.minecraft.client.resources.SkinManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SkinManager.class)
public interface PlayerSkinProviderAccessor {

    /**
     * Returns the skin cache.
     *
     * @return the skin cache
     */
    @Accessor("skinTextures")
    SkinManager.TextureCache getSkinCache();
}
