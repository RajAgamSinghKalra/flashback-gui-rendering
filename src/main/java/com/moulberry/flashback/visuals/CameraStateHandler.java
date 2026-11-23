package com.moulberry.flashback.visuals;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.IrisShaderCompatibility;
import com.moulberry.flashback.compat.CameraModCompatibility;
import com.moulberry.flashback.packet.FlashbackCameraState;
import com.moulberry.flashback.playback.ReplayServer;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.joml.Vector2f;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class CameraStateHandler {

    private static final int MAX_RETAINED_SAMPLES = 720;
    private static final double SAMPLE_RETAIN_TICKS = 10.0;

    private static final List<FlashbackCameraState.Sample> samples = new ArrayList<>();
    private static FlashbackCameraState.Sample activeSample = null;
    private static boolean exportCameraKeyframed = false;
    private static boolean previewCameraKeyframed = false;
    private static boolean hasReceivedCameraState = false;
    private static boolean loggedRecordedCameraState = false;
    private static boolean loggedMissingRecordedCameraState = false;
    private static boolean loggedIrisShaderCameraStateDisabled = false;
    private static double lastReplaySampleTime = Double.NaN;
    private static double lastTimingWarningTime = Double.NEGATIVE_INFINITY;

    public static void tick() {
        if (!Flashback.isInReplay()) {
            reset();
            return;
        }

        double replayTime = currentReplaySampleTime(0.0f);
        if (Double.isFinite(lastReplaySampleTime) && replayTime + 2.0 < lastReplaySampleTime) {
            samples.clear();
            activeSample = null;
            RecordedCameraRenderState.reset();
            CameraModCompatibility.resetRealCameraSmoothing("replay camera time jump");
        }
        lastReplaySampleTime = replayTime;
        pruneSamples(replayTime);
        activeSample = null;
    }

    public static void update(FlashbackCameraState data) {
        if (data.samples().isEmpty()) {
            return;
        }

        double legacyBaseTime = currentReplaySampleTime(0.0f);
        double timingOffset = data.version() < 3 ? 1.0 : 0.0;
        for (FlashbackCameraState.Sample sample : data.samples()) {
            samples.add(normalizeLegacySample(sample, legacyBaseTime, timingOffset));
        }
        samples.sort(Comparator.comparingDouble(FlashbackCameraState.Sample::sampleTime));
        hasReceivedCameraState = true;
        CameraModCompatibility.logLoadedModsOnce("recorded camera-state stream");
        logIncomingSampleGapWarnings(data.samples());
        pruneSamples(currentReplaySampleTime(0.0f));
    }

    public static void setExportCameraKeyframed(boolean keyframed) {
        exportCameraKeyframed = keyframed;
    }

    public static void setPreviewCameraKeyframed(boolean keyframed) {
        previewCameraKeyframed = keyframed;
    }

    public static void setActiveSample(@Nullable FlashbackCameraState.Sample sample) {
        activeSample = sample;
    }

    public static @Nullable FlashbackCameraState.Sample getActiveSample() {
        return activeSample;
    }

    public static boolean hasReceivedCameraState() {
        return hasReceivedCameraState;
    }

    public static boolean hasSampleForTime(double sampleTime, double maxDistanceTicks) {
        for (FlashbackCameraState.Sample sample : samples) {
            if (Math.abs(sample.sampleTime() - sampleTime) <= maxDistanceTicks) {
                return true;
            }
        }
        return false;
    }

    public static @Nullable FlashbackCameraState.Sample getOwnershipSample(float partialTick) {
        if (!shouldUseRecordedCameraStateBase()) {
            return null;
        }
        FlashbackCameraState.Sample sample = getInterpolatedSample(partialTick);
        if (sample != null) {
            logRecordedCameraStateOnce();
        }
        return sample;
    }

    public static @Nullable FlashbackCameraState.Sample getPoseSample(float partialTick) {
        if (!shouldUseRecordedCameraPose()) {
            return null;
        }
        FlashbackCameraState.Sample sample = getInterpolatedSample(partialTick);
        if (sample != null) {
            logRecordedCameraStateOnce();
        }
        return sample;
    }

    public static double currentReplaySampleTime(float partialTick) {
        if (Flashback.EXPORT_JOB != null) {
            return Flashback.EXPORT_JOB.getSettings().startTick() + Flashback.EXPORT_JOB.getCurrentTickDouble();
        }

        ReplayServer replayServer = Flashback.getReplayServer();
        if (replayServer != null) {
            return replayServer.getPartialReplayTick();
        }

        return partialTick;
    }

    private static @Nullable FlashbackCameraState.Sample getInterpolatedSample(float partialTick) {
        if (samples.isEmpty()) {
            logMissingRecordedCameraStateOnce();
            return null;
        }

        double sampleTime = currentReplaySampleTime(partialTick);
        FlashbackCameraState.Sample floor = null;
        FlashbackCameraState.Sample ceil = null;

        for (FlashbackCameraState.Sample sample : samples) {
            if (sample.sampleTime() <= sampleTime) {
                floor = sample;
            }
            if (sample.sampleTime() >= sampleTime) {
                ceil = sample;
                break;
            }
        }

        if (floor == null) {
            floor = ceil;
        }
        if (ceil == null) {
            ceil = floor;
        }
        if (floor == null) {
            logMissingRecordedCameraStateOnce();
            return null;
        }

        logSelectedSampleTiming(sampleTime, floor, ceil);

        if (floor == ceil || floor.sampleTime() == ceil.sampleTime()) {
            return withSampleTime(floor, partialTick, sampleTime);
        }

        float amount = Mth.clamp((float)((sampleTime - floor.sampleTime()) / (ceil.sampleTime() - floor.sampleTime())), 0.0f, 1.0f);
        FlashbackCameraState.Sample modeSample = amount < 0.5f ? floor : ceil;
        Quaternionf rotation = new Quaternionf(floor.qx(), floor.qy(), floor.qz(), floor.qw())
            .slerp(new Quaternionf(ceil.qx(), ceil.qy(), ceil.qz(), ceil.qw()), amount);
        boolean hasEntityPose = floor.hasEntityPose() && ceil.hasEntityPose();
        double entityX = hasEntityPose ? Mth.lerp(amount, floor.entityX(), ceil.entityX()) : modeSample.entityX();
        double entityY = hasEntityPose ? Mth.lerp(amount, floor.entityY(), ceil.entityY()) : modeSample.entityY();
        double entityZ = hasEntityPose ? Mth.lerp(amount, floor.entityZ(), ceil.entityZ()) : modeSample.entityZ();
        float entityYaw = hasEntityPose ? Mth.rotLerp(amount, floor.entityYaw(), ceil.entityYaw()) : modeSample.entityYaw();
        float entityPitch = hasEntityPose ? Mth.rotLerp(amount, floor.entityPitch(), ceil.entityPitch()) : modeSample.entityPitch();
        float entityHeadYaw = hasEntityPose ? Mth.rotLerp(amount, floor.entityHeadYaw(), ceil.entityHeadYaw()) : modeSample.entityHeadYaw();
        float entityBodyYaw = hasEntityPose && Float.isFinite(floor.entityBodyYaw()) && Float.isFinite(ceil.entityBodyYaw())
            ? Mth.rotLerp(amount, floor.entityBodyYaw(), ceil.entityBodyYaw())
            : modeSample.entityBodyYaw();

        return new FlashbackCameraState.Sample(
            partialTick,
            sampleTime,
            modeSample.cameraType(),
            modeSample.detached(),
            modeSample.cameraEntityUuid(),
            Mth.lerp(amount, floor.x(), ceil.x()),
            Mth.lerp(amount, floor.y(), ceil.y()),
            Mth.lerp(amount, floor.z(), ceil.z()),
            rotation.x,
            rotation.y,
            rotation.z,
            rotation.w,
            Mth.lerp(amount, floor.fov(), ceil.fov()),
            modeSample.compatibilityFlags(),
            hasEntityPose,
            entityX,
            entityY,
            entityZ,
            entityYaw,
            entityPitch,
            entityHeadYaw,
            entityBodyYaw
        );
    }

    public static void applyCameraType(FlashbackCameraState.Sample sample) {
        CameraType[] values = CameraType.values();
        if (sample.cameraType() < 0 || sample.cameraType() >= values.length) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        CameraType cameraType = values[sample.cameraType()];
        if (minecraft.options.getCameraType() != cameraType) {
            minecraft.options.setCameraType(cameraType);
        }
    }

    public static @Nullable Entity applyCameraEntity(FlashbackCameraState.Sample sample) {
        if (sample.cameraEntityUuid() == null) {
            return null;
        }

        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            return null;
        }

        Entity entity = level.getEntities().get(sample.cameraEntityUuid());
        if (entity == null || entity.isRemoved()) {
            return null;
        }

        if (minecraft.getCameraEntity() != entity) {
            minecraft.setCameraEntity(entity);
        }
        return entity;
    }

    public static void applyRecordedEntityPose(FlashbackCameraState.Sample sample, @Nullable Entity entity) {
        if (entity == null || entity.isPassenger() || sample.detached()) {
            return;
        }

        double x;
        double y;
        double z;
        float yaw;
        float pitch;
        float headYaw;
        float bodyYaw;

        if (sample.hasEntityPose()) {
            x = sample.entityX();
            y = sample.entityY();
            z = sample.entityZ();
            yaw = sample.entityYaw();
            pitch = sample.entityPitch();
            headYaw = sample.entityHeadYaw();
            bodyYaw = sample.entityBodyYaw();
        } else {
            Vector3d accuratePosition = AccurateEntityPositionHandler.getAccuratePosition(entity, sample.partialTick());
            Vector2f accurateRotation = AccurateEntityPositionHandler.getAccurateRotation(entity, sample.partialTick());
            if (accuratePosition != null && accurateRotation != null) {
                x = accuratePosition.x;
                y = accuratePosition.y;
                z = accuratePosition.z;
                pitch = accurateRotation.x;
                yaw = accurateRotation.y;
                headYaw = yaw;
                bodyYaw = yaw;
            } else if (!CameraModCompatibility.isFirstPersonModelLoaded() && !CameraModCompatibility.isRealCameraLoaded()) {
                CameraType[] values = CameraType.values();
                if (sample.cameraType() < 0 || sample.cameraType() >= values.length || !values[sample.cameraType()].isFirstPerson()) {
                    return;
                }

                Vector3f euler = rotationFromSample(sample).getEulerAnglesYXZ(new Vector3f());
                pitch = Mth.wrapDegrees((float)Math.toDegrees(euler.x));
                yaw = Mth.wrapDegrees((float)-Math.toDegrees(euler.y));
                headYaw = yaw;
                bodyYaw = yaw;
                x = sample.x();
                y = sample.y() - entity.getEyeHeight();
                z = sample.z();
            } else {
                return;
            }
        }

        entity.snapTo(x, y, z, yaw, pitch);
        entity.xo = x;
        entity.yo = y;
        entity.zo = z;
        entity.xOld = x;
        entity.yOld = y;
        entity.zOld = z;
        entity.yRotO = yaw;
        entity.xRotO = pitch;
        entity.setYRot(yaw);
        entity.setXRot(pitch);
        if (entity instanceof LivingEntity livingEntity) {
            livingEntity.setYHeadRot(headYaw);
            if (Float.isFinite(bodyYaw)) {
                livingEntity.setYBodyRot(bodyYaw);
            }
            livingEntity.lerpHeadSteps = 0;
            livingEntity.yHeadRotO = livingEntity.yHeadRot;
            livingEntity.yBodyRotO = livingEntity.yBodyRot;
            livingEntity.yRotO = livingEntity.getYRot();
            livingEntity.xRotO = livingEntity.getXRot();
        }
    }

    public static Quaternionf rotationFromSample(FlashbackCameraState.Sample sample) {
        return new Quaternionf(sample.qx(), sample.qy(), sample.qz(), sample.qw());
    }

    public static FlashbackCameraState.Sample createSample(float partialTick, double sampleTime,
                                                          CameraType cameraType, boolean detached,
                                                          @Nullable Entity cameraEntity,
                                                          double x, double y, double z,
                                                          Quaternionfc rotation, float fov) {
        boolean hasEntityPose = cameraEntity != null;
        double entityX = hasEntityPose ? Mth.lerp(partialTick, cameraEntity.xo, cameraEntity.getX()) : 0.0;
        double entityY = hasEntityPose ? Mth.lerp(partialTick, cameraEntity.yo, cameraEntity.getY()) : 0.0;
        double entityZ = hasEntityPose ? Mth.lerp(partialTick, cameraEntity.zo, cameraEntity.getZ()) : 0.0;
        float entityYaw = hasEntityPose ? cameraEntity.getViewYRot(partialTick) : 0.0f;
        float entityPitch = hasEntityPose ? cameraEntity.getViewXRot(partialTick) : 0.0f;
        float entityHeadYaw = entityYaw;
        float entityBodyYaw = entityYaw;
        if (cameraEntity instanceof LivingEntity livingEntity) {
            entityHeadYaw = Mth.rotLerp(partialTick, livingEntity.yHeadRotO, livingEntity.yHeadRot);
            entityBodyYaw = Mth.rotLerp(partialTick, livingEntity.yBodyRotO, livingEntity.yBodyRot);
        }

        return new FlashbackCameraState.Sample(
            partialTick,
            sampleTime,
            cameraType.ordinal(),
            detached,
            cameraEntity == null ? null : cameraEntity.getUUID(),
            x,
            y,
            z,
            rotation.x(),
            rotation.y(),
            rotation.z(),
            rotation.w(),
            fov,
            CameraModCompatibility.currentCompatibilityFlags(),
            hasEntityPose,
            entityX,
            entityY,
            entityZ,
            entityYaw,
            entityPitch,
            entityHeadYaw,
            entityBodyYaw
        );
    }

    private static boolean shouldUseRecordedCameraStateBase() {
        if (!Flashback.isInReplay()) {
            return false;
        }
        if (Flashback.isExporting()) {
            return !exportCameraKeyframed;
        }
        return !previewCameraKeyframed;
    }

    private static boolean shouldUseRecordedCameraPose() {
        if (!shouldUseRecordedCameraStateBase()) {
            return false;
        }
        if (IrisShaderCompatibility.isActive()) {
            logIrisShaderCameraStateDisabledOnce();
            return false;
        }
        loggedIrisShaderCameraStateDisabled = false;
        return true;
    }

    private static FlashbackCameraState.Sample normalizeLegacySample(FlashbackCameraState.Sample sample, double legacyBaseTime, double timingOffset) {
        if (Double.isFinite(sample.sampleTime())) {
            return timingOffset == 0.0 ? sample : withSampleTime(sample, sample.partialTick(), sample.sampleTime() + timingOffset);
        }
        return withSampleTime(sample, sample.partialTick(), legacyBaseTime + sample.partialTick() + timingOffset);
    }

    private static FlashbackCameraState.Sample withSampleTime(FlashbackCameraState.Sample sample, float partialTick, double sampleTime) {
        return new FlashbackCameraState.Sample(
            partialTick,
            sampleTime,
            sample.cameraType(),
            sample.detached(),
            sample.cameraEntityUuid(),
            sample.x(),
            sample.y(),
            sample.z(),
            sample.qx(),
            sample.qy(),
            sample.qz(),
            sample.qw(),
            sample.fov(),
            sample.compatibilityFlags(),
            sample.hasEntityPose(),
            sample.entityX(),
            sample.entityY(),
            sample.entityZ(),
            sample.entityYaw(),
            sample.entityPitch(),
            sample.entityHeadYaw(),
            sample.entityBodyYaw()
        );
    }

    private static void pruneSamples(double sampleTime) {
        samples.removeIf(sample -> sample.sampleTime() < sampleTime - SAMPLE_RETAIN_TICKS);
        while (samples.size() > MAX_RETAINED_SAMPLES) {
            samples.removeFirst();
        }
    }

    private static void reset() {
        samples.clear();
        activeSample = null;
        exportCameraKeyframed = false;
        previewCameraKeyframed = false;
        hasReceivedCameraState = false;
        loggedRecordedCameraState = false;
        loggedMissingRecordedCameraState = false;
        loggedIrisShaderCameraStateDisabled = false;
        lastReplaySampleTime = Double.NaN;
        lastTimingWarningTime = Double.NEGATIVE_INFINITY;
        CameraModCompatibility.resetLogging();
        RecordedCameraRenderState.reset();
    }

    private static void logIncomingSampleGapWarnings(List<FlashbackCameraState.Sample> incomingSamples) {
        if (incomingSamples.size() < 2) {
            return;
        }

        double maxGap = 0.0;
        FlashbackCameraState.Sample previous = null;
        for (FlashbackCameraState.Sample sample : incomingSamples) {
            if (!Double.isFinite(sample.sampleTime())) {
                continue;
            }
            if (previous != null) {
                maxGap = Math.max(maxGap, sample.sampleTime() - previous.sampleTime());
            }
            previous = sample;
        }

        if (maxGap > 0.75) {
            Flashback.LOGGER.warn("Flashback camera-state stream has a large recorded sample gap: {} ticks", String.format("%.3f", maxGap));
        }
    }

    private static void logSelectedSampleTiming(double sampleTime, FlashbackCameraState.Sample floor, FlashbackCameraState.Sample ceil) {
        double age = Math.min(Math.abs(sampleTime - floor.sampleTime()), Math.abs(ceil.sampleTime() - sampleTime));
        double gap = Math.abs(ceil.sampleTime() - floor.sampleTime());
        if ((age > 0.75 || gap > 0.75) && sampleTime - lastTimingWarningTime > 20.0) {
            lastTimingWarningTime = sampleTime;
            Flashback.LOGGER.warn(
                "Flashback camera-state playback timing warning: sampleTime={}, selectedAge={} ticks, surroundingGap={} ticks",
                String.format("%.3f", sampleTime),
                String.format("%.3f", age),
                String.format("%.3f", gap)
            );
        }
    }

    private static void logMissingRecordedCameraStateOnce() {
        if (!loggedMissingRecordedCameraState && Flashback.isExporting()) {
            loggedMissingRecordedCameraState = true;
            Flashback.LOGGER.warn("Flashback camera source: LIVE_SPECTATE_FOLLOW. This replay has no recorded camera-state stream, so F5/camera-mod perspective changes cannot be reconstructed exactly. cameraMods={}", CameraModCompatibility.loadedCameraMods());
        }
    }

    private static void logIrisShaderCameraStateDisabledOnce() {
        if (!loggedIrisShaderCameraStateDisabled) {
            loggedIrisShaderCameraStateDisabled = true;
            Flashback.LOGGER.warn(
                "Flashback camera source: RECORDED_CAMERA_STATE_OWNERSHIP_ONLY. Iris shaders are enabled, so Flashback " +
                    "preserves recorded camera entity/type and feeds camera-mod state before Camera.update instead of replacing pose after Camera.update. cameraMods={}",
                CameraModCompatibility.loadedCameraMods()
            );
        }
    }

    private static void logRecordedCameraStateOnce() {
        if (loggedRecordedCameraState) {
            return;
        }
        loggedRecordedCameraState = true;
        String source = IrisShaderCompatibility.isActive() ? "RECORDED_CAMERA_STATE_OWNERSHIP_PLUS_CAMERA_MODS" : "RECORDED_CAMERA_STATE";
        Flashback.LOGGER.info("Flashback camera source: {}. cameraMods={}", source, CameraModCompatibility.loadedCameraMods());
    }
}
