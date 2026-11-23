package com.moulberry.flashback.mixin.compat.emf;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.exporting.ExportJob;
import com.moulberry.mixinconstraints.annotations.IfModLoaded;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keep synthetic export warm-up frames from advancing frame_time animations. */
@IfModLoaded("entity_model_features")
@Pseudo
@Mixin(targets = "traben.entity_model_features.models.animation.math.EMFMath", remap = false)
public abstract class MixinEmfAnimationTime {
    @Inject(method = "getFrameTime", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void flashback$holdFrameTimeDuringWarmup(CallbackInfoReturnable<Float> cir) {
        ExportJob job = Flashback.EXPORT_JOB;
        if (job != null && Flashback.isExporting() && job.isEntityAnimationWarmupFrame()) {
            // Match EMF's own safe nonzero value for frozen frames.
            cir.setReturnValue(0.000000001f);
        }
    }
}
