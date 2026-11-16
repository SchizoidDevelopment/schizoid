/*
 * Copyright (c) 2026. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.injection.mixin.minecraft.client.network;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.lyzev.schizoid.feature.features.module.modules.exploit.PacketEventBridge;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Preserves vanilla's real packet, listener validation and error handling around the replacement-aware event. */
@Mixin(targets = "net.minecraft.network.PacketProcessor$ListenerAndPacket")
public class MixinQueuedPacket {

    @Shadow @Final private Packet<?> packet;
    @Shadow @Final private PacketListener listener;

    @WrapMethod(method = "handle")
    private void schizoid$withPacketContext(Operation<Void> original) {
        PacketEventBridge.processQueued(packet, listener, () -> original.call());
    }

    @WrapOperation(method = "handle", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/Packet;handle(Lnet/minecraft/network/PacketListener;)V"))
    private void schizoid$dispatchPacket(Packet<?> packet, PacketListener listener, Operation<Void> original) {
        Packet<?> incoming = PacketEventBridge.queuedIncoming(packet, listener);
        if (incoming != null) original.call(incoming, listener);
    }
}
