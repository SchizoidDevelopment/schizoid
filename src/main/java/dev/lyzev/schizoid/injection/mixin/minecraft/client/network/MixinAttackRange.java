/*
 * Copyright (c) 2026. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.injection.mixin.minecraft.client.network;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.lyzev.api.events.EventGetAttributeValue;
import dev.lyzev.schizoid.Schizoid;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.component.AttackRange;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(AttackRange.class)
public class MixinAttackRange {

    @ModifyReturnValue(method = "effectiveMaxRange", at = @At("RETURN"))
    private float schizoid$weaponReach(float original, Entity entity) {
        if (entity != Schizoid.INSTANCE.getMc().player) return original;
        EventGetAttributeValue event = new EventGetAttributeValue(Attributes.ENTITY_INTERACTION_RANGE.value(), original);
        event.fire();
        return (float) event.getValue();
    }
}
