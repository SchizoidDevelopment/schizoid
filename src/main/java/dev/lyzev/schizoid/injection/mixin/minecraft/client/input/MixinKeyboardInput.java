/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.injection.mixin.minecraft.client.input;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.lyzev.api.events.EventIsMovementKeyPressed;
import dev.lyzev.api.events.EventKeyboardInputTick;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.client.KeyMapping;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * This class provides a mixin for the KeyboardInput class in the Minecraft client input package.
 * It modifies the behavior of the tick method of the KeyboardInput class.
 */
@Mixin(KeyboardInput.class)
public class MixinKeyboardInput {

    /**
     * This method is a redirect for the isPressed method of the KeyBinding class.
     * It creates and fires an EventKeyboardInputPressed event when a key is pressed.
     * The return value of the isPressed method is then replaced by the return value of the event.
     *
     * @param instance The key binding that was pressed.
     * @return The return value of the event.
     */
    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/KeyMapping;isDown()Z"))
    private boolean onTick(KeyMapping instance, Operation<Boolean> original) {
        EventIsMovementKeyPressed event = new EventIsMovementKeyPressed(instance, original.call(instance));
        event.fire();
        return event.isPressed();
    }

    @Inject(method = "tick", at = @At(value = "FIELD", target = "Lnet/minecraft/client/player/KeyboardInput;keyPresses:Lnet/minecraft/world/entity/player/Input;", opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER))
    private void onTick(CallbackInfo ci) {
        EventKeyboardInputTick event = new EventKeyboardInputTick((KeyboardInput) (Object) this);
        event.fire();
    }
}
