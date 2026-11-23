package com.moulberry.flashback.ui;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.IrisAmdTdrSafeMode;
import com.moulberry.flashback.IrisShaderCompatibility;
import com.moulberry.flashback.editor.ui.ReplayUI;
import com.moulberry.flashback.playback.ReplayServer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.jetbrains.annotations.Nullable;

public class UiOverlayPreview {

    private static @Nullable ReplayServer activeReplayServer;
    private static @Nullable UiOverlayPlayback playback;
    private static @Nullable DynamicTexture texture;
    private static int textureWidth;
    private static int textureHeight;

    public static void extract(GuiGraphicsExtractor guiGraphics) {
        ReplayServer replayServer = Flashback.getReplayServer();
        if (replayServer == null || Flashback.isExporting() || !ReplayUI.isActive()) {
            close();
            return;
        }
        if (!IrisShaderCompatibility.shouldAllowRecordedUiOverlayPreview("recorded UI overlay preview")) {
            close();
            return;
        }
        if (IrisAmdTdrSafeMode.isActive()) {
            IrisAmdTdrSafeMode.logUiOverlayPreviewCpuMode();
        }

        UiOverlayPlayback activePlayback = getOrCreatePlayback(replayServer);
        if (activePlayback == null) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        RenderTarget mainTarget = minecraft.gameRenderer.mainRenderTarget();
        int width = mainTarget != null && mainTarget.width > 0 ? mainTarget.width : minecraft.getWindow().getWidth();
        int height = mainTarget != null && mainTarget.height > 0 ? mainTarget.height : minecraft.getWindow().getHeight();
        width = Math.max(1, width);
        height = Math.max(1, height);

        ensureTexture(width, height);
        if (texture == null) {
            return;
        }

        NativeImage pixels = texture.getPixels();
        pixels.fillRect(0, 0, width, height, 0x00000000);
        activePlayback.compositeOverlay(pixels, replayServer.getPartialReplayTick());
        texture.upload();

        guiGraphics.nextStratum();
        guiGraphics.blit(texture.getTextureView(), texture.getSampler(), 0, 0,
            guiGraphics.guiWidth(), guiGraphics.guiHeight(), 0.0f, 1.0f, 0.0f, 1.0f);
    }

    public static void close() {
        activeReplayServer = null;
        if (playback != null) {
            playback.close();
            playback = null;
        }
        if (texture != null) {
            texture.close();
            texture = null;
        }
        textureWidth = 0;
        textureHeight = 0;
    }

    public static boolean shouldSuppressLiveGui() {
        ReplayServer replayServer = Flashback.getReplayServer();
        if (replayServer == null || Flashback.isExporting() || !ReplayUI.isActive()) {
            return false;
        }
        if (!IrisShaderCompatibility.shouldAllowRecordedUiLiveGuiSuppression("recorded UI live GUI suppression")) {
            return false;
        }

        UiOverlayPlayback activePlayback = getOrCreatePlayback(replayServer);
        return activePlayback != null && activePlayback.hasVisibleRecordedScreenOverlay(replayServer.getPartialReplayTick());
    }

    private static @Nullable UiOverlayPlayback getOrCreatePlayback(ReplayServer replayServer) {
        if (replayServer.getMetadata().uiOverlay == null) {
            if (activeReplayServer != replayServer) {
                close();
                activeReplayServer = replayServer;
                Flashback.LOGGER.warn("Replay has no recorded UI overlay metadata; preview/export can only show live replay HUD for this recording. Most likely recordUiOverlay was disabled when this replay was recorded, or no inventory/chat/container UI was opened.");
            }
            return null;
        }

        if (replayServer != activeReplayServer) {
            close();
            activeReplayServer = replayServer;
            playback = UiOverlayPlayback.fromReplay(replayServer);
        }
        return playback;
    }

    private static void ensureTexture(int width, int height) {
        if (texture != null && textureWidth == width && textureHeight == height) {
            return;
        }

        if (texture != null) {
            texture.close();
        }
        texture = new DynamicTexture("flashback ui overlay preview", width, height, true);
        textureWidth = width;
        textureHeight = height;
    }
}
