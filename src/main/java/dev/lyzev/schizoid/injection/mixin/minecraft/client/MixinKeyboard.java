/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.injection.mixin.minecraft.client;

import dev.lyzev.api.events.EventKeystroke;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.CharacterEvent;
import dev.lyzev.api.imgui.spatial.SpatialInputRouter;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * This class provides a mixin for the Keyboard class in the Minecraft client package.
 * It modifies the behavior of the onKey method of the Keyboard class.
 */
@Mixin(KeyboardHandler.class)
public class MixinKeyboard {
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void worldKey(long window, int action, KeyEvent key, CallbackInfo ci) {
        if (SpatialInputRouter.INSTANCE.onKey(window, key.key(), action, key.modifiers())) ci.cancel();
    }

    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void worldCharacter(long window, CharacterEvent character, CallbackInfo ci) {
        if (SpatialInputRouter.INSTANCE.onCharacter(window, character.codepoint())) ci.cancel();
    }

    /**
     * This method is a mixin for the onKey method of the Keyboard class.
     * It creates and fires an EventKeystroke event when a key is pressed.
     * The event is fired after the original method has been executed.
     *
     * @param window    The window where the key was pressed.
     * @param key       The key that was pressed.
     * @param scancode  The scancode of the key.
     * @param action    The action that was performed.
     * @param modifiers The modifier keys that were held down when the key was pressed.
     * @param ci        The callback information.
     */
    @Inject(method = "keyPress", at = @At("RETURN"))
    private void onKey(long window, int action, KeyEvent key, CallbackInfo ci) {
        if (key.key() != GLFW.GLFW_KEY_UNKNOWN && window == Minecraft.getInstance().getWindow().handle())
            new EventKeystroke(window, key.key(), key.scancode(), action, key.modifiers()).fire();
    }
}
