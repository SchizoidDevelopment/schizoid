/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.injection.mixin.minecraft.client.network;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import dev.lyzev.api.events.EventClientPlayerEntityTick;
import dev.lyzev.api.events.EventDistanceToBlockHitResult;
import dev.lyzev.api.events.EventDistanceToEntityHitResult;
import dev.lyzev.api.events.EventUpdateCrosshairTarget;
import dev.lyzev.schizoid.feature.features.module.modules.combat.ModuleToggleableReach;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.item.component.AttackRange;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Predicate;

/**
 * This class provides a mixin for the ClientPlayerEntity class in the Minecraft client network package.
 * It modifies the behavior of the tick method of the ClientPlayerEntity class.
 */
@Mixin(LocalPlayer.class)
public class MixinClientPlayerEntity {

    @Shadow
    private static HitResult pick(Entity camera, double blockReach, double entityReach, float partialTick) {
        throw new AssertionError("Mixin shadow");
    }

    @Unique
    private boolean schizoid$rotationApplied;

    /**
     * This method is a mixin for the tick method of the ClientPlayerEntity class.
     * It creates and fires an EventClientPlayerEntityTick event before the tick method of the AbstractClientPlayerEntity class is invoked.
     * The event is fired at the start of the method.
     *
     * @param ci The callback information.
     */
    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/AbstractClientPlayer;tick()V", shift = At.Shift.BEFORE, ordinal = 0))
    private void onTickPre(CallbackInfo ci) {
        LocalPlayer player = (LocalPlayer) (Object) this;
        schizoid$rotationApplied = true;
        new EventClientPlayerEntityTick(player).fire();
    }

    @WrapMethod(method = "tick")
    private void schizoid$preserveClientRotation(Operation<Void> original) {
        LocalPlayer player = (LocalPlayer) (Object) this;
        float yaw = player.getYRot();
        float pitch = player.getXRot();
        schizoid$rotationApplied = false;
        try {
            original.call();
        } finally {
            // The loading-screen early return never fires the event, so there is nothing to restore.
            if (schizoid$rotationApplied) {
                player.setYRot(yaw);
                player.setXRot(pitch);
            }
            schizoid$rotationApplied = false;
        }
    }

    /** Vanilla picking moved here in 26.2, including item-specific attack ranges. */
    @WrapMethod(method = "raycastHitResult")
    private HitResult schizoid$pickWithServerRotation(float partialTick, Entity camera, Operation<HitResult> original) {
        float yaw = camera.getYRot();
        float pitch = camera.getXRot();
        float oldYaw = camera.yRotO;
        float oldPitch = camera.xRotO;
        try {
            new EventUpdateCrosshairTarget(camera).fire();
            return original.call(partialTick, camera);
        } finally {
            camera.setYRot(yaw);
            camera.setXRot(pitch);
            camera.yRotO = oldYaw;
            camera.xRotO = oldPitch;
        }
    }

    @WrapOperation(method = "raycastHitResult", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/component/AttackRange;getClosesetHit(Lnet/minecraft/world/entity/Entity;FLjava/util/function/Predicate;)Lnet/minecraft/world/phys/HitResult;"))
    private HitResult schizoid$weaponThroughWalls(AttackRange range, Entity camera, float partialTick,
                                                Predicate<Entity> selector, Operation<HitResult> original) {
        HitResult vanilla = original.call(range, camera, partialTick, selector);
        ModuleToggleableReach reach = ModuleToggleableReach.INSTANCE;
        if (!reach.isEnabled() || !reach.getEntity() || !reach.getEntityThroughWalls()) return vanilla;
        LocalPlayer player = (LocalPlayer) (Object) this;
        // Reuse vanilla's existing fallback picker and distance-event contracts, rather than replacing the weapon raycast.
        HitResult extended = pick(camera, player.blockInteractionRange(), range.effectiveMaxRange(player), partialTick);
        if (extended instanceof EntityHitResult entityHit && selector.test(entityHit.getEntity())
                && range.isInRange(player, entityHit.getEntity().getBoundingBox(), 0.0)) {
            Vec3 eye = camera.getEyePosition(partialTick);
            if (!(vanilla instanceof EntityHitResult) || extended.getLocation().distanceToSqr(eye) <= vanilla.getLocation().distanceToSqr(eye)) {
                return extended;
            }
        }
        return vanilla;
    }

    @WrapOperation(method = "pick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/Vec3;distanceToSqr(Lnet/minecraft/world/phys/Vec3;)D", ordinal = 0))
    private static double schizoid$blockDistance(Vec3 instance, Vec3 eye, Operation<Double> original) {
        EventDistanceToBlockHitResult event = new EventDistanceToBlockHitResult(original.call(instance, eye));
        event.fire();
        return event.getDistance();
    }

    @WrapOperation(method = "pick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/Vec3;distanceToSqr(Lnet/minecraft/world/phys/Vec3;)D", ordinal = 1))
    private static double schizoid$entityDistance(Vec3 instance, Vec3 eye, Operation<Double> original) {
        EventDistanceToEntityHitResult event = new EventDistanceToEntityHitResult(original.call(instance, eye));
        event.fire();
        return event.getDistance();
    }
}
