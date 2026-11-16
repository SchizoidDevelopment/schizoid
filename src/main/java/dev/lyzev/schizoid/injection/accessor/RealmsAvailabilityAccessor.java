/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.injection.accessor;

import com.mojang.realmsclient.RealmsAvailability;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.concurrent.CompletableFuture;

/**
 * Provides the means to access protected members of the Realms availability check.
 */
@Mixin(RealmsAvailability.class)
public interface RealmsAvailabilityAccessor {
    /**
     * Sets the Realms availability info checker.
     *
     * @param availabilityInfo Realms availability info completable future
     */
    @Accessor("future")
    static void setCurrentFuture(CompletableFuture<RealmsAvailability.Result> availabilityInfo) {
        throw new UnsupportedOperationException("Mixin accessor was not applied");
    }
}
