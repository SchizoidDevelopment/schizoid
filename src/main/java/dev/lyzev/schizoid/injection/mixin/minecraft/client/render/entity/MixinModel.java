/*
 * Copyright (c) 2026. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.injection.mixin.minecraft.client.render.entity;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.lyzev.api.events.EventRenderModel;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Living models are drawn later by submit nodes in 26.2. */
@Mixin(Model.class)
public class MixinModel {
    @WrapOperation(method = "renderToBuffer", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/geom/ModelPart;render(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V"))
    private void onRenderModel(ModelPart root, PoseStack pose, VertexConsumer vertices,
                               int light, int overlay, int tint, Operation<Void> original) {
        if ((Object) this instanceof EntityModel<?> model) {
            EventRenderModel event = new EventRenderModel(model, pose, vertices, light, overlay, tint);
            event.fire();
            original.call(root, event.getMatrixStack(), event.getVertexConsumer(), event.getLight(), event.getOverlay(), event.getArgb());
        } else {
            original.call(root, pose, vertices, light, overlay, tint);
        }
    }
}
