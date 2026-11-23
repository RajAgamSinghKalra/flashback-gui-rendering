package com.moulberry.flashback.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class UiOverlayManifest {

    public static final int VERSION = 2;
    public static final int MIN_SUPPORTED_VERSION = 1;
    public static final String DIRECTORY = "ui_overlay";
    public static final String FRAMES_DIRECTORY = DIRECTORY + "/frames";
    public static final String MANIFEST_ENTRY = DIRECTORY + "/manifest.json";
    private static final double DUPLICATE_TIME_EPSILON = 0.0001;

    public int version = VERSION;
    public int sourceWidth = 0;
    public int sourceHeight = 0;
    public int guiScale = 0;
    public final List<Sample> samples = new ArrayList<>();
    public final List<CursorSample> cursorSamples = new ArrayList<>();
    public CaptureStats captureStats = new CaptureStats();

    public Metadata toMetadata() {
        Metadata metadata = new Metadata();
        metadata.version = this.version;
        metadata.manifest = MANIFEST_ENTRY;
        metadata.sourceWidth = this.sourceWidth;
        metadata.sourceHeight = this.sourceHeight;
        metadata.guiScale = this.guiScale;
        return metadata;
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("version", this.version);
        json.addProperty("source_width", this.sourceWidth);
        json.addProperty("source_height", this.sourceHeight);
        json.addProperty("gui_scale", this.guiScale);

        JsonArray samplesJson = new JsonArray();
        for (Sample sample : this.samples) {
            samplesJson.add(sample.toJson());
        }
        json.add("samples", samplesJson);

        if (!this.cursorSamples.isEmpty()) {
            JsonArray cursorSamplesJson = new JsonArray();
            for (CursorSample cursorSample : this.cursorSamples) {
                cursorSamplesJson.add(cursorSample.toJson());
            }
            json.add("cursor_samples", cursorSamplesJson);
        }

        if (!this.captureStats.isEmpty()) {
            json.add("capture_stats", this.captureStats.toJson());
        }

        return json;
    }

    public static @Nullable UiOverlayManifest fromJson(JsonObject json) {
        UiOverlayManifest manifest = new UiOverlayManifest();

        if (json.has("version")) {
            manifest.version = json.get("version").getAsInt();
        }
        if (manifest.version < MIN_SUPPORTED_VERSION || manifest.version > VERSION) {
            return null;
        }

        if (json.has("source_width")) {
            manifest.sourceWidth = json.get("source_width").getAsInt();
        }
        if (json.has("source_height")) {
            manifest.sourceHeight = json.get("source_height").getAsInt();
        }
        if (json.has("gui_scale")) {
            manifest.guiScale = json.get("gui_scale").getAsInt();
        }

        if (json.has("samples") && json.get("samples").isJsonArray()) {
            for (JsonElement element : json.getAsJsonArray("samples")) {
                if (!element.isJsonObject()) {
                    continue;
                }

                Sample sample = Sample.fromJson(element.getAsJsonObject());
                if (sample != null) {
                    manifest.samples.add(sample);
                }
            }
        }

        if (json.has("cursor_samples") && json.get("cursor_samples").isJsonArray()) {
            for (JsonElement element : json.getAsJsonArray("cursor_samples")) {
                if (!element.isJsonObject()) {
                    continue;
                }

                CursorSample cursorSample = CursorSample.fromJson(element.getAsJsonObject());
                if (cursorSample != null) {
                    manifest.cursorSamples.add(cursorSample);
                }
            }
        }

        if (json.has("capture_stats") && json.get("capture_stats").isJsonObject()) {
            manifest.captureStats = CaptureStats.fromJson(json.getAsJsonObject("capture_stats"));
        }

        manifest.coalesceDuplicateTimes();
        return manifest;
    }

    public void coalesceDuplicateTimes() {
        this.samples.sort(Comparator.comparingDouble(Sample::time));
        this.cursorSamples.sort(Comparator.comparingDouble(CursorSample::time));

        int duplicateSamples = coalesceSamples(this.samples);
        int duplicateCursorSamples = coalesceCursorSamples(this.cursorSamples);
        this.captureStats.duplicateSampleTimes += duplicateSamples;
        this.captureStats.duplicateCursorSampleTimes += duplicateCursorSamples;
        this.captureStats.serializedSamples = this.samples.size();
        this.captureStats.serializedCursorSamples = this.cursorSamples.size();
    }

    private static int coalesceSamples(List<Sample> samples) {
        if (samples.size() < 2) {
            return 0;
        }

        List<Sample> coalesced = new ArrayList<>(samples.size());
        int duplicates = 0;
        for (Sample sample : samples) {
            int lastIndex = coalesced.size() - 1;
            if (lastIndex >= 0 && sample.time() == coalesced.get(lastIndex).time()) {
                coalesced.set(lastIndex, sample);
                duplicates += 1;
            } else {
                coalesced.add(sample);
            }
        }

        samples.clear();
        samples.addAll(coalesced);
        return duplicates;
    }

    private static int coalesceCursorSamples(List<CursorSample> samples) {
        if (samples.size() < 2) {
            return 0;
        }

        List<CursorSample> coalesced = new ArrayList<>(samples.size());
        int duplicates = 0;
        for (CursorSample sample : samples) {
            int lastIndex = coalesced.size() - 1;
            if (lastIndex >= 0 && sample.time() == coalesced.get(lastIndex).time()) {
                coalesced.set(lastIndex, sample);
                duplicates += 1;
            } else {
                coalesced.add(sample);
            }
        }

        samples.clear();
        samples.addAll(coalesced);
        return duplicates;
    }

    public UiOverlayManifest copyWithTickOffset(int tickOffset) {
        UiOverlayManifest copy = new UiOverlayManifest();
        copy.version = this.version;
        copy.sourceWidth = this.sourceWidth;
        copy.sourceHeight = this.sourceHeight;
        copy.guiScale = this.guiScale;

        for (Sample sample : this.samples) {
            copy.samples.add(sample.copyWithTickOffset(tickOffset));
        }
        for (CursorSample cursorSample : this.cursorSamples) {
            copy.cursorSamples.add(cursorSample.copyWithTickOffset(tickOffset));
        }

        return copy;
    }

    public void append(UiOverlayManifest other) {
        if (this.sourceWidth == 0) {
            this.sourceWidth = other.sourceWidth;
        }
        if (this.sourceHeight == 0) {
            this.sourceHeight = other.sourceHeight;
        }
        if (this.guiScale == 0) {
            this.guiScale = other.guiScale;
        }
        this.samples.addAll(other.samples);
        this.cursorSamples.addAll(other.cursorSamples);
        this.samples.sort(Comparator.comparingDouble(Sample::time));
        this.cursorSamples.sort(Comparator.comparingDouble(CursorSample::time));
    }

    public static class Metadata {
        public int version = VERSION;
        public String manifest = MANIFEST_ENTRY;
        public int sourceWidth = 0;
        public int sourceHeight = 0;
        public int guiScale = 0;

        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("version", this.version);
            json.addProperty("manifest", this.manifest);
            json.addProperty("source_width", this.sourceWidth);
            json.addProperty("source_height", this.sourceHeight);
            json.addProperty("gui_scale", this.guiScale);
            return json;
        }

        public static @Nullable Metadata fromJson(JsonObject json) {
            Metadata metadata = new Metadata();
            if (json.has("version")) {
                metadata.version = json.get("version").getAsInt();
            }
            if (metadata.version < MIN_SUPPORTED_VERSION || metadata.version > VERSION) {
                return null;
            }
            if (json.has("manifest")) {
                metadata.manifest = json.get("manifest").getAsString();
            }
            if (json.has("source_width")) {
                metadata.sourceWidth = json.get("source_width").getAsInt();
            }
            if (json.has("source_height")) {
                metadata.sourceHeight = json.get("source_height").getAsInt();
            }
            if (json.has("gui_scale")) {
                metadata.guiScale = json.get("gui_scale").getAsInt();
            }
            return metadata;
        }
    }

    public static class Sample {
        public int tick;
        public float partialTick;
        public int sourceWidth;
        public int sourceHeight;
        public int guiScale;
        public double sampleTime = Double.NaN;
        public double mouseX;
        public double mouseY;
        public String cursorType = "none";
        public boolean interactive;
        public boolean blur;
        public @Nullable Layer beforeBlur;
        public @Nullable Layer afterBlur;

        public double time() {
            if (!Double.isNaN(this.sampleTime)) {
                return this.sampleTime;
            }
            return this.tick + this.partialTick;
        }

        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("tick", this.tick);
            json.addProperty("partial_tick", this.partialTick);
            if (!Double.isNaN(this.sampleTime)) {
                json.addProperty("sample_time", this.sampleTime);
            }
            json.addProperty("source_width", this.sourceWidth);
            json.addProperty("source_height", this.sourceHeight);
            json.addProperty("gui_scale", this.guiScale);
            json.addProperty("mouse_x", this.mouseX);
            json.addProperty("mouse_y", this.mouseY);
            json.addProperty("cursor_type", this.cursorType);
            if (this.interactive) {
                json.addProperty("interactive", true);
            }
            json.addProperty("blur", this.blur);
            if (this.beforeBlur != null) {
                json.add("before_blur", this.beforeBlur.toJson());
            }
            if (this.afterBlur != null) {
                json.add("after_blur", this.afterBlur.toJson());
            }
            return json;
        }

        public static @Nullable Sample fromJson(JsonObject json) {
            Sample sample = new Sample();
            if (!json.has("tick")) {
                return null;
            }

            sample.tick = json.get("tick").getAsInt();
            if (json.has("partial_tick")) {
                sample.partialTick = json.get("partial_tick").getAsFloat();
            }
            if (json.has("sample_time")) {
                sample.sampleTime = json.get("sample_time").getAsDouble();
            }
            if (json.has("source_width")) {
                sample.sourceWidth = json.get("source_width").getAsInt();
            }
            if (json.has("source_height")) {
                sample.sourceHeight = json.get("source_height").getAsInt();
            }
            if (json.has("gui_scale")) {
                sample.guiScale = json.get("gui_scale").getAsInt();
            }
            if (json.has("mouse_x")) {
                sample.mouseX = json.get("mouse_x").getAsDouble();
            }
            if (json.has("mouse_y")) {
                sample.mouseY = json.get("mouse_y").getAsDouble();
            }
            if (json.has("cursor_type")) {
                sample.cursorType = json.get("cursor_type").getAsString();
            }
            if (json.has("interactive")) {
                sample.interactive = json.get("interactive").getAsBoolean();
            }
            if (json.has("blur")) {
                sample.blur = json.get("blur").getAsBoolean();
            }
            if (json.has("before_blur") && json.get("before_blur").isJsonObject()) {
                sample.beforeBlur = Layer.fromJson(json.getAsJsonObject("before_blur"));
            }
            if (json.has("after_blur") && json.get("after_blur").isJsonObject()) {
                sample.afterBlur = Layer.fromJson(json.getAsJsonObject("after_blur"));
            }
            return sample;
        }

        public Sample copyWithTickOffset(int tickOffset) {
            Sample copy = new Sample();
            copy.tick = this.tick + tickOffset;
            copy.partialTick = this.partialTick;
            copy.sampleTime = Double.isNaN(this.sampleTime) ? Double.NaN : this.sampleTime + tickOffset;
            copy.sourceWidth = this.sourceWidth;
            copy.sourceHeight = this.sourceHeight;
            copy.guiScale = this.guiScale;
            copy.mouseX = this.mouseX;
            copy.mouseY = this.mouseY;
            copy.cursorType = this.cursorType;
            copy.interactive = this.interactive;
            copy.blur = this.blur;
            copy.beforeBlur = this.beforeBlur == null ? null : this.beforeBlur.copy();
            copy.afterBlur = this.afterBlur == null ? null : this.afterBlur.copy();
            return copy;
        }
    }

    public static class CursorSample {
        public int tick;
        public float partialTick;
        public int sourceWidth;
        public int sourceHeight;
        public double sampleTime = Double.NaN;
        public double mouseX;
        public double mouseY;
        public String cursorType = "none";

        public double time() {
            if (!Double.isNaN(this.sampleTime)) {
                return this.sampleTime;
            }
            return this.tick + this.partialTick;
        }

        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("tick", this.tick);
            json.addProperty("partial_tick", this.partialTick);
            if (!Double.isNaN(this.sampleTime)) {
                json.addProperty("sample_time", this.sampleTime);
            }
            json.addProperty("source_width", this.sourceWidth);
            json.addProperty("source_height", this.sourceHeight);
            json.addProperty("mouse_x", this.mouseX);
            json.addProperty("mouse_y", this.mouseY);
            json.addProperty("cursor_type", this.cursorType);
            return json;
        }

        public static @Nullable CursorSample fromJson(JsonObject json) {
            if (!json.has("tick")) {
                return null;
            }

            CursorSample sample = new CursorSample();
            sample.tick = json.get("tick").getAsInt();
            if (json.has("partial_tick")) {
                sample.partialTick = json.get("partial_tick").getAsFloat();
            }
            if (json.has("sample_time")) {
                sample.sampleTime = json.get("sample_time").getAsDouble();
            }
            if (json.has("source_width")) {
                sample.sourceWidth = json.get("source_width").getAsInt();
            }
            if (json.has("source_height")) {
                sample.sourceHeight = json.get("source_height").getAsInt();
            }
            if (json.has("mouse_x")) {
                sample.mouseX = json.get("mouse_x").getAsDouble();
            }
            if (json.has("mouse_y")) {
                sample.mouseY = json.get("mouse_y").getAsDouble();
            }
            if (json.has("cursor_type")) {
                sample.cursorType = json.get("cursor_type").getAsString();
            }
            return sample;
        }

        public CursorSample copyWithTickOffset(int tickOffset) {
            CursorSample copy = new CursorSample();
            copy.tick = this.tick + tickOffset;
            copy.partialTick = this.partialTick;
            copy.sampleTime = Double.isNaN(this.sampleTime) ? Double.NaN : this.sampleTime + tickOffset;
            copy.sourceWidth = this.sourceWidth;
            copy.sourceHeight = this.sourceHeight;
            copy.mouseX = this.mouseX;
            copy.mouseY = this.mouseY;
            copy.cursorType = this.cursorType;
            return copy;
        }
    }

    public static class CaptureStats {
        public long renderFrames;
        public long captureDecisions;
        public long readbackStarts;
        public long readbackCompletions;
        public long skippedReadbacks;
        public long skippedEncodeJobs;
        public long skippedInteractiveEncodeJobs;
        public long skippedStaticEncodeJobs;
        public long preventedStaleSamples;
        public long appendedSamples;
        public long duplicateSamples;
        public long appendedCursorSamples;
        public long duplicateCursorSamples;
        public long duplicateSampleTimes;
        public long duplicateCursorSampleTimes;
        public long serializedSamples;
        public long serializedCursorSamples;
        public long clearSamples;
        public long screenCaptures;
        public long hudCapturesSkipped;
        public long expectedScreenSamples;
        public long maxCaptureWidth;
        public long maxCaptureHeight;
        public long beforeBlurLayerDraws;
        public long afterBlurLayerDraws;
        public long qoiAssets;
        public long dirtyTiles;
        public long backgroundTilesDropped;
        public double maxSampleGapTicks;
        public double maxInteractiveSampleGapTicks;
        public double maxCursorSampleGapTicks;
        public long maxEncodeQueueBytes;
        public long maxEncodeQueueJobs;
        public long maxEncodeLatencyMillis;

        public boolean isEmpty() {
            return this.renderFrames == 0 && this.captureDecisions == 0 && this.readbackStarts == 0 &&
                this.readbackCompletions == 0 && this.skippedReadbacks == 0 && this.skippedEncodeJobs == 0 &&
                this.skippedInteractiveEncodeJobs == 0 && this.skippedStaticEncodeJobs == 0 &&
                this.preventedStaleSamples == 0 &&
                this.appendedSamples == 0 && this.duplicateSamples == 0 && this.appendedCursorSamples == 0 &&
                this.duplicateCursorSamples == 0 && this.duplicateSampleTimes == 0 &&
                this.duplicateCursorSampleTimes == 0 && this.serializedSamples == 0 &&
                this.serializedCursorSamples == 0 && this.clearSamples == 0 &&
                this.screenCaptures == 0 && this.hudCapturesSkipped == 0 && this.expectedScreenSamples == 0 &&
                this.maxCaptureWidth == 0 && this.maxCaptureHeight == 0 &&
                this.beforeBlurLayerDraws == 0 && this.afterBlurLayerDraws == 0 &&
                this.qoiAssets == 0 && this.dirtyTiles == 0 && this.backgroundTilesDropped == 0 &&
                this.maxSampleGapTicks == 0.0 &&
                this.maxInteractiveSampleGapTicks == 0.0 && this.maxCursorSampleGapTicks == 0.0 &&
                this.maxEncodeQueueBytes == 0 && this.maxEncodeQueueJobs == 0 && this.maxEncodeLatencyMillis == 0;
        }

        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("render_frames", this.renderFrames);
            json.addProperty("capture_decisions", this.captureDecisions);
            json.addProperty("readback_starts", this.readbackStarts);
            json.addProperty("readback_completions", this.readbackCompletions);
            json.addProperty("skipped_readbacks", this.skippedReadbacks);
            json.addProperty("skipped_encode_jobs", this.skippedEncodeJobs);
            json.addProperty("skipped_interactive_encode_jobs", this.skippedInteractiveEncodeJobs);
            json.addProperty("skipped_static_encode_jobs", this.skippedStaticEncodeJobs);
            json.addProperty("prevented_stale_samples", this.preventedStaleSamples);
            json.addProperty("appended_samples", this.appendedSamples);
            json.addProperty("duplicate_samples", this.duplicateSamples);
            json.addProperty("appended_cursor_samples", this.appendedCursorSamples);
            json.addProperty("duplicate_cursor_samples", this.duplicateCursorSamples);
            json.addProperty("duplicate_sample_times", this.duplicateSampleTimes);
            json.addProperty("duplicate_cursor_sample_times", this.duplicateCursorSampleTimes);
            json.addProperty("serialized_samples", this.serializedSamples);
            json.addProperty("serialized_cursor_samples", this.serializedCursorSamples);
            json.addProperty("clear_samples", this.clearSamples);
            json.addProperty("screen_captures", this.screenCaptures);
            json.addProperty("hud_captures_skipped", this.hudCapturesSkipped);
            json.addProperty("expected_screen_samples", this.expectedScreenSamples);
            json.addProperty("max_capture_width", this.maxCaptureWidth);
            json.addProperty("max_capture_height", this.maxCaptureHeight);
            json.addProperty("before_blur_layer_draws", this.beforeBlurLayerDraws);
            json.addProperty("after_blur_layer_draws", this.afterBlurLayerDraws);
            json.addProperty("qoi_assets", this.qoiAssets);
            json.addProperty("dirty_tiles", this.dirtyTiles);
            json.addProperty("background_tiles_dropped", this.backgroundTilesDropped);
            json.addProperty("max_sample_gap_ticks", this.maxSampleGapTicks);
            json.addProperty("max_interactive_sample_gap_ticks", this.maxInteractiveSampleGapTicks);
            json.addProperty("max_cursor_sample_gap_ticks", this.maxCursorSampleGapTicks);
            json.addProperty("max_encode_queue_bytes", this.maxEncodeQueueBytes);
            json.addProperty("max_encode_queue_jobs", this.maxEncodeQueueJobs);
            json.addProperty("max_encode_latency_millis", this.maxEncodeLatencyMillis);
            return json;
        }

        public static CaptureStats fromJson(JsonObject json) {
            CaptureStats stats = new CaptureStats();
            if (json.has("render_frames")) {
                stats.renderFrames = json.get("render_frames").getAsLong();
            }
            if (json.has("capture_decisions")) {
                stats.captureDecisions = json.get("capture_decisions").getAsLong();
            }
            if (json.has("readback_starts")) {
                stats.readbackStarts = json.get("readback_starts").getAsLong();
            }
            if (json.has("readback_completions")) {
                stats.readbackCompletions = json.get("readback_completions").getAsLong();
            }
            if (json.has("skipped_readbacks")) {
                stats.skippedReadbacks = json.get("skipped_readbacks").getAsLong();
            }
            if (json.has("skipped_encode_jobs")) {
                stats.skippedEncodeJobs = json.get("skipped_encode_jobs").getAsLong();
            }
            if (json.has("skipped_interactive_encode_jobs")) {
                stats.skippedInteractiveEncodeJobs = json.get("skipped_interactive_encode_jobs").getAsLong();
            }
            if (json.has("skipped_static_encode_jobs")) {
                stats.skippedStaticEncodeJobs = json.get("skipped_static_encode_jobs").getAsLong();
            }
            if (json.has("prevented_stale_samples")) {
                stats.preventedStaleSamples = json.get("prevented_stale_samples").getAsLong();
            }
            if (json.has("appended_samples")) {
                stats.appendedSamples = json.get("appended_samples").getAsLong();
            }
            if (json.has("duplicate_samples")) {
                stats.duplicateSamples = json.get("duplicate_samples").getAsLong();
            }
            if (json.has("appended_cursor_samples")) {
                stats.appendedCursorSamples = json.get("appended_cursor_samples").getAsLong();
            }
            if (json.has("duplicate_cursor_samples")) {
                stats.duplicateCursorSamples = json.get("duplicate_cursor_samples").getAsLong();
            }
            if (json.has("duplicate_sample_times")) {
                stats.duplicateSampleTimes = json.get("duplicate_sample_times").getAsLong();
            }
            if (json.has("duplicate_cursor_sample_times")) {
                stats.duplicateCursorSampleTimes = json.get("duplicate_cursor_sample_times").getAsLong();
            }
            if (json.has("serialized_samples")) {
                stats.serializedSamples = json.get("serialized_samples").getAsLong();
            }
            if (json.has("serialized_cursor_samples")) {
                stats.serializedCursorSamples = json.get("serialized_cursor_samples").getAsLong();
            }
            if (json.has("clear_samples")) {
                stats.clearSamples = json.get("clear_samples").getAsLong();
            }
            if (json.has("screen_captures")) {
                stats.screenCaptures = json.get("screen_captures").getAsLong();
            }
            if (json.has("hud_captures_skipped")) {
                stats.hudCapturesSkipped = json.get("hud_captures_skipped").getAsLong();
            }
            if (json.has("expected_screen_samples")) {
                stats.expectedScreenSamples = json.get("expected_screen_samples").getAsLong();
            }
            if (json.has("max_capture_width")) {
                stats.maxCaptureWidth = json.get("max_capture_width").getAsLong();
            }
            if (json.has("max_capture_height")) {
                stats.maxCaptureHeight = json.get("max_capture_height").getAsLong();
            }
            if (json.has("before_blur_layer_draws")) {
                stats.beforeBlurLayerDraws = json.get("before_blur_layer_draws").getAsLong();
            }
            if (json.has("after_blur_layer_draws")) {
                stats.afterBlurLayerDraws = json.get("after_blur_layer_draws").getAsLong();
            }
            if (json.has("qoi_assets")) {
                stats.qoiAssets = json.get("qoi_assets").getAsLong();
            }
            if (json.has("dirty_tiles")) {
                stats.dirtyTiles = json.get("dirty_tiles").getAsLong();
            }
            if (json.has("background_tiles_dropped")) {
                stats.backgroundTilesDropped = json.get("background_tiles_dropped").getAsLong();
            }
            if (json.has("max_sample_gap_ticks")) {
                stats.maxSampleGapTicks = json.get("max_sample_gap_ticks").getAsDouble();
            }
            if (json.has("max_interactive_sample_gap_ticks")) {
                stats.maxInteractiveSampleGapTicks = json.get("max_interactive_sample_gap_ticks").getAsDouble();
            }
            if (json.has("max_cursor_sample_gap_ticks")) {
                stats.maxCursorSampleGapTicks = json.get("max_cursor_sample_gap_ticks").getAsDouble();
            }
            if (json.has("max_encode_queue_bytes")) {
                stats.maxEncodeQueueBytes = json.get("max_encode_queue_bytes").getAsLong();
            }
            if (json.has("max_encode_queue_jobs")) {
                stats.maxEncodeQueueJobs = json.get("max_encode_queue_jobs").getAsLong();
            }
            if (json.has("max_encode_latency_millis")) {
                stats.maxEncodeLatencyMillis = json.get("max_encode_latency_millis").getAsLong();
            }
            return stats;
        }
    }

    public static class Layer {
        public @Nullable String path;
        public int x;
        public int y;
        public int width;
        public int height;
        public final List<Layer> tiles = new ArrayList<>();

        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            if (this.path != null) {
                json.addProperty("path", this.path);
            }
            json.addProperty("x", this.x);
            json.addProperty("y", this.y);
            json.addProperty("width", this.width);
            json.addProperty("height", this.height);
            if (!this.tiles.isEmpty()) {
                JsonArray tilesJson = new JsonArray();
                for (Layer tile : this.tiles) {
                    tilesJson.add(tile.toJson());
                }
                json.add("tiles", tilesJson);
            }
            return json;
        }

        public static @Nullable Layer fromJson(JsonObject json) {
            Layer layer = new Layer();
            if (json.has("path")) {
                layer.path = json.get("path").getAsString();
            }
            if (json.has("x")) {
                layer.x = json.get("x").getAsInt();
            }
            if (json.has("y")) {
                layer.y = json.get("y").getAsInt();
            }
            if (json.has("width")) {
                layer.width = json.get("width").getAsInt();
            }
            if (json.has("height")) {
                layer.height = json.get("height").getAsInt();
            }
            if (json.has("tiles") && json.get("tiles").isJsonArray()) {
                for (JsonElement element : json.getAsJsonArray("tiles")) {
                    if (!element.isJsonObject()) {
                        continue;
                    }

                    Layer tile = Layer.fromJson(element.getAsJsonObject());
                    if (tile != null) {
                        layer.tiles.add(tile);
                    }
                }
            }
            if (layer.path == null && layer.tiles.isEmpty()) {
                return null;
            }
            return layer;
        }

        public Layer copy() {
            Layer copy = new Layer();
            copy.path = this.path;
            copy.x = this.x;
            copy.y = this.y;
            copy.width = this.width;
            copy.height = this.height;
            for (Layer tile : this.tiles) {
                copy.tiles.add(tile.copy());
            }
            return copy;
        }
    }
}
