/*
 * Copyright (c) 2026. Schizoid
 * All rights reserved.
 */
package dev.lyzev.schizoid.injection.mixin.minecraft.client.gui;

import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;

/** Keep vanilla crosshair and attack indicator: spatial gaze uses their existing screen centre. */
@Mixin(Hud.class)
public class MixinHud {
}
