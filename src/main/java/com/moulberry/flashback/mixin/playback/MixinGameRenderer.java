package com.moulberry.flashback.mixin.playback;

import com.moulberry.flashback.ui.UiOverlayCapture;
import com.moulberry.flashback.visuals.RecordedCameraRenderState;
import com.moulberry.flashback.compat.CameraModCompatibility;
import com.moulberry.flashback.IrisShaderCompatibility;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.EditorStateManager;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Options;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Objects;

@Mixin(GameRenderer.class)
public abstract class MixinGameRenderer {
    @Shadow
    @Final
    Minecraft minecraft;

    @Shadow @Final
    private GameRenderState gameRenderState;

    @WrapOperation(method = "extractOptions", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Options;getCloudStatus()Lnet/minecraft/client/CloudStatus;"), require = 0)
    public CloudStatus extractOptions_getCloudStatus(Options instance, Operation<CloudStatus> original) {
        EditorState editorState = EditorStateManager.getCurrent();
        if (editorState != null && !editorState.replayVisuals.renderSky) {
            return CloudStatus.OFF;
        } else {
            return original.call(instance);
        }
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/GuiRenderer;render()V"))
    private void flashback$skipGuiDrawOnly(GuiRenderer guiRenderer, Operation<Void> original) {
        // Returning from GameRenderer.render here also bypasses GuiRenderer,
        // RenderBuffers and CrossFrameResourcePool endFrame cleanup. Keep those
        // lifecycle calls even when the GUI itself is omitted from the export.
        if (!Flashback.isExporting() || !Flashback.EXPORT_JOB.getSettings().noGui()) {
            original.call(guiRenderer);
        } else {
            // GuiRenderer.render normally clears the extracted GUI tree.
            this.gameRenderState.guiRenderState.reset();
        }
    }

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/GuiRenderer;render()V", shift = At.Shift.BEFORE))
    public void flashback$beginUiOverlayCapture(CallbackInfo ci) {
        UiOverlayCapture.beginFrame(this.minecraft.getDeltaTracker());
    }

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/GuiRenderer;endFrame()V", shift = At.Shift.BEFORE))
    public void flashback$endUiOverlayCapture(CallbackInfo ci) {
        UiOverlayCapture.endFrame();
    }

    @Inject(method = "render", at = @At("RETURN"))
    public void flashback$restoreRecordedCameraRenderState(CallbackInfo ci) {
        RecordedCameraRenderState.endRenderFrame();
    }

    @Inject(method = "update", at = @At("HEAD"))
    public void flashback$applyRecordedCameraOwnershipBeforeUpdate(DeltaTracker deltaTracker, CallbackInfo ci) {
        if (!Flashback.isInReplay()) {
            return;
        }

        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);
        RecordedCameraRenderState.applyForRenderFrame(partialTick, "GameRenderer.update");
    }

    @WrapOperation(method = "renderItemInHand", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;getPlayerMode()Lnet/minecraft/world/level/GameType;"))
    public GameType getPlayerMode(MultiPlayerGameMode instance, Operation<GameType> original) {
        if (CameraModCompatibility.shouldDisableFlashbackHandSubstitution()) {
            return original.call(instance);
        }
        if (!IrisShaderCompatibility.shouldAllowVisualOverrides("first-person hand gamemode override")) {
            return original.call(instance);
        }
        AbstractClientPlayer spectatingPlayer = Flashback.getSpectatingPlayer();
        if (spectatingPlayer != null) {
            return Objects.requireNonNullElse(spectatingPlayer.gameMode(), GameType.SURVIVAL);
        }
        return original.call(instance);
    }

    @Inject(method = "tryTakeScreenshotIfNeeded", at = @At("HEAD"), cancellable = true)
    public void tryTakeScreenshotIfNeeded(CallbackInfo ci) {
        if (Flashback.isInReplay()) {
            ci.cancel();
        }
    }

    @Inject(method = "shouldRenderBlockOutline", at = @At("HEAD"), cancellable = true)
    public void shouldRenderBlockOutline(CallbackInfoReturnable<Boolean> cir) {
        if (Flashback.isInReplay()) {
            var cameraEntity = Minecraft.getInstance().getCameraEntity();
            if (cameraEntity == this.minecraft.player && cameraEntity.isSpectator()) {
                cir.setReturnValue(false);
            }
        }
    }

}
