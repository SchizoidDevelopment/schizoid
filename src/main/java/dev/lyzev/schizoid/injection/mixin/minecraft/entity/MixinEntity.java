/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.injection.mixin.minecraft.entity;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.lyzev.api.events.EventIsInvisibleTo;
import dev.lyzev.api.events.EventLerpPosAndRotation;
import dev.lyzev.api.events.EventUpdateVelocity;
import dev.lyzev.api.events.EventVelocity;
import dev.lyzev.schizoid.Schizoid;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * This class provides a mixin for the Entity class in the Minecraft entity package.
 * It modifies the behavior of the isInvisibleTo method of the Entity class.
 */
@Mixin(Entity.class)
public abstract class MixinEntity {

    /**
     * This method is a redirect for the isInvisibleTo method of the Entity class.
     * It creates and fires an EventIsInvisibleTo event before the invisibility status is checked.
     * The invisibility status is then determined by the result of the event.
     *
     * @param player The player that the Entity is being checked for invisibility against.
     * @param cir    The callback information.
     * @return The invisibility status of the Entity, as determined by the EventIsInvisibleTo event.
     */
    @Inject(method = "isInvisibleTo", at = @At("RETURN"), cancellable = true)
    private void onIsInvisibleTo(Player player, CallbackInfoReturnable<Boolean> cir) {
        EventIsInvisibleTo event = new EventIsInvisibleTo(cir.getReturnValueZ());
        event.fire();
        cir.setReturnValue(event.isInvisible());
    }

    @WrapOperation(method = "moveRelative", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getYRot()F"))
    private float onUpdateVelocity(Entity instance, Operation<Float> original) {
        if (instance != Schizoid.INSTANCE.getMc().player) {
            return original.call(instance);
        }
        EventUpdateVelocity event = new EventUpdateVelocity(original.call(instance));
        event.fire();
        return event.getYaw();
    }

    @Inject(method = "lerpPositionAndRotationStep", at = @At("RETURN"))
    private void onLerpPosAndRotation(int step, double x, double y, double z, double yaw, double pitch, CallbackInfo ci) {
        if ((Object) this != Schizoid.INSTANCE.getMc().player) {
            return;
        }
        new EventLerpPosAndRotation(step, x, y, z, yaw, pitch).fire();
    }

    @ModifyVariable(method = "lerpMotion", at = @At("HEAD"), argsOnly = true)
    private Vec3 onSetVelocityClient(Vec3 movement) {
        if ((Object) this != Schizoid.INSTANCE.getMc().player) {
            return movement;
        }
        EventVelocity event = new EventVelocity(movement.x, movement.y, movement.z);
        event.fire();
        return new Vec3(event.getX(), event.getY(), event.getZ());
    }
}
