package com.moulberry.flashback.mixin.playback;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.ui.UiOverlayCapture;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.function.Supplier;

@Mixin(GuiRenderer.class)
public abstract class MixinGuiRenderer {

    @WrapOperation(method = "draw", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/GuiRenderer;executeDrawRange(Ljava/util/function/Supplier;Lcom/mojang/blaze3d/pipeline/RenderTarget;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;II)V"))
    private void flashback$captureUiDrawRange(GuiRenderer instance, Supplier<String> name, RenderTarget target,
                                              GpuBufferSlice transforms, int start, int end, Operation<Void> original) {
        original.call(instance, name, target, transforms, start, end);

        RenderTarget captureTarget = Flashback.shouldBlockUiOverlayCaptureForIris() ? null : UiOverlayCapture.getCaptureTarget(start > 0);
        if (captureTarget != null && captureTarget.width > 0 && captureTarget.height > 0) {
            String captureName;
            try {
                captureName = name.get();
            } catch (RuntimeException ignored) {
                captureName = "unknown GUI draw range";
            }
            try {
                UiOverlayCapture.runWithCaptureWindowState(() -> original.call(instance, name, captureTarget, transforms, start, end));
                UiOverlayCapture.recordMirroredDraw(captureName);
            } catch (RuntimeException exception) {
                UiOverlayCapture.abortActiveFrame("GUI mirror draw failed for " + captureName, exception);
            }
        }
    }
}
