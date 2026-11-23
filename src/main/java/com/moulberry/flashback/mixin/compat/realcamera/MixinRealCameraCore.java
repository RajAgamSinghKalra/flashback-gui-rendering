package com.moulberry.flashback.mixin.compat.realcamera;

import com.moulberry.flashback.visuals.RecordedCameraRenderState;
import com.moulberry.mixinconstraints.annotations.IfModLoaded;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@IfModLoaded("realcamera")
@Pseudo
@Mixin(targets = "com.xtracr.realcamera.RealCameraCore", remap = false)
public class MixinRealCameraCore {

    @Inject(method = "computeCamera", at = @At("HEAD"), remap = false)
    private static void flashback$applyRecordedCameraBeforeRealCamera(Minecraft client, float partialTicks, CallbackInfo ci) {
        RecordedCameraRenderState.applyForRenderFrame(partialTicks, "RealCameraCore.computeCamera");
    }
}
