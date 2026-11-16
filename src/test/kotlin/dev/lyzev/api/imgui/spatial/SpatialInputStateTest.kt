package dev.lyzev.api.imgui.spatial

/** Deterministic pointer regressions; no Minecraft, GLFW natives, GL context or test framework. */
object SpatialInputStateTest {
    @JvmStatic
    fun main(args: Array<String>) {
        // Absolute input, not integrated deltas: first move, reopen and DPI changes have no baseline.
        for (extent in listOf(800.0, 1000.0, 1600.0)) {
            for (absolute in listOf(-9000.0, 0.0, 170.0, 700.0, 9000.0, Double.NaN)) {
                check(SpatialPointerState.coordinate(false, absolute, extent) == extent / 2.0)
                val expected = if (absolute.isFinite()) absolute.coerceIn(0.0, extent) else extent / 2.0
                check(SpatialPointerState.coordinate(true, absolute, extent) == expected)
                check(SpatialPointerState.coordinate(false, absolute, extent) == extent / 2.0)
            }
        }
        check(SpatialPointerState.coordinate(true, 100.0, 0.0) == 0.0)
        check(SpatialPointerState.coordinate(true, 100.0, Double.NaN) == 0.0)
        // GLFW position is already in screen units, independent of framebuffer size and GUI scale.
        check(SpatialPointerState.coordinate(true, 700.0, 1000.0) == 700.0)
        for (guiHeight in listOf(200.0, 400.0, 800.0)) {
            val boundary = (guiHeight - 16.0) * 800.0 / guiHeight
            check(!SpatialPointerState.inChatInput(boundary - 1.0, 800.0, guiHeight))
            check(SpatialPointerState.inChatInput(boundary, 800.0, guiHeight))
            check(SpatialPointerState.inChatInput(799.0, 800.0, guiHeight))
        }
        check(!SpatialPointerState.inChatInput(Double.NaN, 800.0, 400.0))
        check(!SpatialPointerState.inChatInput(799.0, 0.0, 400.0))
        check(SpatialPointerState.planeDelta(2f, 4f, 2f, 4f) == (0f to 0f)) // no pickup jump
        check(SpatialPointerState.planeDelta(3f, 2f, 2f, 4f) == (1f to -2f)) // preserve grab offset
        check(SpatialPointerState.planeDelta(2f, 4f, 2f, 4f) == (0f to 0f)) // no accumulated drift
        check(SpatialPointerState.planeDelta(Float.NaN, 4f, 2f, 4f) == null)
        sourceChecks()
        mappingChecks()
        focusChecks()
        keyboardChecks()
        resizeChecks()
        println("SpatialInputStateTest: all checks passed")
    }

    private fun keyboardChecks() {
        val keys = SpatialKeyOwnership()
        keys.press(87, null) // held game movement keeps vanilla release even if text activates
        check(keys.get(87)?.owner == null)
        check(keys.release(87)?.owner == null)
        for (key in listOf(32, 49, 69, 84, 87, 256, 290, 340, 344)) {
            keys.press(key, "") // explicit bind is not restricted to a non-game whitelist
            check(keys.hasBindPress())
            check(keys.get(key)?.owner != null) // repeat follows bind press
            check(keys.release(key)?.owner != null)
            check(!keys.hasBindPress())
        }
        keys.press(65, "workspace", true)
        check(keys.get(65) == SpatialKeyOwnership.Press("workspace", true))
        keys.cancelText() // screen change cancels delivery, not release ownership
        check(keys.get(65)?.owner == "")
        check(!keys.hasBindPress())
        check(keys.release(65)?.owner != null)
        check(keys.release(65) == null)
    }

    private fun resizeChecks() {
        check(SpatialResizeGeometry.distance(2f, .00001f) == null)
        check(SpatialResizeGeometry.distance(2f, 0f) == null)
        check(SpatialResizeGeometry.distance(Float.NaN, 1f) == null)
        check(SpatialResizeGeometry.distance(1f, Float.POSITIVE_INFINITY) == null)
        check(SpatialResizeGeometry.distance(-2f, 1f) == null)
        check(SpatialResizeGeometry.distance(257f, 1f) == null)
        check(SpatialResizeGeometry.distance(2f, 1f) == 2f)
        // Outside wall/accepted canvas: no dependency on the last accepted size, no oscillation.
        for (point in listOf(630f, 900f, 2500f, 900f, 500f, -1000f, 630f)) {
            val size = SpatialResizeGeometry.size(640f, point, 630f)!!
            check(size == (640f + point - 630f).coerceIn(128f, 2048f))
            repeat(20) { check(SpatialResizeGeometry.size(640f, point, 630f) == size) }
        }
        check(SpatialResizeGeometry.size(4096f, 100f, 100f) == 4096f) // giant pickup: no jump
        check(SpatialResizeGeometry.size(4096f, -2000f, 100f) == 1996f) // giant can shrink
        check(SpatialResizeGeometry.size(640f, Float.NaN, 630f) == null)
        for (width in listOf(128f, 640f, 1000f)) {
            val shift = (width - 640f) / 640f
            check(kotlin.math.abs(shift - width / 640f + 1f) < .000001f) // fixed opposite corner
        }
    }

    private fun focusChecks() {
        for (interacting in listOf(false, true)) for (visible in listOf(false, true))
            for (hasLevel in listOf(false, true)) {
                val preserved = SpatialFocusPolicy.keepInteraction(interacting, visible, hasLevel)
                check(preserved == (interacting && visible && hasLevel))
                // Duplicate focus-loss callbacks are idempotent; a normal close cannot be resumed.
                check(SpatialFocusPolicy.keepInteraction(preserved, visible, hasLevel) == preserved)
                check(!SpatialFocusPolicy.keepInteraction(false, visible, hasLevel))
            }

    }

    private fun sourceChecks() {
        val root = java.nio.file.Path.of("src/main")
        val router = java.nio.file.Files.readString(root.resolve("kotlin/dev/lyzev/api/imgui/spatial/SpatialInputRouter.kt"))
        check("val cursorMode: Boolean get() = Schizoid.mc.gui.screen() is ChatScreen" in router)
        check("fun capturesLook(): Boolean = false" in router)
        check("toggleCursorMode" !in router && "pointer.enable" !in router && "GLFW_KEY_LEFT_ALT" !in router)
        val transition = router.substringAfter("private fun syncScreen()").substringBefore("/** Panel under")
        check("carry = null" in transition && "captured = null" in transition)
        check("buttonOwners.fill" !in transition && "surface.centerX =" !in transition)
        check("keyChat.key.value" in router && "keyCommand.key.value" in router)
        check("mouseHandler.xpos()" in router && "mouseHandler.ypos()" in router)
        check("internal fun cursorForAutotest(x: Double, y: Double) = Unit" in router)
        check("if (!screenOpen() && !Schizoid.mc.mouseHandler.isMouseGrabbed())" in router)
        val mouse = java.nio.file.Files.readString(root.resolve("java/dev/lyzev/schizoid/injection/mixin/minecraft/client/MixinMouse.java"))
        check("""@Inject(method = "onMove", at = @At("HEAD"))""" in mouse)
        val glfw = java.nio.file.Files.readString(root.resolve("java/dev/lyzev/schizoid/injection/mixin/imgui/MixinImGuiImplGlfw.java"))
        check("getInteracting() || SpatialInputRouter.INSTANCE.getCursorMode() || !ImGui.getIO().getWantCaptureKeyboard()" in glfw)
        val keyPath = router.substringAfter("fun onKey(").substringBefore("fun onCharacter(")
        check(keyPath.indexOf("consumeKeybindInput(key)") < keyPath.indexOf("if (screenOpen())"))
        check("val isGameKey" !in keyPath)
        val hook = java.nio.file.Files.readString(root.resolve("kotlin/dev/lyzev/api/imgui/spatial/SpatialWorldHook.kt"))
        check("surface.wantsKeyboard = io.wantTextInput" in hook)
        val resizePath = router.substringAfter("private fun updateResize(").substringBefore("private fun revalidateAttachments(")
        check("cursorPlanePoint(initial)" in resizePath && "pickOn(" !in resizePath)
        check("SpatialResizeGeometry.size(initial.logicalW" in resizePath)
        check("getCursorMode() || !ImGui.getIO().getWantCaptureMouse()" in glfw)
    }

    private fun mappingChecks() {
        fun near(actual: Float, expected: Float) = check(kotlin.math.abs(actual - expected) < .00001f) {
            "Expected $expected, got $actual"
        }
        val flat = SpatialCanvasMapping.visible(.9f, .25f, 0f, 0f)!!
        near(flat.x, .9f)
        near(flat.y, .25f)
        val right = SpatialCanvasMapping.visible(.9f, .5f, .2f, .04f)!!
        near(right.x, .9512f) // forward shader map, not inverse; 32.768 px difference on a 640 px canvas
        near(right.y, .5f)
        val top = SpatialCanvasMapping.visible(.5f, .1f, .2f, .04f)!!
        near(top.y, .0488f) // top remains top (ImGui Y down), never .9512
        val bottom = SpatialCanvasMapping.visible(.5f, .9f, .2f, .04f)!!
        near(bottom.y, .9512f)
        near(top.y + bottom.y, 1f)
        val diagonal = SpatialCanvasMapping.curved(.75f, .75f, .2f)
        near(diagonal.x, .775f)
        near(diagonal.y, .775f)
        check(SpatialCanvasMapping.visible(.99f, .5f, .2f, .04f) == null) // warped outside canvas
        check(SpatialCanvasMapping.visible(.001f, .001f, 0f, .04f) == null) // transparent rounded corner
        check(SpatialCanvasMapping.visible(.02f, .02f, 0f, .04f) != null) // feather has positive coverage
        check(SpatialCanvasMapping.visible(.5f, .001f, 0f, .04f) != null) // near-edge body, not corner
        check(SpatialCanvasMapping.visible(0f, .5f, 0f, 0f) == null) // mask exactly zero on boundary
        check(SpatialCanvasMapping.visible(-.01f, .5f, 0f, 0f) == null)
        check(SpatialCanvasMapping.visible(Float.NaN, .5f, 0f, 0f) == null)
        // Capture can go outside the silhouette; resize callers keep physical UV instead of this map.
        check(SpatialCanvasMapping.curved(1.1f, .5f, .2f).x > 1.1f)
    }
}