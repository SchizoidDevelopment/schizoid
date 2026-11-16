/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.injection.mixin.minecraft.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.lyzev.api.events.EventIsCursorLocked;
import dev.lyzev.api.events.EventMouseClick;
import dev.lyzev.api.events.EventMouseScroll;
import dev.lyzev.api.events.EventUpdateMouse;
import dev.lyzev.api.imgui.spatial.SpatialInputRouter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * This class provides a mixin for the Mouse class in the Minecraft client package.
 * It modifies the behavior of the onMouseButton method of the Mouse class.
 */
@Mixin(MouseHandler.class)
public class MixinMouse {
    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void worldButton(long window, MouseButtonInfo button, int action, CallbackInfo ci) {
        if (SpatialInputRouter.INSTANCE.onButton(window, button.button(), action, button.modifiers())) ci.cancel();
    }

    @Inject(method = "onMove", at = @At("HEAD"))
    private void worldMove(long window, double x, double y, CallbackInfo ci) {
        SpatialInputRouter.INSTANCE.onMove(window, x, y);
    }

    /**
     * This method is a mixin for the onMouseButton method of the Mouse class.
     * It creates and fires an EventMouseClick event when a mouse button is clicked.
     * The event is fired after the original method has been executed.
     *
     * @param window The window where the mouse button was clicked.
     * @param button The button that was clicked.
     * @param action The action that was performed.
     * @param ci     The callback information.
     */
    @Inject(method = "onButton", at = @At("RETURN"))
    private void onMouseButton(long window, MouseButtonInfo button, int action, CallbackInfo ci) {
        if (button.button() != GLFW.GLFW_KEY_UNKNOWN && window == Minecraft.getInstance().getWindow().handle())
            new EventMouseClick(window, button.button(), action, button.modifiers()).fire();
    }

    /**
     * This method is a redirect for the isCursorLocked method of the Mouse class.
     * It creates and fires an EventIsCursorLocked event when it is checked if the cursor is locked.
     * The return value of the isCursorLocked method is then replaced by the return value of the event.
     *
     * @param instance The Mouse instance.
     * @return The return value of the event.
     */
    @WrapOperation(method = "handleAccumulatedMovement", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/MouseHandler;isMouseGrabbed()Z"))
    private boolean onTick(MouseHandler instance, Operation<Boolean> original) {
        EventIsCursorLocked event = new EventIsCursorLocked(original.call(instance));
        event.fire();
        return event.isCursorLocked();
    }

    /**
     * This method is a mixin for the updateMouse method of the Mouse class.
     * It creates and fires an EventUpdateMouse event when the mouse is updated.
     * The event is fired at the start of the method.
     *
     * @param ci The callback information.
     */
    @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
    private void onUpdateMouse(double elapsedTime, CallbackInfo ci) {
        if (SpatialInputRouter.INSTANCE.capturesLook()) {
            ci.cancel();
            return;
        }
        // Camera turning stays enabled on purpose: with gaze aiming the crosshair *is* the pointer,
        // so the player looks around to move it. The router arbitrates clicks, not the look.
        EventUpdateMouse.INSTANCE.fire();
    }

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void onScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
        if (SpatialInputRouter.INSTANCE.onScroll(window, horizontal, vertical)) {
            ci.cancel();
            return;
        }
        if (window == Minecraft.getInstance().getWindow().handle()) {
            EventMouseScroll event = new EventMouseScroll(horizontal, vertical);
            event.fire();
            if (event.isCancelled()) {
                ci.cancel();
            }
        }
    }
}
