package com.moulberry.flashback.mixin.playback;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.IrisShaderCompatibility;
import com.moulberry.flashback.combo_options.ExportProjection;
import com.moulberry.flashback.exporting.ExportJob;
import net.minecraft.client.renderer.culling.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Frustum.class)
public class MixinFrustum {

    @Inject(method = "offsetToFullyIncludeCameraCube", at = @At("HEAD"), cancellable = true)
    public void offsetToFullyIncludeCameraCube(int cubeSize, CallbackInfoReturnable<Frustum> cir) {
        ExportJob exportJob = Flashback.EXPORT_JOB;
        if (exportJob != null && exportJob.getEffectiveProjection() == ExportProjection.ORTHOGRAPHIC &&
            IrisShaderCompatibility.shouldAllowCameraOverrides("orthographic frustum offset override")) {
            cir.setReturnValue((Frustum) (Object) this);
        }
    }

}
