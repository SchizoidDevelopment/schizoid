/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.injection.accessor;

import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Optional;

@Mixin(ClientboundExplodePacket.class)
public interface ExplosionS2CPacketAccessor {

    @Accessor("playerKnockback")
    Optional<Vec3> getPlayerKnockback();
}
