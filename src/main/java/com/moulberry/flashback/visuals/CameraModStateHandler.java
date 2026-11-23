package com.moulberry.flashback.visuals;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.compat.CameraModCompatibility;
import com.moulberry.flashback.packet.FlashbackCameraModState;
import com.moulberry.flashback.packet.FlashbackCameraState;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public class CameraModStateHandler {

    private static final int MAX_RETAINED_SAMPLES = 720;
    private static final double SAMPLE_RETAIN_TICKS = 10.0;

    private static final List<FlashbackCameraModState.Sample> samples = new ArrayList<>();
    private static boolean hasReceivedCameraModState = false;
    private static boolean loggedDirectDoABarrelRollState = false;
    private static boolean loggedQuaternionDoABarrelRollFallback = false;
    private static boolean loggedMissingDoABarrelRollState = false;
    private static double lastReplaySampleTime = Double.NaN;
    private static double lastTimingWarningTime = Double.NEGATIVE_INFINITY;

    public static void tick() {
        if (!Flashback.isInReplay()) {
            reset();
            return;
        }

        double replayTime = CameraStateHandler.currentReplaySampleTime(0.0f);
        if (Double.isFinite(lastReplaySampleTime) && replayTime + 2.0 < lastReplaySampleTime) {
            samples.clear();
        }
        lastReplaySampleTime = replayTime;
        pruneSamples(replayTime);
    }

    public static void update(FlashbackCameraModState data) {
        if (data.samples().isEmpty()) {
            return;
        }

        double timingOffset = data.version() < 2 ? 1.0 : 0.0;
        if (timingOffset == 0.0) {
            samples.addAll(data.samples());
        } else {
            for (FlashbackCameraModState.Sample sample : data.samples()) {
                samples.add(new FlashbackCameraModState.Sample(
                    sample.sampleTime() + timingOffset,
                    sample.entityUuid(),
                    sample.compatibilityFlags(),
                    sample.doABarrelRollRolling(),
                    sample.doABarrelRollRoll(),
                    sample.doABarrelRollPrevRoll()
                ));
            }
        }
        samples.sort(Comparator.comparingDouble(FlashbackCameraModState.Sample::sampleTime));
        hasReceivedCameraModState = true;
        CameraModCompatibility.logLoadedModsOnce("recorded camera-mod-state stream");
        logIncomingSampleGapWarnings(data.samples());
        pruneSamples(CameraStateHandler.currentReplaySampleTime(0.0f));
    }

    public static boolean hasReceivedCameraModState() {
        return hasReceivedCameraModState;
    }

    public static void applyForCameraEntity(float partialTick, @Nullable FlashbackCameraState.Sample cameraSample) {
        if (!Flashback.isInReplay() || cameraSample == null || !CameraModCompatibility.isDoABarrelRollLoaded()) {
            return;
        }

        Entity entity = Minecraft.getInstance().getCameraEntity();
        if (entity == null || !CameraModCompatibility.hasDoABarrelRollState(entity)) {
            return;
        }

        SelectedDoABarrelRollState selectedState = selectDoABarrelRollState(entity.getUUID(), partialTick);
        if (selectedState != null) {
            if (CameraModCompatibility.applyDoABarrelRollState(entity, selectedState.rolling(), selectedState.roll(), selectedState.prevRoll(), partialTick)) {
                logDirectDoABarrelRollStateOnce(selectedState);
            }
            return;
        }

        CameraModCompatibility.DoABarrelRollState fallbackState = deriveDoABarrelRollStateFromCameraSample(cameraSample);
        if (fallbackState != null) {
            if (!hasReceivedCameraModState && !loggedMissingDoABarrelRollState) {
                loggedMissingDoABarrelRollState = true;
                Flashback.LOGGER.warn("Flashback replay has camera-state samples from Do a Barrel Roll but no camera-mod-state samples. Using quaternion-derived DABR roll fallback; old recordings may not reproduce third-person player roll exactly.");
            }
            if (CameraModCompatibility.applyDoABarrelRollState(entity, fallbackState.rolling(), fallbackState.roll(), partialTick)) {
                logQuaternionDoABarrelRollFallbackOnce(fallbackState);
            }
        }
    }

    private static @Nullable SelectedDoABarrelRollState selectDoABarrelRollState(UUID entityUuid, float partialTick) {
        if (samples.isEmpty()) {
            return null;
        }

        double sampleTime = CameraStateHandler.currentReplaySampleTime(partialTick);
        FlashbackCameraModState.Sample floor = null;
        FlashbackCameraModState.Sample ceil = null;

        for (FlashbackCameraModState.Sample sample : samples) {
            if (!entityUuid.equals(sample.entityUuid())) {
                continue;
            }
            if ((sample.compatibilityFlags() & CameraModCompatibility.FLAG_DO_A_BARREL_ROLL) == 0) {
                continue;
            }
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
            return null;
        }

        double age = Math.min(Math.abs(sampleTime - floor.sampleTime()), Math.abs(ceil.sampleTime() - sampleTime));
        double gap = Math.abs(ceil.sampleTime() - floor.sampleTime());
        logSelectedSampleTiming(sampleTime, age, gap);

        if (floor == ceil || floor.sampleTime() == ceil.sampleTime()) {
            return new SelectedDoABarrelRollState(floor.doABarrelRollRolling(), floor.doABarrelRollRoll(), floor.doABarrelRollPrevRoll(), age, gap);
        }

        float amount = Mth.clamp((float)((sampleTime - floor.sampleTime()) / (ceil.sampleTime() - floor.sampleTime())), 0.0f, 1.0f);
        boolean rolling = amount < 0.5f ? floor.doABarrelRollRolling() : ceil.doABarrelRollRolling();
        float roll = Mth.rotLerp(amount, floor.doABarrelRollRoll(), ceil.doABarrelRollRoll());
        float prevRoll = Mth.rotLerp(amount, floor.doABarrelRollPrevRoll(), ceil.doABarrelRollPrevRoll());
        return new SelectedDoABarrelRollState(rolling, rolling ? roll : 0.0f, rolling ? prevRoll : 0.0f, age, gap);
    }

    private static @Nullable CameraModCompatibility.DoABarrelRollState deriveDoABarrelRollStateFromCameraSample(FlashbackCameraState.Sample cameraSample) {
        if ((cameraSample.compatibilityFlags() & CameraModCompatibility.FLAG_DO_A_BARREL_ROLL) == 0) {
            return null;
        }

        Vector3f euler = new Quaternionf(cameraSample.qx(), cameraSample.qy(), cameraSample.qz(), cameraSample.qw())
            .getEulerAnglesYXZ(new Vector3f());
        float roll = Mth.wrapDegrees((float)-Math.toDegrees(euler.z));
        if (!Float.isFinite(roll)) {
            return null;
        }

        return new CameraModCompatibility.DoABarrelRollState(Math.abs(roll) > 0.1f, roll, roll);
    }

    private static void pruneSamples(double sampleTime) {
        samples.removeIf(sample -> sample.sampleTime() < sampleTime - SAMPLE_RETAIN_TICKS);
        while (samples.size() > MAX_RETAINED_SAMPLES) {
            samples.removeFirst();
        }
    }

    private static void reset() {
        samples.clear();
        hasReceivedCameraModState = false;
        loggedDirectDoABarrelRollState = false;
        loggedQuaternionDoABarrelRollFallback = false;
        loggedMissingDoABarrelRollState = false;
        lastReplaySampleTime = Double.NaN;
        lastTimingWarningTime = Double.NEGATIVE_INFINITY;
    }

    private static void logIncomingSampleGapWarnings(List<FlashbackCameraModState.Sample> incomingSamples) {
        double maxGap = 0.0;
        FlashbackCameraModState.Sample previous = null;
        for (FlashbackCameraModState.Sample sample : incomingSamples) {
            if (previous != null && sample.entityUuid().equals(previous.entityUuid())) {
                maxGap = Math.max(maxGap, sample.sampleTime() - previous.sampleTime());
            }
            previous = sample;
        }

        if (maxGap > 0.75) {
            Flashback.LOGGER.warn("Flashback camera-mod-state stream has a large recorded sample gap: {} ticks", String.format("%.3f", maxGap));
        }
    }

    private static void logSelectedSampleTiming(double sampleTime, double age, double gap) {
        if ((age > 0.75 || gap > 0.75) && sampleTime - lastTimingWarningTime > 20.0) {
            lastTimingWarningTime = sampleTime;
            Flashback.LOGGER.warn(
                "Flashback camera-mod-state playback timing warning: sampleTime={}, selectedAge={} ticks, surroundingGap={} ticks",
                String.format("%.3f", sampleTime),
                String.format("%.3f", age),
                String.format("%.3f", gap)
            );
        }
    }

    private static void logDirectDoABarrelRollStateOnce(SelectedDoABarrelRollState selectedState) {
        if (loggedDirectDoABarrelRollState) {
            return;
        }
        loggedDirectDoABarrelRollState = true;
        Flashback.LOGGER.info(
            "Flashback camera-mod source: DIRECT_DO_A_BARREL_ROLL_STATE. rolling={}, prevRoll={}, roll={}",
            selectedState.rolling(),
            String.format("%.3f", selectedState.prevRoll()),
            String.format("%.3f", selectedState.roll())
        );
    }

    private static void logQuaternionDoABarrelRollFallbackOnce(CameraModCompatibility.DoABarrelRollState fallbackState) {
        if (loggedQuaternionDoABarrelRollFallback) {
            return;
        }
        loggedQuaternionDoABarrelRollFallback = true;
        Flashback.LOGGER.warn(
            "Flashback camera-mod source: QUATERNION_DERIVED_DO_A_BARREL_ROLL_STATE. rolling={}, roll={}",
            fallbackState.rolling(),
            String.format("%.3f", fallbackState.roll())
        );
    }

    public static boolean hasSampleForTime(double sampleTime, double maxDistanceTicks) {
        for (FlashbackCameraModState.Sample sample : samples) {
            if (Math.abs(sample.sampleTime() - sampleTime) <= maxDistanceTicks) {
                return true;
            }
        }
        return false;
    }

    private record SelectedDoABarrelRollState(boolean rolling, float roll, float prevRoll, double age, double gap) {
    }
}
