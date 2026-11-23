package com.moulberry.flashback.mixin.compat.firstperson;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.moulberry.flashback.visuals.RecordedCameraRenderState;
import com.moulberry.mixinconstraints.annotations.IfModLoaded;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@IfModLoaded("firstperson")
@Pseudo
@Mixin(targets = "dev.tr7zw.firstperson.LogicHandler", remap = false)
public class MixinFirstPersonLogicHandler {

    @Inject(method = "shouldApplyThirdPerson", at = @At("HEAD"), cancellable = true, remap = false)
    public void flashback$disableBodyWhenRecordedCameraPlayerWouldDisableIt(boolean thirdPerson, CallbackInfoReturnable<Boolean> cir) {
        if (thirdPerson) {
            return;
        }

        Entity entity = RecordedCameraRenderState.getEffectiveCameraEntity();
        if (!(entity instanceof Player player)) {
            return;
        }

        if (player.isSleeping() || player.isAutoSpinAttack() || player.isFallFlying() || player.isScoping() ||
            (player.getSwimAmount(1.0f) != 0.0f && !player.isVisuallySwimming())) {
            cir.setReturnValue(false);
        }
    }

    @WrapOperation(method = "updatePositionOffset", at = @At(value = "INVOKE", target = "Ldev/tr7zw/firstperson/LogicHandler;isCrawlingOrSwimming(Lnet/minecraft/world/entity/player/Player;)Z", remap = false), remap = false)
    public boolean flashback$useRecordedCameraPlayerForSwimChecks(@Coerce Object instance, Player player, Operation<Boolean> original,
                                                                 @Local(argsOnly = true) Entity entity) {
        if (entity instanceof Player entityPlayer) {
            return original.call(instance, entityPlayer);
        }
        return original.call(instance, player);
    }
}
