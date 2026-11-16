/*
 * Copyright (c) 2023-2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.injection.mixin.imgui;

import dev.lyzev.api.imgui.render.ImGuiRenderer;
import dev.lyzev.api.imgui.spatial.SpatialInputRouter;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.glfw.ImGuiImplGlfw;
import org.lwjgl.glfw.GLFWMouseButtonCallback;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * This class provides a mixin for the ImGuiImplGlfw class in the imgui glfw package.
 * It modifies the behavior of the scrollCallback method of the ImGuiImplGlfw class.
 */
@Mixin(value = ImGuiImplGlfw.class, remap = false)
public class MixinImGuiImplGlfw {
    @Inject(method = "scrollCallback", at = @At("HEAD"), cancellable = true, remap = false)
    private void worldScroll(long window, double x, double y, CallbackInfo ci) {
        if (SpatialInputRouter.INSTANCE.onScroll(window, x, y)) ci.cancel();
    }

    @Inject(method = "keyCallback", at = @At("HEAD"), cancellable = true, remap = false)
    private void worldKey(long window, int key, int scancode, int action, int mods, CallbackInfo ci) {
        if (SpatialInputRouter.INSTANCE.onKey(window, key, action, mods)) ci.cancel();
    }

    @Inject(method = "charCallback", at = @At("HEAD"), cancellable = true, remap = false)
    private void worldCharacter(long window, int codepoint, CallbackInfo ci) {
        if (SpatialInputRouter.INSTANCE.onCharacter(window, codepoint)) ci.cancel();
    }

    @Inject(method = "windowFocusCallback", at = @At("HEAD"), remap = false)
    private void worldFocus(long window, boolean focused, CallbackInfo ci) {
        if (!focused && window == windowPtr && SpatialInputRouter.INSTANCE.getInteracting())
            SpatialInputRouter.INSTANCE.suspendForFocusLoss();
    }

    @Shadow
    @Final
    private long[] keyOwnerWindows;

    @Shadow
    @Final
    private boolean[] mouseJustPressed;

    @Shadow
    private long windowPtr;

    @Shadow
    private GLFWMouseButtonCallback prevUserCallbackMouseButton;

    /**
     * This method is a mixin for the scrollCallback method of the ImGuiImplGlfw class.
     * It invokes the previous user callback for the scroll event if it exists and the window ID matches the window pointer.
     * It then updates the target scroll position in the ImGuiRenderer instance.
     * The method is cancelled after the target scroll position is updated.
     *
     * @param windowId The ID of the window.
     * @param xOffset  The offset in the x direction.
     * @param yOffset  The offset in the y direction.
     * @param ci       The callback information.
     */
    @Inject(method = "scrollCallback", at = @At(value = "INVOKE", target = "Limgui/ImGui;getIO()Limgui/ImGuiIO;"), cancellable = true, remap = false)
    private void onScrollCallback(long windowId, double xOffset, double yOffset, CallbackInfo ci) {
        // The previous vanilla callback already ran. Chat owns non-panel history scrolling.
        if (SpatialInputRouter.INSTANCE.getCursorMode()) { ci.cancel(); return; }
        // Update target scroll position
        ImGuiRenderer.INSTANCE.setTargetScrollX((float) (ImGuiRenderer.INSTANCE.getTargetScrollX() + xOffset));
        ImGuiRenderer.INSTANCE.setTargetScrollY((float) (ImGuiRenderer.INSTANCE.getTargetScrollY() + yOffset));
        ci.cancel();
    }

    /**
     * This method is a mixin for the keyCallback method of the ImGuiImplGlfw class.
     * It cancels the method if the ImGui IO does not want to capture the keyboard.
     *
     * @param windowId The ID of the window.
     * @param key      The key.
     * @param scancode The scancode.
     * @param action   The action.
     * @param mods     The mods.
     * @param ci       The callback information.
     */
    @Inject(method = "keyCallback", at = @At(value = "INVOKE", target = "Limgui/ImGui;getIO()Limgui/ImGuiIO;"), cancellable = true, remap = false)
    private void onKeyCallback(long windowId, int key, int scancode, int action, int mods, CallbackInfo ci) {
        if (SpatialInputRouter.INSTANCE.getInteracting() || SpatialInputRouter.INSTANCE.getCursorMode() || !ImGui.getIO().getWantCaptureKeyboard()) {
            final ImGuiIO io = ImGui.getIO();

            for (int ownedKey = 0; ownedKey < keyOwnerWindows.length; ownedKey++) {
                io.setKeysDown(ownedKey, false);
                keyOwnerWindows[ownedKey] = 0;
            }

            io.setKeyCtrl(false);
            io.setKeyShift(false);
            io.setKeyAlt(false);
            io.setKeySuper(false);
            ci.cancel();
        }
    }

    /**
     * This method is a mixin for the charCallback method of the ImGuiImplGlfw class.
     * It cancels the method if the ImGui IO does not want to capture the mouse buttons.
     *
     * @param windowId The ID of the window.
     * @param button   The button.
     * @param action   The action.
     * @param mods     The mods.
     * @param ci       The callback information.
     */
    @Inject(method = "mouseButtonCallback", at = @At("HEAD"), cancellable = true, remap = false)
    private void onMouseButtonCallback(long windowId, int button, int action, int mods, CallbackInfo ci) {
        if (SpatialInputRouter.INSTANCE.onButton(windowId, button, action, mods)) {
            ci.cancel();
            return;
        }
        if (SpatialInputRouter.INSTANCE.getInteracting() || SpatialInputRouter.INSTANCE.getCursorMode() || !ImGui.getIO().getWantCaptureMouse()) {
            // Keep Minecraft's callback chain intact even when the backend has no previous callback.
            if (windowId == windowPtr && prevUserCallbackMouseButton != null)
                prevUserCallbackMouseButton.invoke(windowId, button, action, mods);
            if (button >= 0 && button < mouseJustPressed.length)
                mouseJustPressed[button] = false;
            ci.cancel();
        }
    }

    @Inject(method = "charCallback", at = @At(value = "INVOKE", target = "Limgui/ImGui;getIO()Limgui/ImGuiIO;"), cancellable = true, remap = false)
    private void onCharCallback(long windowId, int codepoint, CallbackInfo ci) {
        if (SpatialInputRouter.INSTANCE.getInteracting() || SpatialInputRouter.INSTANCE.getCursorMode() || !ImGui.getIO().getWantCaptureKeyboard()) ci.cancel();
    }
}
