/*
 * Copyright (c) 2026. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.injection.mixin.minecraft.client.network;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.lyzev.schizoid.feature.features.module.modules.player.FeatureRotation;
import dev.lyzev.schizoid.feature.features.module.modules.exploit.PacketEventBridge;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public class MixinClientPacketListener {

    @Inject(method = {"handleMovePlayer", "handleLookAt"}, at = @At("RETURN"))
    private void schizoid$acceptServerRotation(CallbackInfo ci) {
        FeatureRotation.INSTANCE.acceptServerRotation();
    }

    @WrapOperation(method = "handleBundlePacket", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/Packet;handle(Lnet/minecraft/network/PacketListener;)V"))
    private void schizoid$handleBundleChild(Packet<?> packet, PacketListener listener, Operation<Void> original) {
        Packet<?> incoming = PacketEventBridge.receive(packet, listener, ((ClientPacketListener) (Object) this).getConnection());
        if (incoming != null) original.call(incoming, listener);
    }
}
