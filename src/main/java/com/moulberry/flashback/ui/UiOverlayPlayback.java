package com.moulberry.flashback.ui;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.FlashbackGson;
import com.moulberry.flashback.playback.ReplayServer;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class UiOverlayPlayback implements AutoCloseable {

    private static final double HALF_60FPS_FRAME_TICKS = 20.0 / 60.0 / 2.0;

    private static final String[] CURSOR_MASK = new String[]{
        "X               ",
        "XX              ",
        "X.X             ",
        "X..X            ",
        "X...X           ",
        "X....X          ",
        "X.....X         ",
        "X......X        ",
        "X.......X       ",
        "X........X      ",
        "X.....XXXXX     ",
        "X..X..X         ",
        "X.X X..X        ",
        "XX  X..X        ",
        "X    X..X       ",
        "     X..X       ",
        "      XX        "
    };

    private final FileSystem fileSystem;
    private final UiOverlayManifest manifest;
    private final Map<String, NativeImage> imageCache = new HashMap<>();

    private UiOverlayPlayback(FileSystem fileSystem, UiOverlayManifest manifest) {
        this.fileSystem = fileSystem;
        this.manifest = manifest;
        this.warnIfSparse();
    }

    public static @Nullable UiOverlayPlayback fromReplay(ReplayServer replayServer) {
        if (replayServer.getMetadata().uiOverlay == null) {
            Flashback.LOGGER.warn("Replay has no recorded UI overlay metadata; recorded inventory/chat/container UI cannot be shown for this recording. Most likely recordUiOverlay was disabled when this replay was recorded, or no inventory/chat/container UI was opened.");
            return null;
        }

        try {
            FileSystem fileSystem = replayServer.getPlaybackFileSystem();
            Path manifestPath = fileSystem.getPath("/" + replayServer.getMetadata().uiOverlay.manifest);
            if (!Files.exists(manifestPath)) {
                Flashback.LOGGER.warn("Replay metadata references UI overlay, but {} is missing", manifestPath);
                return null;
            }

            JsonObject json = FlashbackGson.COMPRESSED.fromJson(Files.readString(manifestPath), JsonObject.class);
            UiOverlayManifest manifest = UiOverlayManifest.fromJson(json);
            if (manifest == null || (manifest.samples.isEmpty() && manifest.cursorSamples.isEmpty())) {
                Flashback.LOGGER.warn("Replay UI overlay manifest is invalid or empty");
                return null;
            }

            return new UiOverlayPlayback(fileSystem, manifest);
        } catch (Exception e) {
            Flashback.LOGGER.warn("Unable to load replay UI overlay", e);
            return null;
        }
    }

    public void composite(NativeImage target, double replayTick) {
        this.compositeInto(target, replayTick, true);
    }

    public void compositeOverlay(NativeImage target, double replayTick) {
        this.compositeInto(target, replayTick, false);
    }

    public boolean hasVisibleRecordedScreenOverlay(double replayTick) {
        UiOverlayManifest.Sample sample = this.sampleAt(replayTick);
        return sample != null && sample.interactive &&
            (hasLayer(sample.beforeBlur) || hasLayer(sample.afterBlur) || sample.blur);
    }

    private void compositeInto(NativeImage target, double replayTick, boolean blurTarget) {
        UiOverlayManifest.Sample sample = this.sampleAt(replayTick);
        if (sample == null) {
            this.drawCursor(target, replayTick, null);
            return;
        }

        this.compositeLayer(target, sample, sample.beforeBlur);
        if (blurTarget && sample.blur) {
            applyRecordedBlurStep(target);
        }
        this.compositeLayer(target, sample, sample.afterBlur);
        this.drawCursor(target, replayTick, sample);
    }

    private @Nullable UiOverlayManifest.Sample sampleAt(double replayTick) {
        int low = 0;
        int high = this.manifest.samples.size() - 1;
        int result = -1;

        while (low <= high) {
            int mid = (low + high) >>> 1;
            UiOverlayManifest.Sample sample = this.manifest.samples.get(mid);
            if (sample.time() <= replayTick + 0.0001) {
                result = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }

        UiOverlayManifest.Sample previous = result >= 0 ? this.manifest.samples.get(result) : null;
        UiOverlayManifest.Sample next = result + 1 < this.manifest.samples.size() ? this.manifest.samples.get(result + 1) : null;
        if (next != null && next.time() >= replayTick - 0.0001 && next.time() - replayTick <= HALF_60FPS_FRAME_TICKS &&
            (previous == null || next.time() - replayTick < replayTick - previous.time())) {
            return next;
        }
        return previous;
    }

    private @Nullable UiOverlayManifest.CursorSample cursorSampleAt(double replayTick) {
        if (this.manifest.cursorSamples.isEmpty()) {
            return null;
        }

        int low = 0;
        int high = this.manifest.cursorSamples.size() - 1;
        int result = -1;

        while (low <= high) {
            int mid = (low + high) >>> 1;
            UiOverlayManifest.CursorSample sample = this.manifest.cursorSamples.get(mid);
            if (sample.time() <= replayTick + 0.0001) {
                result = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }

        return result >= 0 ? this.manifest.cursorSamples.get(result) : null;
    }

    private void compositeLayer(NativeImage target, UiOverlayManifest.Sample sample, @Nullable UiOverlayManifest.Layer layer) {
        if (layer != null && !layer.tiles.isEmpty()) {
            for (UiOverlayManifest.Layer tile : layer.tiles) {
                this.compositeLayer(target, sample, tile);
            }
            return;
        }

        if (layer == null || layer.width <= 0 || layer.height <= 0) {
            return;
        }

        NativeImage source = this.loadLayer(layer);
        if (source == null) {
            return;
        }

        int sourceWidth = sample.sourceWidth > 0 ? sample.sourceWidth : this.manifest.sourceWidth;
        int sourceHeight = sample.sourceHeight > 0 ? sample.sourceHeight : this.manifest.sourceHeight;
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            return;
        }

        int destX1 = (int) Math.floor((double) layer.x * target.getWidth() / sourceWidth);
        int destY1 = (int) Math.floor((double) layer.y * target.getHeight() / sourceHeight);
        int destX2 = (int) Math.ceil((double) (layer.x + layer.width) * target.getWidth() / sourceWidth);
        int destY2 = (int) Math.ceil((double) (layer.y + layer.height) * target.getHeight() / sourceHeight);

        destX1 = clamp(destX1, 0, target.getWidth());
        destY1 = clamp(destY1, 0, target.getHeight());
        destX2 = clamp(destX2, 0, target.getWidth());
        destY2 = clamp(destY2, 0, target.getHeight());

        int destWidth = destX2 - destX1;
        int destHeight = destY2 - destY1;
        if (destWidth <= 0 || destHeight <= 0) {
            return;
        }

        for (int y = destY1; y < destY2; y++) {
            int sourceY = (int) ((long) (y - destY1) * source.getHeight() / destHeight);
            sourceY = clamp(sourceY, 0, source.getHeight() - 1);
            for (int x = destX1; x < destX2; x++) {
                int sourceX = (int) ((long) (x - destX1) * source.getWidth() / destWidth);
                sourceX = clamp(sourceX, 0, source.getWidth() - 1);

                int sourcePixel = source.getPixel(sourceX, sourceY);
                if ((sourcePixel >>> 24) == 0) {
                    continue;
                }

                target.setPixel(x, y, blend(target.getPixel(x, y), sourcePixel));
            }
        }
    }

    private static boolean hasLayer(@Nullable UiOverlayManifest.Layer layer) {
        if (layer == null) {
            return false;
        }
        if (layer.path != null && layer.width > 0 && layer.height > 0) {
            return true;
        }
        for (UiOverlayManifest.Layer tile : layer.tiles) {
            if (hasLayer(tile)) {
                return true;
            }
        }
        return false;
    }

    private @Nullable NativeImage loadLayer(UiOverlayManifest.Layer layer) {
        if (layer.path == null) {
            return null;
        }

        NativeImage cached = this.imageCache.get(layer.path);
        if (cached != null) {
            return cached;
        }

        Path path = this.fileSystem.getPath("/" + layer.path);
        if (!Files.exists(path)) {
            Flashback.LOGGER.warn("Missing UI overlay frame {}", path);
            return null;
        }

        try (InputStream inputStream = Files.newInputStream(path)) {
            NativeImage image = layer.path.endsWith(".qoi") ? QoiCodec.read(inputStream) : NativeImage.read(inputStream);
            this.imageCache.put(layer.path, image);
            return image;
        } catch (IOException e) {
            Flashback.LOGGER.warn("Unable to read UI overlay frame {}", path, e);
            return null;
        }
    }

    private void drawCursor(NativeImage target, double replayTick, @Nullable UiOverlayManifest.Sample layerSample) {
        CursorState cursorState = this.cursorStateAt(replayTick, layerSample);
        if (cursorState == null || "none".equals(cursorState.cursorType)) {
            return;
        }

        int sourceWidth = cursorState.sourceWidth > 0 ? cursorState.sourceWidth : this.manifest.sourceWidth;
        int sourceHeight = cursorState.sourceHeight > 0 ? cursorState.sourceHeight : this.manifest.sourceHeight;
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            return;
        }

        int cursorX = (int) Math.round(cursorState.mouseX * target.getWidth() / sourceWidth);
        int cursorY = (int) Math.round(cursorState.mouseY * target.getHeight() / sourceHeight);
        double scale = Math.max(1.0, (double) target.getWidth() / sourceWidth);
        int pixelSize = Math.max(1, (int) Math.round(scale));

        for (int y = 0; y < CURSOR_MASK.length; y++) {
            String row = CURSOR_MASK[y];
            for (int x = 0; x < row.length(); x++) {
                char c = row.charAt(x);
                if (c == ' ') {
                    continue;
                }

                int color = c == 'X' ? 0xFF000000 : 0xFFFFFFFF;
                fillCursorPixel(target, cursorX + x * pixelSize, cursorY + y * pixelSize, pixelSize, color);
            }
        }
    }

    private @Nullable CursorState cursorStateAt(double replayTick, @Nullable UiOverlayManifest.Sample layerSample) {
        if (!this.manifest.cursorSamples.isEmpty()) {
            CursorState interpolated = this.interpolatedCursorStateAt(replayTick);
            if (interpolated != null) {
                return interpolated;
            }
        }

        if (layerSample == null) {
            return null;
        }

        return this.legacyCursorStateAt(replayTick, layerSample);
    }

    private @Nullable CursorState interpolatedCursorStateAt(double replayTick) {
        int low = 0;
        int high = this.manifest.cursorSamples.size() - 1;
        int result = -1;

        while (low <= high) {
            int mid = (low + high) >>> 1;
            UiOverlayManifest.CursorSample sample = this.manifest.cursorSamples.get(mid);
            if (sample.time() <= replayTick + 0.0001) {
                result = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }

        UiOverlayManifest.CursorSample previous = result >= 0 ? this.manifest.cursorSamples.get(result) : null;
        UiOverlayManifest.CursorSample next = result + 1 < this.manifest.cursorSamples.size() ? this.manifest.cursorSamples.get(result + 1) : null;
        if (previous == null) {
            if (next != null && next.time() - replayTick <= HALF_60FPS_FRAME_TICKS) {
                return new CursorState(next.cursorType, next.mouseX, next.mouseY, next.sourceWidth, next.sourceHeight);
            }
            return null;
        }
        if (next == null || previous.time() >= replayTick || next.time() <= previous.time() ||
            !previous.cursorType.equals(next.cursorType) || "none".equals(previous.cursorType) || "none".equals(next.cursorType)) {
            return new CursorState(previous.cursorType, previous.mouseX, previous.mouseY, previous.sourceWidth, previous.sourceHeight);
        }

        double amount = (replayTick - previous.time()) / (next.time() - previous.time());
        amount = Math.max(0.0, Math.min(1.0, amount));
        double mouseX = previous.mouseX + (next.mouseX - previous.mouseX) * amount;
        double mouseY = previous.mouseY + (next.mouseY - previous.mouseY) * amount;
        int sourceWidth = previous.sourceWidth > 0 ? previous.sourceWidth : next.sourceWidth;
        int sourceHeight = previous.sourceHeight > 0 ? previous.sourceHeight : next.sourceHeight;
        return new CursorState(previous.cursorType, mouseX, mouseY, sourceWidth, sourceHeight);
    }

    private @Nullable CursorState legacyCursorStateAt(double replayTick, UiOverlayManifest.Sample layerSample) {
        if ("none".equals(layerSample.cursorType)) {
            return null;
        }

        UiOverlayManifest.Sample next = this.nextSampleAfter(replayTick);
        if (next == null || "none".equals(next.cursorType) || !layerSample.cursorType.equals(next.cursorType) || next.time() <= layerSample.time()) {
            return new CursorState(layerSample.cursorType, layerSample.mouseX, layerSample.mouseY,
                layerSample.sourceWidth, layerSample.sourceHeight);
        }

        double amount = (replayTick - layerSample.time()) / (next.time() - layerSample.time());
        amount = Math.max(0.0, Math.min(1.0, amount));
        double mouseX = layerSample.mouseX + (next.mouseX - layerSample.mouseX) * amount;
        double mouseY = layerSample.mouseY + (next.mouseY - layerSample.mouseY) * amount;
        int sourceWidth = layerSample.sourceWidth > 0 ? layerSample.sourceWidth : next.sourceWidth;
        int sourceHeight = layerSample.sourceHeight > 0 ? layerSample.sourceHeight : next.sourceHeight;
        return new CursorState(layerSample.cursorType, mouseX, mouseY, sourceWidth, sourceHeight);
    }

    private @Nullable UiOverlayManifest.Sample nextSampleAfter(double replayTick) {
        int low = 0;
        int high = this.manifest.samples.size() - 1;
        int result = -1;

        while (low <= high) {
            int mid = (low + high) >>> 1;
            UiOverlayManifest.Sample sample = this.manifest.samples.get(mid);
            if (sample.time() > replayTick + 0.0001) {
                result = mid;
                high = mid - 1;
            } else {
                low = mid + 1;
            }
        }

        return result >= 0 ? this.manifest.samples.get(result) : null;
    }

    private void warnIfSparse() {
        if (this.manifest.captureStats.skippedInteractiveEncodeJobs > 0) {
            Flashback.LOGGER.warn("Replay UI overlay dropped {} interactive encode jobs while recording. Missing UI frames cannot be reconstructed exactly.",
                this.manifest.captureStats.skippedInteractiveEncodeJobs);
        }
        if (this.manifest.captureStats.duplicateSampleTimes > 0 || this.manifest.captureStats.duplicateCursorSampleTimes > 0) {
            Flashback.LOGGER.warn("Replay UI overlay had duplicate timeline entries: {} layer samples and {} cursor samples were coalesced.",
                this.manifest.captureStats.duplicateSampleTimes, this.manifest.captureStats.duplicateCursorSampleTimes);
        }
        double maxSampleGap = this.manifest.captureStats.maxInteractiveSampleGapTicks > 0.0
            ? Math.round(this.manifest.captureStats.maxInteractiveSampleGapTicks / 20.0 * 1000.0) / 1000.0
            : maxGapSecondsForSamples();
        double maxCursorGap = this.manifest.captureStats.maxCursorSampleGapTicks > 0.0
            ? Math.round(this.manifest.captureStats.maxCursorSampleGapTicks / 20.0 * 1000.0) / 1000.0
            : maxGapSecondsForCursorSamples();
        if (maxSampleGap > 0.05) {
            Flashback.LOGGER.warn("Replay UI overlay layer samples are sparse: {} samples, max interactive gap {}s. UI pixels may appear low-framerate in exports.",
                this.manifest.samples.size(), maxSampleGap);
        }
        if (!this.manifest.cursorSamples.isEmpty() && maxCursorGap > 0.05) {
            Flashback.LOGGER.warn("Replay UI overlay cursor samples are sparse: {} samples, max visible-cursor gap {}s. Cursor motion may appear low-framerate in exports.",
                this.manifest.cursorSamples.size(), maxCursorGap);
        }
        if (this.manifest.cursorSamples.isEmpty() && this.manifest.samples.size() > 1) {
            Flashback.LOGGER.warn("Replay UI overlay has no dense cursor_samples track; using best-effort legacy cursor interpolation.");
        }
    }

    private double maxGapSecondsForSamples() {
        double maxGapTicks = 0.0;
        for (int i = 1; i < this.manifest.samples.size(); i++) {
            maxGapTicks = Math.max(maxGapTicks, this.manifest.samples.get(i).time() - this.manifest.samples.get(i - 1).time());
        }
        return Math.round(maxGapTicks / 20.0 * 1000.0) / 1000.0;
    }

    private double maxGapSecondsForCursorSamples() {
        double maxGapTicks = 0.0;
        for (int i = 1; i < this.manifest.cursorSamples.size(); i++) {
            maxGapTicks = Math.max(maxGapTicks, this.manifest.cursorSamples.get(i).time() - this.manifest.cursorSamples.get(i - 1).time());
        }
        return Math.round(maxGapTicks / 20.0 * 1000.0) / 1000.0;
    }

    private static void fillCursorPixel(NativeImage target, int x, int y, int size, int color) {
        for (int yy = 0; yy < size; yy++) {
            int targetY = y + yy;
            if (targetY < 0 || targetY >= target.getHeight()) {
                continue;
            }
            for (int xx = 0; xx < size; xx++) {
                int targetX = x + xx;
                if (targetX < 0 || targetX >= target.getWidth()) {
                    continue;
                }
                target.setPixel(targetX, targetY, blend(target.getPixel(targetX, targetY), color));
            }
        }
    }

    private static void applyRecordedBlurStep(NativeImage target) {
        int width = target.getWidth();
        int height = target.getHeight();
        int[] source = target.getPixels();
        int[] horizontal = new int[source.length];
        int radius = 2;

        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                int count = 0;
                int a = 0;
                int r = 0;
                int g = 0;
                int b = 0;

                for (int xx = Math.max(0, x - radius); xx <= Math.min(width - 1, x + radius); xx++) {
                    int pixel = source[row + xx];
                    a += (pixel >>> 24) & 0xFF;
                    r += (pixel >>> 16) & 0xFF;
                    g += (pixel >>> 8) & 0xFF;
                    b += pixel & 0xFF;
                    count += 1;
                }

                horizontal[row + x] = ((a / count) << 24) | ((r / count) << 16) | ((g / count) << 8) | (b / count);
            }
        }

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int count = 0;
                int a = 0;
                int r = 0;
                int g = 0;
                int b = 0;

                for (int yy = Math.max(0, y - radius); yy <= Math.min(height - 1, y + radius); yy++) {
                    int pixel = horizontal[yy * width + x];
                    a += (pixel >>> 24) & 0xFF;
                    r += (pixel >>> 16) & 0xFF;
                    g += (pixel >>> 8) & 0xFF;
                    b += pixel & 0xFF;
                    count += 1;
                }

                target.setPixel(x, y, ((a / count) << 24) | ((r / count) << 16) | ((g / count) << 8) | (b / count));
            }
        }
    }

    private static int blend(int dst, int src) {
        int srcA = (src >>> 24) & 0xFF;
        if (srcA <= 0) {
            return dst;
        }
        if (srcA >= 255) {
            return src;
        }

        int dstA = (dst >>> 24) & 0xFF;
        int outA = srcA + dstA * (255 - srcA) / 255;
        if (outA <= 0) {
            return 0;
        }

        int srcR = (src >>> 16) & 0xFF;
        int srcG = (src >>> 8) & 0xFF;
        int srcB = src & 0xFF;
        int dstR = (dst >>> 16) & 0xFF;
        int dstG = (dst >>> 8) & 0xFF;
        int dstB = dst & 0xFF;

        int dstFactor = dstA * (255 - srcA) / 255;
        int outR = (srcR * srcA + dstR * dstFactor) / outA;
        int outG = (srcG * srcA + dstG * dstFactor) / outA;
        int outB = (srcB * srcA + dstB * dstFactor) / outA;

        return (outA << 24) | (outR << 16) | (outG << 8) | outB;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private record CursorState(String cursorType, double mouseX, double mouseY, int sourceWidth, int sourceHeight) {}

    @Override
    public void close() {
        for (NativeImage image : this.imageCache.values()) {
            image.close();
        }
        this.imageCache.clear();
    }
}
