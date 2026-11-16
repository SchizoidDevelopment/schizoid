/*
 * Copyright (c) 2023-2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.injection.mixin.minecraft.network;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.lyzev.schizoid.feature.features.module.modules.exploit.PacketEventBridge;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * This class provides mixins for the Connection class in the Minecraft network package.
 * It injects custom behavior into the doSendPacket and genericsFtw methods of the Connection class.
 */
@Mixin(Connection.class)
public class MixinClientConnection {

    /**
     * This method is a mixin for the genericsFtw method of the Connection class.
     * It creates and fires an EventReceivePacket event before the packet is handled.
     * If the event is cancelled, it cancels the handling of the packet.
     *
     * @param packet   The packet to be handled.
     * @param listener The listener for the packet.
     */
    @WrapOperation(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/network/Connection;genericsFtw(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;)V"))
    private void onHandlePacket(Packet<?> packet, PacketListener listener, Operation<Void> original) {
        PacketEventBridge.withIncomingContext((Connection) (Object) this, listener, () -> original.call(packet, listener));
    }

    @WrapOperation(method = "genericsFtw", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/Packet;handle(Lnet/minecraft/network/PacketListener;)V"))
    private static void schizoid$dispatchIncoming(Packet<?> packet, PacketListener listener, Operation<Void> original) {
        Packet<?> incoming = PacketEventBridge.prepareIncoming(packet, listener);
        if (incoming != null) original.call(incoming, listener);
    }

    /**
     * This method is a mixin for the doSendPacket method of the Connection class.
     * It creates and fires an EventSendPacket event before the packet is sent.
     * If the event is cancelled, it cancels the sending of the packet.
     *
     * @param packet    The packet to be sent.
     * @param callbacks The callbacks to be used when the packet is sent.
     * @param flush     Whether to flush the packet immediately.
     */
    @WrapMethod(method = "sendPacket(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V")
    private void onSendImmediately(Packet<?> packet, @Nullable ChannelFutureListener callbacks, boolean flush, Operation<Void> original) {
        Connection connection = (Connection) (Object) this;
        Packet<?> outgoing = connection.getReceiving() == PacketFlow.CLIENTBOUND
                ? PacketEventBridge.send(packet, connection, callbacks, flush) : packet;
        // Filter before Netty scheduling, so replay suppression cannot race a later doSendPacket invocation.
        if (outgoing != null) original.call(outgoing, callbacks, flush);
    }
}
