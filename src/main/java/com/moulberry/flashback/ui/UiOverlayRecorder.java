package com.moulberry.flashback.ui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.platform.NativeImage;
import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.record.FlashbackMeta;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class UiOverlayRecorder {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final long SOFT_ENCODE_QUEUE_BYTES = 128L * 1024L * 1024L;
    private static final long HARD_ENCODE_QUEUE_BYTES = 512L * 1024L * 1024L;
    private static final int TILE_SIZE = 256;
    private static final int TILED_LAYER_MIN_SIZE = TILE_SIZE * 2;
    private static final int OUTER_EDGE_ARTIFACT_MAX_THICKNESS = 16;
    private static final int OUTER_EDGE_ARTIFACT_MAX_AREA = 4096;
    private static final int DIM_BACKGROUND_ALPHA_CUTOFF = 240;
    private static final int NEAR_BLACK_CUTOFF = 8;
    private static final int ENCODER_THREAD_COUNT = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors() - 1));
    private static final long ENCODE_WARNING_INTERVAL_NANOS = 5_000_000_000L;

    private final Path recordFolder;
    private final UiOverlayManifest manifest = new UiOverlayManifest();
    private final Set<String> writtenFrameHashes = ConcurrentHashMap.newKeySet();
    private final Object manifestLock = new Object();
    private final Object queueLock = new Object();
    private final Object resultLock = new Object();
    private final ArrayDeque<FrameJob> encodeQueue = new ArrayDeque<>();
    private final Thread[] encoderThreads = new Thread[ENCODER_THREAD_COUNT];
    private final Map<Long, FrameResult> pendingResults = new HashMap<>();

    private volatile boolean acceptingFrames = true;
    private long queuedImageBytes = 0;
    private int processingJobs = 0;
    private long nextSequence = 0;
    private long nextAppendSequence = 0;
    private int droppedEncodeFrames = 0;
    private long lastEncodeWarningNanos = Long.MIN_VALUE;
    private @Nullable String lastLayerSignature = null;
    private @Nullable String lastCursorSignature = null;
    private boolean hasSamples = false;
    private boolean manifestClosed = false;

    public UiOverlayRecorder(Path recordFolder) {
        this.recordFolder = recordFolder;
        for (int i = 0; i < this.encoderThreads.length; i++) {
            Thread thread = new Thread(this::runEncoder, "Flashback UI Overlay Encoder " + (i + 1));
            thread.setDaemon(true);
            this.encoderThreads[i] = thread;
            thread.start();
        }
    }

    public void recordRenderFrame() {
        synchronized (this.manifestLock) {
            this.manifest.captureStats.renderFrames += 1;
        }
    }

    public void recordCaptureDecision() {
        synchronized (this.manifestLock) {
            this.manifest.captureStats.captureDecisions += 1;
        }
    }

    public void recordScreenCapture(int width, int height) {
        synchronized (this.manifestLock) {
            this.manifest.captureStats.screenCaptures += 1;
            this.manifest.captureStats.expectedScreenSamples += 1;
            this.manifest.captureStats.maxCaptureWidth = Math.max(this.manifest.captureStats.maxCaptureWidth, width);
            this.manifest.captureStats.maxCaptureHeight = Math.max(this.manifest.captureStats.maxCaptureHeight, height);
        }
    }

    public void recordLayerDraw(boolean afterBlur) {
        synchronized (this.manifestLock) {
            if (afterBlur) {
                this.manifest.captureStats.afterBlurLayerDraws += 1;
            } else {
                this.manifest.captureStats.beforeBlurLayerDraws += 1;
            }
        }
    }

    public void recordHudCaptureSkipped() {
        synchronized (this.manifestLock) {
            this.manifest.captureStats.hudCapturesSkipped += 1;
        }
    }

    public void recordReadbackStarted() {
        synchronized (this.manifestLock) {
            this.manifest.captureStats.readbackStarts += 1;
        }
    }

    public void recordReadbackCompleted() {
        synchronized (this.manifestLock) {
            this.manifest.captureStats.readbackCompletions += 1;
        }
    }

    public void recordSkippedReadback() {
        synchronized (this.manifestLock) {
            this.manifest.captureStats.skippedReadbacks += 1;
        }
    }

    public void recordFrame(int tick, float partialTick, double sampleTime, int width, int height, int guiScale,
                            @Nullable NativeImage beforeBlur, @Nullable NativeImage afterBlur,
                            double mouseX, double mouseY, String cursorType, boolean interactive) {
        long sequence;
        synchronized (this.resultLock) {
            sequence = this.nextSequence;
            this.nextSequence += 1;
        }

        FrameJob job = new FrameJob(sequence, tick, partialTick, sampleTime, width, height, guiScale, beforeBlur, afterBlur,
            mouseX, mouseY, cursorType, interactive);

        if (!this.acceptingFrames) {
            closeJob(job);
            this.markSkippedSequence(job);
            return;
        }

        this.appendCursorSample(job);

        if (this.offerJob(job)) {
            return;
        }

        closeJob(job);
        this.markEncodeDrop(job);
    }

    public void recordClearFrame(int tick, float partialTick, double sampleTime, int width, int height, int guiScale) {
        long sequence;
        synchronized (this.resultLock) {
            sequence = this.nextSequence;
            this.nextSequence += 1;
        }

        FrameJob job = FrameJob.clear(sequence, tick, partialTick, sampleTime, width, height, guiScale);
        if (!this.acceptingFrames) {
            this.markSkippedSequence(job);
            return;
        }

        this.appendCursorSample(job);
        synchronized (this.resultLock) {
            this.pendingResults.put(sequence, new FrameResult(sequence, job, null, null, false));
            this.appendReadyResults();
            this.resultLock.notifyAll();
        }
    }

    public void finish(FlashbackMeta metadata) {
        this.acceptingFrames = false;
        synchronized (this.queueLock) {
            this.queueLock.notifyAll();
        }

        this.waitForEncoder();

        synchronized (this.manifestLock) {
            this.manifestClosed = true;
            this.sortAndRecomputeStats();
            this.logSummary();
            if (this.manifest.samples.isEmpty() && this.manifest.cursorSamples.isEmpty()) {
                Flashback.LOGGER.warn(
                    "UI overlay recording finished with no serialized samples. If inventory/chat/container UI was opened, check skippedReadbacks={}, skippedEncodeJobs={} (interactive={}, static={}), screenCaptures={}, hudCapturesSkipped={}, and whether capture was disabled by config or Iris safety policy.",
                    this.manifest.captureStats.skippedReadbacks,
                    this.manifest.captureStats.skippedEncodeJobs,
                    this.manifest.captureStats.skippedInteractiveEncodeJobs,
                    this.manifest.captureStats.skippedStaticEncodeJobs,
                    this.manifest.captureStats.screenCaptures,
                    this.manifest.captureStats.hudCapturesSkipped
                );
                metadata.uiOverlay = null;
                return;
            }

            try {
                Path overlayRoot = this.recordFolder.resolve(UiOverlayManifest.DIRECTORY);
                Files.createDirectories(overlayRoot);
                Files.writeString(this.recordFolder.resolve(UiOverlayManifest.MANIFEST_ENTRY),
                    GSON.toJson(this.manifest.toJson()), StandardCharsets.UTF_8);
                metadata.uiOverlay = this.manifest.toMetadata();
            } catch (IOException e) {
                Flashback.LOGGER.warn("Failed to finish UI overlay manifest", e);
                metadata.uiOverlay = null;
            }
        }
    }

    public boolean hasSamples() {
        synchronized (this.manifestLock) {
            return !this.manifest.samples.isEmpty() || !this.manifest.cursorSamples.isEmpty();
        }
    }

    private boolean offerJob(FrameJob job) {
        List<FrameJob> evicted = new ArrayList<>();
        boolean accepted = false;

        synchronized (this.queueLock) {
            if (!this.acceptingFrames) {
                return false;
            }

            long limit = job.interactive ? HARD_ENCODE_QUEUE_BYTES : SOFT_ENCODE_QUEUE_BYTES;
            if (job.interactive) {
                this.evictStaticJobsFor(job.imageBytes, SOFT_ENCODE_QUEUE_BYTES, evicted);
                if (this.queuedImageBytes + job.imageBytes > limit) {
                    this.evictStaticJobsFor(job.imageBytes, limit, evicted);
                }
                // Give encoders a short, bounded chance to catch up before dropping
                // an interactive screen frame; never grow beyond the memory cap.
                long deadline = System.nanoTime() + 8_000_000L;
                while (this.acceptingFrames && this.queuedImageBytes + job.imageBytes > limit) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) break;
                    try {
                        this.queueLock.wait(Math.max(1L, remaining / 1_000_000L));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            if (this.acceptingFrames && this.queuedImageBytes + job.imageBytes <= limit) {
                this.encodeQueue.addLast(job);
                this.queuedImageBytes += job.imageBytes;
                this.recordQueueHighWaterLocked();
                this.queueLock.notifyAll();
                accepted = true;
            }
        }

        for (FrameJob evictedJob : evicted) {
            closeJob(evictedJob);
            this.markEncodeDrop(evictedJob);
        }
        return accepted;
    }

    private void evictStaticJobsFor(long incomingBytes, long limit, List<FrameJob> evicted) {
        var iterator = this.encodeQueue.iterator();
        while (this.queuedImageBytes + incomingBytes > limit && iterator.hasNext()) {
            FrameJob queued = iterator.next();
            if (queued.interactive) {
                continue;
            }

            iterator.remove();
            this.queuedImageBytes -= queued.imageBytes;
            evicted.add(queued);
        }
    }

    private void recordQueueHighWaterLocked() {
        synchronized (this.manifestLock) {
            this.manifest.captureStats.maxEncodeQueueBytes =
                Math.max(this.manifest.captureStats.maxEncodeQueueBytes, this.queuedImageBytes);
            this.manifest.captureStats.maxEncodeQueueJobs =
                Math.max(this.manifest.captureStats.maxEncodeQueueJobs, this.encodeQueue.size());
        }
    }

    private void runEncoder() {
        while (true) {
            FrameJob job = this.takeEncodeJob();
            if (job == null) {
                break;
            }

            synchronized (this.resultLock) {
                this.processingJobs += 1;
            }

            long startNanos = System.nanoTime();
            FrameResult result;
            try {
                result = this.processFrame(job);
            } catch (Exception e) {
                Flashback.LOGGER.warn("Failed to record UI overlay frame", e);
                closeJob(job);
                result = FrameResult.skipped(job);
            }

            long latencyMillis = (System.nanoTime() - startNanos) / 1_000_000L;
            synchronized (this.manifestLock) {
                this.manifest.captureStats.maxEncodeLatencyMillis =
                    Math.max(this.manifest.captureStats.maxEncodeLatencyMillis, latencyMillis);
            }

            synchronized (this.resultLock) {
                this.processingJobs -= 1;
                this.pendingResults.put(result.sequence, result);
                this.appendReadyResults();
                this.resultLock.notifyAll();
            }
        }

        synchronized (this.resultLock) {
            this.resultLock.notifyAll();
        }
    }

    private @Nullable FrameJob takeEncodeJob() {
        synchronized (this.queueLock) {
            while (true) {
                FrameJob job = this.encodeQueue.pollFirst();
                if (job != null) {
                    this.queuedImageBytes -= job.imageBytes;
                    this.queueLock.notifyAll();
                    return job;
                }

                if (!this.acceptingFrames) {
                    return null;
                }

                try {
                    this.queueLock.wait(100L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
    }

    private boolean isEncodeQueueEmpty() {
        synchronized (this.queueLock) {
            return this.encodeQueue.isEmpty();
        }
    }

    private void waitForEncoder() {
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (System.nanoTime() < deadline) {
            synchronized (this.resultLock) {
                this.appendReadyResults();
                if (this.isEncodeQueueEmpty() && this.processingJobs == 0 && this.nextAppendSequence >= this.nextSequence) {
                    break;
                }

                try {
                    this.resultLock.wait(50L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        for (Thread encoderThread : this.encoderThreads) {
            try {
                encoderThread.join(500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            if (encoderThread.isAlive()) {
                Flashback.LOGGER.warn("UI overlay encoder thread did not finish before manifest write");
            }
        }
    }

    private FrameResult processFrame(FrameJob job) throws IOException {
        UiOverlayManifest.Layer beforeLayer = this.processLayer(job.beforeBlur);
        UiOverlayManifest.Layer afterLayer = this.processLayer(job.afterBlur);
        return new FrameResult(job.sequence, job, beforeLayer, afterLayer, false);
    }

    private void appendReadyResults() {
        while (true) {
            FrameResult result = this.pendingResults.remove(this.nextAppendSequence);
            if (result == null) {
                return;
            }

            this.nextAppendSequence += 1;
            if (result.skipped) {
                continue;
            }

            if (result.job.clearFrame) {
                this.appendClearSample(result.job);
            } else if (result.job.interactive) {
                this.appendInteractiveSample(result.job, result.beforeLayer, result.afterLayer);
            } else {
                this.appendStaticSample(result.job, result.beforeLayer, result.afterLayer);
            }
        }
    }

    private void appendCursorSample(FrameJob job) {
        synchronized (this.manifestLock) {
            if (this.manifestClosed) {
                return;
            }

            this.ensureSourceSize(job);
            String signature = cursorSignature(job);
            boolean cursorVisible = !"none".equals(job.cursorType);
            if (!cursorVisible && this.manifest.cursorSamples.isEmpty()) {
                this.manifest.captureStats.duplicateCursorSamples += 1;
                this.lastCursorSignature = signature;
                return;
            }
            if (!cursorVisible && signature.equals(this.lastCursorSignature)) {
                this.manifest.captureStats.duplicateCursorSamples += 1;
                return;
            }

            UiOverlayManifest.CursorSample sample = new UiOverlayManifest.CursorSample();
            sample.tick = job.tick;
            sample.partialTick = job.partialTick;
            sample.sampleTime = job.sampleTime;
            sample.sourceWidth = job.width;
            sample.sourceHeight = job.height;
            sample.mouseX = job.mouseX;
            sample.mouseY = job.mouseY;
            sample.cursorType = job.cursorType;
            this.manifest.cursorSamples.add(sample);
            this.manifest.captureStats.appendedCursorSamples += 1;
            this.lastCursorSignature = signature;
        }
    }

    private void appendInteractiveSample(FrameJob job, @Nullable UiOverlayManifest.Layer beforeLayer,
                                         @Nullable UiOverlayManifest.Layer afterLayer) {
        String signature = layerSignature(job, beforeLayer, afterLayer);
        synchronized (this.manifestLock) {
            if (this.manifestClosed) {
                return;
            }

            if (signature.equals(this.lastLayerSignature)) {
                this.manifest.captureStats.duplicateSamples += 1;
            }
            this.ensureSourceSize(job);
            UiOverlayManifest.Sample sample = this.createSample(job, beforeLayer, afterLayer);
            this.manifest.samples.add(sample);
            this.hasSamples = true;
            this.lastLayerSignature = signature;
            this.manifest.captureStats.appendedSamples += 1;
        }
    }

    private void appendClearSample(FrameJob job) {
        String signature = layerSignature(job, null, null);
        synchronized (this.manifestLock) {
            if (this.manifestClosed || !this.hasSamples) {
                this.manifest.captureStats.duplicateSamples += 1;
                return;
            }
            if (signature.equals(this.lastLayerSignature)) {
                this.manifest.captureStats.duplicateSamples += 1;
                return;
            }

            this.ensureSourceSize(job);
            UiOverlayManifest.Sample sample = this.createSample(job, null, null);
            this.manifest.samples.add(sample);
            this.hasSamples = true;
            this.lastLayerSignature = signature;
            this.manifest.captureStats.appendedSamples += 1;
            this.manifest.captureStats.clearSamples += 1;
        }
    }

    private void appendStaticSample(FrameJob job, @Nullable UiOverlayManifest.Layer beforeLayer,
                                    @Nullable UiOverlayManifest.Layer afterLayer) {
        String signature = layerSignature(job, beforeLayer, afterLayer);
        boolean clearSample = beforeLayer == null && afterLayer == null && "none".equals(job.cursorType);

        synchronized (this.manifestLock) {
            if (this.manifestClosed) {
                return;
            }
            if (clearSample && !this.hasSamples) {
                this.manifest.captureStats.duplicateSamples += 1;
                return;
            }
            if (!clearSample && signature.equals(this.lastLayerSignature)) {
                this.manifest.captureStats.duplicateSamples += 1;
                return;
            }

            this.ensureSourceSize(job);
            UiOverlayManifest.Sample sample = this.createSample(job, beforeLayer, afterLayer);
            this.manifest.samples.add(sample);
            this.hasSamples = true;
            this.lastLayerSignature = signature;
            this.manifest.captureStats.appendedSamples += 1;
        }
    }

    private UiOverlayManifest.Sample createSample(FrameJob job, @Nullable UiOverlayManifest.Layer beforeLayer,
                                                  @Nullable UiOverlayManifest.Layer afterLayer) {
        UiOverlayManifest.Sample sample = new UiOverlayManifest.Sample();
        sample.tick = job.tick;
        sample.partialTick = job.partialTick;
        sample.sampleTime = job.sampleTime;
        sample.sourceWidth = job.width;
        sample.sourceHeight = job.height;
        sample.guiScale = job.guiScale;
        sample.mouseX = job.mouseX;
        sample.mouseY = job.mouseY;
        sample.cursorType = job.cursorType;
        sample.interactive = job.interactive;
        sample.blur = afterLayer != null;
        sample.beforeBlur = copyLayer(beforeLayer);
        sample.afterBlur = copyLayer(afterLayer);
        return sample;
    }

    private void ensureSourceSize(FrameJob job) {
        if (this.manifest.sourceWidth == 0) {
            this.manifest.sourceWidth = job.width;
            this.manifest.sourceHeight = job.height;
            this.manifest.guiScale = job.guiScale;
        }
    }

    private @Nullable UiOverlayManifest.Layer processLayer(@Nullable NativeImage image) throws IOException {
        if (image == null) {
            return null;
        }

        try {
            Bounds bounds = findNonTransparentBoundsInFlippedSpace(image);
            if (bounds == null) {
                return null;
            }
            if (isLikelyOuterEdgeArtifact(image, bounds)) {
                this.recordBackgroundTileDropped();
                return null;
            }

            if (shouldTile(bounds)) {
                UiOverlayManifest.Layer tiledLayer = this.processTiledLayer(image, bounds);
                if (tiledLayer != null) {
                    return tiledLayer;
                }
            }

            return this.encodeLayer(image, bounds);
        } finally {
            image.close();
        }
    }

    private @Nullable UiOverlayManifest.Layer processTiledLayer(NativeImage image, Bounds bounds) throws IOException {
        UiOverlayManifest.Layer parent = new UiOverlayManifest.Layer();
        parent.x = bounds.x();
        parent.y = bounds.y();
        parent.width = bounds.width();
        parent.height = bounds.height();

        int startTileX = bounds.x() / TILE_SIZE * TILE_SIZE;
        int startTileY = bounds.y() / TILE_SIZE * TILE_SIZE;
        int endX = bounds.x() + bounds.width();
        int endY = bounds.y() + bounds.height();

        for (int tileY = startTileY; tileY < endY; tileY += TILE_SIZE) {
            for (int tileX = startTileX; tileX < endX; tileX += TILE_SIZE) {
                int x1 = Math.max(bounds.x(), tileX);
                int y1 = Math.max(bounds.y(), tileY);
                int x2 = Math.min(endX, tileX + TILE_SIZE);
                int y2 = Math.min(endY, tileY + TILE_SIZE);
                Bounds tileBounds = findNonTransparentBoundsInFlippedSpace(image, new Bounds(x1, y1, x2 - x1, y2 - y1));
                if (tileBounds == null) {
                    continue;
                }
                if (isLikelyOuterEdgeArtifact(image, tileBounds)) {
                    this.recordBackgroundTileDropped();
                    continue;
                }

                UiOverlayManifest.Layer tile = this.encodeLayer(image, tileBounds);
                if (tile != null) {
                    parent.tiles.add(tile);
                }
            }
        }

        if (parent.tiles.isEmpty()) {
            return null;
        }
        if (parent.tiles.size() == 1) {
            return parent.tiles.getFirst();
        }
        return parent;
    }

    private void recordBackgroundTileDropped() {
        synchronized (this.manifestLock) {
            this.manifest.captureStats.backgroundTilesDropped += 1;
        }
    }

    private @Nullable UiOverlayManifest.Layer encodeLayer(NativeImage image, Bounds bounds) throws IOException {
        MessageDigest digest = newDigest();
        updateInt(digest, bounds.width());
        updateInt(digest, bounds.height());

        // Feed each row in one call instead of making four digest calls per pixel.
        // The byte order remains identical to updateInt so existing hashes match.
        byte[] row = new byte[bounds.width() * Integer.BYTES];
        for (int y = 0; y < bounds.height(); y++) {
            int sourceY = image.getHeight() - 1 - (bounds.y() + y);
            for (int x = 0; x < bounds.width(); x++) {
                int pixel = filteredPixel(image, bounds.x() + x, sourceY);
                int offset = x * Integer.BYTES;
                row[offset] = (byte) (pixel >>> 24);
                row[offset + 1] = (byte) (pixel >>> 16);
                row[offset + 2] = (byte) (pixel >>> 8);
                row[offset + 3] = (byte) pixel;
            }
            digest.update(row);
        }

        String hash = toHex(digest.digest());
        String fileName = hash + ".qoi";
        Path framesDir = this.recordFolder.resolve(UiOverlayManifest.FRAMES_DIRECTORY);
        Path filePath = framesDir.resolve(fileName);
        if (this.writtenFrameHashes.add(hash) && !Files.exists(filePath)) {
            Files.createDirectories(framesDir);
            QoiCodec.write(filePath, bounds.width(), bounds.height(), (x, y) -> {
                int sourceY = image.getHeight() - 1 - (bounds.y() + y);
                return filteredPixel(image, bounds.x() + x, sourceY);
            });
            synchronized (this.manifestLock) {
                this.manifest.captureStats.qoiAssets += 1;
            }
        }
        synchronized (this.manifestLock) {
            this.manifest.captureStats.dirtyTiles += 1;
        }

        UiOverlayManifest.Layer layer = new UiOverlayManifest.Layer();
        layer.path = UiOverlayManifest.FRAMES_DIRECTORY + "/" + fileName;
        layer.x = bounds.x();
        layer.y = bounds.y();
        layer.width = bounds.width();
        layer.height = bounds.height();
        return layer;
    }

    private static @Nullable Bounds findNonTransparentBoundsInFlippedSpace(NativeImage image) {
        return findNonTransparentBoundsInFlippedSpace(image, new Bounds(0, 0, image.getWidth(), image.getHeight()));
    }

    private static @Nullable Bounds findNonTransparentBoundsInFlippedSpace(NativeImage image, Bounds searchBounds) {
        int minX = image.getWidth();
        int minY = image.getHeight();
        int maxX = -1;
        int maxY = -1;

        int endX = searchBounds.x() + searchBounds.width();
        int endY = searchBounds.y() + searchBounds.height();
        for (int flippedY = searchBounds.y(); flippedY < endY; flippedY++) {
            int y = image.getHeight() - 1 - flippedY;
            if (y < 0 || y >= image.getHeight()) {
                continue;
            }
            for (int x = searchBounds.x(); x < endX; x++) {
                int pixel = getPixelArgb(image, x, y);
                if (!isRecordablePixel(pixel)) {
                    continue;
                }

                minX = Math.min(minX, x);
                minY = Math.min(minY, flippedY);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, flippedY);
            }
        }

        if (maxX < minX || maxY < minY) {
            return null;
        }

        return new Bounds(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    private static boolean shouldTile(Bounds bounds) {
        return bounds.width() > TILED_LAYER_MIN_SIZE || bounds.height() > TILED_LAYER_MIN_SIZE ||
            (long) bounds.width() * bounds.height() > (long) TILED_LAYER_MIN_SIZE * TILED_LAYER_MIN_SIZE;
    }

    private static boolean isLikelyOuterEdgeArtifact(NativeImage image, Bounds bounds) {
        long area = (long) bounds.width() * bounds.height();
        if (area > OUTER_EDGE_ARTIFACT_MAX_AREA) {
            return false;
        }
        if (bounds.width() > OUTER_EDGE_ARTIFACT_MAX_THICKNESS && bounds.height() > OUTER_EDGE_ARTIFACT_MAX_THICKNESS) {
            return false;
        }

        boolean touchesHorizontalEdge = bounds.x() <= 0 || bounds.x() + bounds.width() >= image.getWidth();
        boolean touchesVerticalEdge = bounds.y() <= 0 || bounds.y() + bounds.height() >= image.getHeight();
        return touchesHorizontalEdge || touchesVerticalEdge;
    }

    private void markEncodeDrop(FrameJob job) {
        synchronized (this.manifestLock) {
            this.droppedEncodeFrames += 1;
            this.manifest.captureStats.skippedEncodeJobs += 1;
            if (job.interactive) {
                this.manifest.captureStats.skippedInteractiveEncodeJobs += 1;
                this.manifest.captureStats.preventedStaleSamples += 1;
            } else {
                this.manifest.captureStats.skippedStaticEncodeJobs += 1;
            }
        }
        this.warnDroppedEncodeFrames();
        this.markSkippedSequence(job);
    }

    private void markSkippedSequence(FrameJob job) {
        synchronized (this.resultLock) {
            this.pendingResults.put(job.sequence, FrameResult.skipped(job));
            this.appendReadyResults();
            this.resultLock.notifyAll();
        }
    }

    private void sortAndRecomputeStats() {
        this.manifest.coalesceDuplicateTimes();
        this.manifest.samples.sort(Comparator.comparingDouble(UiOverlayManifest.Sample::time));
        this.manifest.cursorSamples.sort(Comparator.comparingDouble(UiOverlayManifest.CursorSample::time));

        double maxSampleGap = 0.0;
        double maxInteractiveGap = 0.0;
        double lastSampleTime = Double.NaN;
        double lastInteractiveTime = Double.NaN;
        for (UiOverlayManifest.Sample sample : this.manifest.samples) {
            double time = sample.time();
            if (!Double.isNaN(lastSampleTime)) {
                maxSampleGap = Math.max(maxSampleGap, Math.max(0.0, time - lastSampleTime));
            }
            lastSampleTime = time;

            if (sample.interactive) {
                if (!Double.isNaN(lastInteractiveTime)) {
                    maxInteractiveGap = Math.max(maxInteractiveGap, Math.max(0.0, time - lastInteractiveTime));
                }
                lastInteractiveTime = time;
            } else {
                lastInteractiveTime = Double.NaN;
            }
        }

        double maxCursorGap = 0.0;
        double lastVisibleCursorTime = Double.NaN;
        for (UiOverlayManifest.CursorSample sample : this.manifest.cursorSamples) {
            if ("none".equals(sample.cursorType)) {
                lastVisibleCursorTime = Double.NaN;
                continue;
            }

            double time = sample.time();
            if (!Double.isNaN(lastVisibleCursorTime)) {
                maxCursorGap = Math.max(maxCursorGap, Math.max(0.0, time - lastVisibleCursorTime));
            }
            lastVisibleCursorTime = time;
        }

        this.manifest.captureStats.maxSampleGapTicks = maxSampleGap;
        this.manifest.captureStats.maxInteractiveSampleGapTicks = maxInteractiveGap;
        this.manifest.captureStats.maxCursorSampleGapTicks = maxCursorGap;
        this.manifest.captureStats.serializedSamples = this.manifest.samples.size();
        this.manifest.captureStats.serializedCursorSamples = this.manifest.cursorSamples.size();
    }

    private static String layerSignature(FrameJob job, @Nullable UiOverlayManifest.Layer beforeLayer,
                                         @Nullable UiOverlayManifest.Layer afterLayer) {
        StringBuilder builder = new StringBuilder();
        builder.append(job.width).append('x').append(job.height).append('@').append(job.guiScale).append('|');
        appendLayerSignature(builder, beforeLayer);
        builder.append('|');
        appendLayerSignature(builder, afterLayer);
        return builder.toString();
    }

    private static String cursorSignature(FrameJob job) {
        if ("none".equals(job.cursorType)) {
            return "none";
        }

        return job.cursorType + '|' + Double.doubleToLongBits(job.mouseX) + '|' + Double.doubleToLongBits(job.mouseY);
    }

    private static void appendLayerSignature(StringBuilder builder, @Nullable UiOverlayManifest.Layer layer) {
        if (layer == null) {
            builder.append('-');
            return;
        }

        if (!layer.tiles.isEmpty()) {
            builder.append('[');
            for (UiOverlayManifest.Layer tile : layer.tiles) {
                appendLayerSignature(builder, tile);
                builder.append(';');
            }
            builder.append(']');
            return;
        }

        builder.append(layer.path).append(':')
            .append(layer.x).append(',')
            .append(layer.y).append(',')
            .append(layer.width).append(',')
            .append(layer.height);
    }

    private static @Nullable UiOverlayManifest.Layer copyLayer(@Nullable UiOverlayManifest.Layer layer) {
        return layer == null ? null : layer.copy();
    }

    private static int getPixelArgb(NativeImage image, int x, int y) {
        int abgr = MemoryUtil.memGetInt(image.pixels + 4L * ((long) y * image.getWidth() + x));
        return abgrToArgb(abgr);
    }

    private static int filteredPixel(NativeImage image, int x, int y) {
        int pixel = getPixelArgb(image, x, y);
        return isRecordablePixel(pixel) ? pixel : 0;
    }

    private static int abgrToArgb(int abgr) {
        return (abgr & 0xFF00FF00) | ((abgr & 0x00FF0000) >>> 16) | ((abgr & 0x000000FF) << 16);
    }

    private static boolean isRecordablePixel(int pixel) {
        int alpha = pixel >>> 24;
        if (alpha == 0) {
            return false;
        }

        return alpha >= DIM_BACKGROUND_ALPHA_CUTOFF || !isNearBlack(pixel);
    }

    private static boolean isNearBlack(int pixel) {
        return (pixel & 0xFF) <= NEAR_BLACK_CUTOFF &&
            ((pixel >>> 8) & 0xFF) <= NEAR_BLACK_CUTOFF &&
            ((pixel >>> 16) & 0xFF) <= NEAR_BLACK_CUTOFF;
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void updateInt(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            builder.append(Character.forDigit((b >>> 4) & 0xF, 16));
            builder.append(Character.forDigit(b & 0xF, 16));
        }
        return builder.toString();
    }

    private void warnDroppedEncodeFrames() {
        long now = System.nanoTime();
        if (this.lastEncodeWarningNanos != Long.MIN_VALUE && now - this.lastEncodeWarningNanos < ENCODE_WARNING_INTERVAL_NANOS) {
            return;
        }

        synchronized (this.manifestLock) {
            Flashback.LOGGER.warn("Skipped {} UI overlay frames because the encoder queue is overloaded", this.droppedEncodeFrames);
            this.droppedEncodeFrames = 0;
        }
        this.lastEncodeWarningNanos = now;
    }

    private void logSummary() {
        UiOverlayManifest.CaptureStats stats = this.manifest.captureStats;
        Flashback.LOGGER.info(
            "UI overlay capture summary: renderFrames={}, decisions={}, screenCaptures={} at max {}x{}, hudCapturesSkipped={}, layerDraws(before={}, after={}), readbackStarts={}, readbackCompletions={}, skippedReadbacks={}, skippedEncodeJobs={} (interactive={}, static={}), preventedStaleSamples={}, samples={}, cursorSamples={}, duplicateTimes={} / {}, clearSamples={}, qoiAssets={}, dirtyTiles={}, backgroundTilesDropped={}, maxSampleGap={} ticks, maxInteractiveSampleGap={} ticks, maxCursorSampleGap={} ticks, maxEncodeQueue={} jobs/{} MiB, maxEncodeLatency={} ms",
            stats.renderFrames, stats.captureDecisions, stats.screenCaptures, stats.maxCaptureWidth, stats.maxCaptureHeight,
            stats.hudCapturesSkipped, stats.beforeBlurLayerDraws, stats.afterBlurLayerDraws,
            stats.readbackStarts, stats.readbackCompletions, stats.skippedReadbacks,
            stats.skippedEncodeJobs, stats.skippedInteractiveEncodeJobs, stats.skippedStaticEncodeJobs,
            stats.preventedStaleSamples, this.manifest.samples.size(), this.manifest.cursorSamples.size(),
            stats.duplicateSampleTimes, stats.duplicateCursorSampleTimes, stats.clearSamples, stats.qoiAssets,
            stats.dirtyTiles, stats.backgroundTilesDropped, stats.maxSampleGapTicks, stats.maxInteractiveSampleGapTicks,
            stats.maxCursorSampleGapTicks, stats.maxEncodeQueueJobs, Math.round(stats.maxEncodeQueueBytes / 1024.0 / 1024.0),
            stats.maxEncodeLatencyMillis
        );
    }

    private static void closeJob(FrameJob job) {
        closeImage(job.beforeBlur);
        closeImage(job.afterBlur);
    }

    private static void closeImage(@Nullable NativeImage image) {
        if (image != null && !image.isClosed()) {
            image.close();
        }
    }

    private static class FrameJob {
        private final long sequence;
        private final int tick;
        private final float partialTick;
        private final double sampleTime;
        private final int width;
        private final int height;
        private final int guiScale;
        private final @Nullable NativeImage beforeBlur;
        private final @Nullable NativeImage afterBlur;
        private final double mouseX;
        private final double mouseY;
        private final String cursorType;
        private final boolean interactive;
        private final boolean clearFrame;
        private final long imageBytes;

        private FrameJob(long sequence, int tick, float partialTick, double sampleTime, int width, int height, int guiScale,
                         @Nullable NativeImage beforeBlur, @Nullable NativeImage afterBlur,
                         double mouseX, double mouseY, String cursorType, boolean interactive) {
            this.sequence = sequence;
            this.tick = tick;
            this.partialTick = partialTick;
            this.sampleTime = sampleTime;
            this.width = width;
            this.height = height;
            this.guiScale = guiScale;
            this.beforeBlur = beforeBlur;
            this.afterBlur = afterBlur;
            this.mouseX = mouseX;
            this.mouseY = mouseY;
            this.cursorType = cursorType;
            this.interactive = interactive;
            this.clearFrame = false;
            long imageBytes = 0;
            if (beforeBlur != null) {
                imageBytes += beforeBlur.size;
            }
            if (afterBlur != null) {
                imageBytes += afterBlur.size;
            }
            this.imageBytes = imageBytes;
        }

        private FrameJob(long sequence, int tick, float partialTick, double sampleTime, int width, int height, int guiScale) {
            this.sequence = sequence;
            this.tick = tick;
            this.partialTick = partialTick;
            this.sampleTime = sampleTime;
            this.width = width;
            this.height = height;
            this.guiScale = guiScale;
            this.beforeBlur = null;
            this.afterBlur = null;
            this.mouseX = 0.0;
            this.mouseY = 0.0;
            this.cursorType = "none";
            this.interactive = false;
            this.clearFrame = true;
            this.imageBytes = 0;
        }

        private static FrameJob clear(long sequence, int tick, float partialTick, double sampleTime, int width, int height, int guiScale) {
            return new FrameJob(sequence, tick, partialTick, sampleTime, width, height, guiScale);
        }
    }

    private record FrameResult(long sequence, @Nullable FrameJob job, @Nullable UiOverlayManifest.Layer beforeLayer,
                               @Nullable UiOverlayManifest.Layer afterLayer, boolean skipped) {
        private static FrameResult skipped(FrameJob job) {
            return new FrameResult(job.sequence, job, null, null, true);
        }
    }

    private record Bounds(int x, int y, int width, int height) {}
}
