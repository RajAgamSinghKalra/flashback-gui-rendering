package com.moulberry.flashback.ui;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.utils.FramebufferUtils;
import com.moulberry.flashback.exporting.SaveableFramebuffer;
import com.moulberry.flashback.record.Recorder;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.concurrent.locks.LockSupport;

public class UiOverlayCapture {
    private static NativeImage toNativeImage(com.moulberry.flashback.exporting.ImageFrame f) { if (f == null) return null; try (f) { if (f.format != com.moulberry.flashback.exporting.ImageFrame.Format.RGBA_U8) throw new IllegalStateException("Unsupported overlay format"); NativeImage n = new NativeImage(NativeImage.Format.RGBA, f.width, f.height, false); org.lwjgl.system.MemoryUtil.memCopy(f.pixels, n.getPointer(), f.size); return n; } }

    private static final int MAX_PENDING_DOWNLOADS = 4;
    private static final long DROP_WARNING_INTERVAL_NANOS = 5_000_000_000L;
    private static final double MIN_SAMPLE_TIME_STEP_TICKS = 0.01;
    private static final ArrayDeque<PendingDownload> PENDING_DOWNLOADS = new ArrayDeque<>();
    private static final ArrayDeque<DownloadTarget> AVAILABLE_DOWNLOADS = new ArrayDeque<>();

    private static @Nullable RenderTarget beforeBlurTarget;
    private static @Nullable RenderTarget afterBlurTarget;

    private static boolean active = false;
    private static @Nullable UiOverlayRecorder activeRecorder;
    private static int activeTick;
    private static float activePartialTick;
    private static int activeWidth;
    private static int activeHeight;
    private static int activeGuiScale;
    private static double activeSampleReplayTime;
    private static double activeMouseX;
    private static double activeMouseY;
    private static String activeCursorType = "none";
    private static boolean activeInteractive;
    private static boolean activeBeforeBlurDrawn;
    private static boolean activeAfterBlurDrawn;
    private static @Nullable CaptureDecision activeCaptureDecision;

    private static @Nullable UiOverlayRecorder lastRecorder;
    private static double lastCaptureReplayTime = Double.NEGATIVE_INFINITY;
    private static long lastCaptureNanos = Long.MIN_VALUE;
    private static int lastWidth = -1;
    private static int lastHeight = -1;
    private static int lastGuiScale = -1;
    private static String lastScreenKey = "";
    private static String lastCursorType = "";
    private static long lastDropWarningNanos = Long.MIN_VALUE;
    private static long lastAbortWarningNanos = Long.MIN_VALUE;
    private static int droppedDownloadFrames = 0;
    private static int abortedMirrorFrames = 0;
    private static int activeMirroredDrawRanges = 0;

    public static void beginFrame(DeltaTracker deltaTracker) {
        active = false;
        activeRecorder = null;
        activeBeforeBlurDrawn = false;
        activeAfterBlurDrawn = false;
        activeCaptureDecision = null;
        activeMirroredDrawRanges = 0;

        if (Flashback.shouldBlockUiOverlayCaptureForIris()) {
            return;
        }

        processPendingDownloads();

        Recorder recorder = Flashback.RECORDER;
        if (recorder == null || !recorder.readyToWrite() || Flashback.isInReplay() || Flashback.isExporting()) {
            return;
        }

        UiOverlayRecorder overlayRecorder = recorder.getUiOverlayRecorder();
        if (overlayRecorder == null) {
            return;
        }
        overlayRecorder.recordRenderFrame();

        Minecraft minecraft = Minecraft.getInstance();
        RenderTarget mainTarget = minecraft.gameRenderer.mainRenderTarget();
        if (mainTarget == null || mainTarget.width <= 0 || mainTarget.height <= 0) {
            return;
        }

        Window window = minecraft.getWindow();
        double windowWidth = Math.max(1, window.getWidth());
        double windowHeight = Math.max(1, window.getHeight());
        boolean screenVisible = minecraft.gui.screen() != null;
        String cursorType = screenVisible ? "arrow" : "none";
        String screenKey = screenVisible ? minecraft.gui.screen().getClass().getName() : "<none>";
        int guiScale = Math.max(1, window.getGuiScale());
        int captureWidth = mainTarget.width;
        int captureHeight = mainTarget.height;
        int tick = recorder.getWrittenTicks();
        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(true);

        if (!screenVisible) {
            overlayRecorder.recordHudCaptureSkipped();
            if (overlayRecorder == lastRecorder && !"<none>".equals(lastScreenKey)) {
                double replayTime = tick + partialTick;
                long now = System.nanoTime();
                double sampleReplayTime = nextMonotonicEventTime(replayTime);
                int clearTick = toTick(sampleReplayTime);
                float clearPartialTick = toPartialTick(sampleReplayTime, clearTick);
                overlayRecorder.recordClearFrame(clearTick, clearPartialTick, sampleReplayTime, captureWidth, captureHeight, guiScale);
                rememberCapture(new CaptureDecision(true, overlayRecorder, replayTime, sampleReplayTime, now,
                    captureWidth, captureHeight, guiScale, screenKey, cursorType));
            }
            return;
        }

        CaptureDecision captureDecision = getCaptureDecision(overlayRecorder, tick, partialTick, captureWidth, captureHeight, guiScale, screenKey, cursorType);
        if (!captureDecision.shouldCapture()) {
            return;
        }
        overlayRecorder.recordCaptureDecision();
        overlayRecorder.recordScreenCapture(captureWidth, captureHeight);

        if (PENDING_DOWNLOADS.size() >= MAX_PENDING_DOWNLOADS) {
            droppedDownloadFrames += 1;
            overlayRecorder.recordSkippedReadback();
            warnDroppedDownloads();
            return;
        }

        beforeBlurTarget = FramebufferUtils.resizeOrCreateFramebuffer(beforeBlurTarget, captureWidth, captureHeight, false);
        afterBlurTarget = FramebufferUtils.resizeOrCreateFramebuffer(afterBlurTarget, captureWidth, captureHeight, false);
        FramebufferUtils.clear(beforeBlurTarget, FramebufferUtils.TRANSPARENT_CLEAR_COLOUR);
        FramebufferUtils.clear(afterBlurTarget, FramebufferUtils.TRANSPARENT_CLEAR_COLOUR);

        active = true;
        activeRecorder = overlayRecorder;
        activeTick = toTick(captureDecision.sampleReplayTime());
        activePartialTick = toPartialTick(captureDecision.sampleReplayTime(), activeTick);
        activeWidth = captureWidth;
        activeHeight = captureHeight;
        activeGuiScale = guiScale;
        activeSampleReplayTime = captureDecision.sampleReplayTime();
        activeMouseX = minecraft.mouseHandler.xpos() * captureWidth / windowWidth;
        activeMouseY = minecraft.mouseHandler.ypos() * captureHeight / windowHeight;
        activeCursorType = cursorType;
        activeInteractive = true;
        activeBeforeBlurDrawn = false;
        activeAfterBlurDrawn = false;
        activeCaptureDecision = captureDecision;
        activeMirroredDrawRanges = 0;
    }

    private static CaptureDecision getCaptureDecision(UiOverlayRecorder recorder, int tick, float partialTick, int width, int height,
                                                      int guiScale, String screenKey, String cursorType) {
        int captureFps = Math.max(1, Math.min(60, Flashback.getConfig().recording.uiOverlayCaptureFps));
        double replayTime = tick + partialTick;
        long now = System.nanoTime();

        // Fractional tick values may step backwards briefly after a pause, and
        // synthetic samples can be ahead of the replay clock. Neither starts a
        // new recording; resetting here can put old screen frames after a clear.
        boolean resetTimeline = recorder != lastRecorder;
        boolean force = resetTimeline || width != lastWidth || height != lastHeight || guiScale != lastGuiScale ||
            !screenKey.equals(lastScreenKey) || !cursorType.equals(lastCursorType);

        double replayInterval = 20.0 / captureFps;
        long wallInterval = 1_000_000_000L / captureFps;
        boolean replayTimeDue = replayTime >= lastCaptureReplayTime + replayInterval - 0.0001;
        boolean wallTimeDue = now - lastCaptureNanos >= wallInterval;
        boolean stalledReplayTimeDue = wallTimeDue && replayTime < lastCaptureReplayTime + replayInterval - 0.0001;

        if (!force && !replayTimeDue && !stalledReplayTimeDue) {
            return new CaptureDecision(false, recorder, replayTime, replayTime, now, width, height, guiScale, screenKey, cursorType);
        }

        double sampleReplayTime = replayTime;
        if (!force && stalledReplayTimeDue) {
            sampleReplayTime = Math.max(replayTime, lastCaptureReplayTime + replayInterval);
        }
        if (!resetTimeline) {
            sampleReplayTime = Math.max(sampleReplayTime, lastCaptureReplayTime + MIN_SAMPLE_TIME_STEP_TICKS);
        }

        return new CaptureDecision(true, recorder, replayTime, sampleReplayTime, now, width, height, guiScale, screenKey, cursorType);
    }

    private static double nextMonotonicEventTime(double replayTime) {
        if (Double.isInfinite(lastCaptureReplayTime)) {
            return replayTime;
        }
        return Math.max(replayTime, lastCaptureReplayTime + MIN_SAMPLE_TIME_STEP_TICKS);
    }

    private static void rememberCapture(CaptureDecision decision) {
        lastRecorder = decision.recorder();
        lastCaptureReplayTime = decision.sampleReplayTime();
        lastCaptureNanos = decision.captureNanos();
        lastWidth = decision.width();
        lastHeight = decision.height();
        lastGuiScale = decision.guiScale();
        lastScreenKey = decision.screenKey();
        lastCursorType = decision.cursorType();
    }

    public static @Nullable RenderTarget getCaptureTarget(boolean afterBlur) {
        if (!active) {
            return null;
        }
        UiOverlayRecorder recorder = activeRecorder;
        if (afterBlur) {
            activeAfterBlurDrawn = true;
            if (recorder != null) {
                recorder.recordLayerDraw(true);
            }
            return afterBlurTarget;
        }

        activeBeforeBlurDrawn = true;
        if (recorder != null) {
            recorder.recordLayerDraw(false);
        }
        return beforeBlurTarget;
    }

    public static void runWithCaptureWindowState(Runnable runnable) {
        runnable.run();
    }

    public static void recordMirroredDraw(String name) {
        if (active) {
            activeMirroredDrawRanges += 1;
        }
    }

    public static void abortActiveFrame(String reason, RuntimeException exception) {
        if (!active) {
            return;
        }
        abortedMirrorFrames += 1;
        if (activeRecorder != null) {
            activeRecorder.recordSkippedReadback();
        }
        warnAbortedMirror(reason, exception);
        resetActiveFrameState();
    }

    public static void endFrame() {
        if (Flashback.shouldBlockUiOverlayCaptureForIris()) {
            resetActiveFrameState();
            return;
        }

        if (!active || activeRecorder == null || beforeBlurTarget == null || afterBlurTarget == null) {
            resetActiveFrameState();
            return;
        }

        if (!activeBeforeBlurDrawn && !activeAfterBlurDrawn) {
            if (activeCaptureDecision != null) {
                rememberCapture(activeCaptureDecision);
            }
            activeRecorder.recordFrame(activeTick, activePartialTick, activeSampleReplayTime, activeWidth, activeHeight,
                activeGuiScale, null, null, activeMouseX, activeMouseY, activeCursorType, activeInteractive);
            resetActiveFrameState();
            processPendingDownloads();
            return;
        }

        DownloadTarget target = obtainDownloadTarget(activeWidth, activeHeight, activeBeforeBlurDrawn, activeAfterBlurDrawn);
        if (target == null) {
            droppedDownloadFrames += 1;
            activeRecorder.recordSkippedReadback();
            warnDroppedDownloads();
            resetActiveFrameState();
            return;
        }

        activeRecorder.recordReadbackStarted();
        if (activeCaptureDecision != null) {
            rememberCapture(activeCaptureDecision);
        }

        if (activeBeforeBlurDrawn && target.beforeDownload != null) {
            target.beforeDownload.startDownload(beforeBlurTarget.getColorTexture());
        }
        if (activeAfterBlurDrawn && target.afterDownload != null) {
            target.afterDownload.startDownload(afterBlurTarget.getColorTexture());
        }

        PENDING_DOWNLOADS.add(new PendingDownload(activeRecorder, activeTick, activePartialTick, activeWidth, activeHeight,
            activeGuiScale, activeSampleReplayTime, activeMouseX, activeMouseY, activeCursorType, activeInteractive, target));

        resetActiveFrameState();
        processPendingDownloads();
    }

    public static boolean isCapturing() {
        return active;
    }

    public static void flushRecorder(UiOverlayRecorder recorder) {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (hasPendingDownloads(recorder) && System.nanoTime() < deadline) {
            RenderSystem.executePendingTasks();
            processPendingDownloads();
            LockSupport.parkNanos("waiting for UI overlay downloads", 100_000L);
        }

        if (hasPendingDownloads(recorder)) {
            Flashback.LOGGER.warn("Timed out waiting for UI overlay frame downloads");
        }
    }

    private static boolean hasPendingDownloads(UiOverlayRecorder recorder) {
        for (PendingDownload pendingDownload : PENDING_DOWNLOADS) {
            if (pendingDownload.recorder == recorder) {
                return true;
            }
        }
        return false;
    }

    private static void processPendingDownloads() {
        Iterator<PendingDownload> iterator = PENDING_DOWNLOADS.iterator();
        while (iterator.hasNext()) {
            PendingDownload pending = iterator.next();
            if (!pending.target.canFinishDownload()) {
                continue;
            }

            NativeImage before = pending.target.finishBeforeDownload();
            NativeImage after = pending.target.finishAfterDownload();
            pending.recorder.recordReadbackCompleted();

            pending.recorder.recordFrame(pending.tick, pending.partialTick, pending.sampleReplayTime, pending.width, pending.height, pending.guiScale,
                before, after, pending.mouseX, pending.mouseY, pending.cursorType, pending.interactive);

            iterator.remove();
            AVAILABLE_DOWNLOADS.addLast(pending.target);
        }
    }

    private static @Nullable DownloadTarget obtainDownloadTarget(int width, int height, boolean needsBefore, boolean needsAfter) {
        while (!AVAILABLE_DOWNLOADS.isEmpty()) {
            DownloadTarget target = AVAILABLE_DOWNLOADS.removeFirst();
            if (target.width == width && target.height == height &&
                target.hasBeforeDownload() == needsBefore && target.hasAfterDownload() == needsAfter) {
                return target;
            }
            target.close();
        }

        return new DownloadTarget(width, height, needsBefore, needsAfter);
    }

    private static void warnDroppedDownloads() {
        long now = System.nanoTime();
        if (lastDropWarningNanos != Long.MIN_VALUE && now - lastDropWarningNanos < DROP_WARNING_INTERVAL_NANOS) {
            return;
        }

        Flashback.LOGGER.warn("Skipped {} UI overlay frames because capture readbacks are overloaded", droppedDownloadFrames);
        droppedDownloadFrames = 0;
        lastDropWarningNanos = now;
    }

    private static void warnAbortedMirror(String reason, RuntimeException exception) {
        long now = System.nanoTime();
        if (lastAbortWarningNanos != Long.MIN_VALUE && now - lastAbortWarningNanos < DROP_WARNING_INTERVAL_NANOS) {
            return;
        }

        Flashback.LOGGER.warn("Aborted {} UI overlay capture mirror frames; live Minecraft GUI was already rendered and left untouched. Last failure: {}", abortedMirrorFrames, reason, exception);
        abortedMirrorFrames = 0;
        lastAbortWarningNanos = now;
    }

    private static void resetActiveFrameState() {
        active = false;
        activeRecorder = null;
        activeBeforeBlurDrawn = false;
        activeAfterBlurDrawn = false;
        activeCaptureDecision = null;
        activeMirroredDrawRanges = 0;
    }

    private static int toTick(double replayTime) {
        return (int) Math.floor(Math.max(0.0, replayTime));
    }

    private static float toPartialTick(double replayTime, int tick) {
        return (float) Math.max(0.0, Math.min(0.999999, replayTime - tick));
    }

    private record PendingDownload(UiOverlayRecorder recorder, int tick, float partialTick, int width, int height,
                                   int guiScale, double sampleReplayTime, double mouseX, double mouseY, String cursorType, boolean interactive,
                                   DownloadTarget target) {}

    private record CaptureDecision(boolean shouldCapture, UiOverlayRecorder recorder, double replayTime, double sampleReplayTime, long captureNanos,
                                   int width, int height, int guiScale, String screenKey, String cursorType) {}

    private static class DownloadTarget {
        private final int width;
        private final int height;
        private final @Nullable SaveableFramebuffer beforeDownload;
        private final @Nullable SaveableFramebuffer afterDownload;

        private DownloadTarget(int width, int height, boolean needsBefore, boolean needsAfter) {
            this.width = width;
            this.height = height;
            this.beforeDownload = needsBefore ? new SaveableFramebuffer(width, height) : null;
            this.afterDownload = needsAfter ? new SaveableFramebuffer(width, height) : null;
        }

        private boolean hasBeforeDownload() {
            return this.beforeDownload != null;
        }

        private boolean hasAfterDownload() {
            return this.afterDownload != null;
        }

        private boolean canFinishDownload() {
            return (this.beforeDownload == null || this.beforeDownload.canFinishDownload()) &&
                (this.afterDownload == null || this.afterDownload.canFinishDownload());
        }

        private @Nullable NativeImage finishBeforeDownload() {
            return this.beforeDownload == null ? null : toNativeImage(this.beforeDownload.finishDownload());
        }

        private @Nullable NativeImage finishAfterDownload() {
            return this.afterDownload == null ? null : toNativeImage(this.afterDownload.finishDownload());
        }

        private void close() {
            if (this.beforeDownload != null) {
                this.beforeDownload.close();
            }
            if (this.afterDownload != null) {
                this.afterDownload.close();
            }
        }
    }
}
