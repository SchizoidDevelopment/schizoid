/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.injection.accessor;

import com.mojang.realmsclient.client.RealmsClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RealmsClient.class)
public interface RealmsClientAccessor {

    @Accessor("realmsClientInstance")
    static void setInstance(RealmsClient client) {
        throw new UnsupportedOperationException("Mixin accessor was not applied");
    }
}
