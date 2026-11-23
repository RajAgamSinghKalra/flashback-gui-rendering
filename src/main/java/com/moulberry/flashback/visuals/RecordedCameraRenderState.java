package com.moulberry.flashback.visuals;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.compat.CameraModCompatibility;
import com.moulberry.flashback.packet.FlashbackCameraState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;

public class RecordedCameraRenderState {

    private static @Nullable Scope activeScope = null;
    private static @Nullable FlashbackCameraState.Sample lastAppliedSample = null;
    private static double lastAppliedSampleTime = Double.NaN;
    private static int cameraEntitySubmitCount = 0;

    public static @Nullable FlashbackCameraState.Sample applyForRenderFrame(float partialTick, String reason) {
        if (!Flashback.isInReplay()) {
            endRenderFrame();
            return null;
        }

        FlashbackCameraState.Sample sample = CameraStateHandler.getOwnershipSample(partialTick);
        if (sample == null) {
            endRenderFrame();
            return null;
        }

        Minecraft minecraft = Minecraft.getInstance();
        CameraStateHandler.applyCameraType(sample);
        Entity entity = CameraStateHandler.applyCameraEntity(sample);
        if (entity == null) {
            return sample;
        }

        if (!Double.isFinite(lastAppliedSampleTime)) {
            CameraModCompatibility.resetRealCameraSmoothing("first recorded camera sample");
        } else if (Math.abs(sample.sampleTime() - lastAppliedSampleTime) > 2.0) {
            CameraModCompatibility.resetRealCameraSmoothing("camera sample jump from " + String.format("%.3f", lastAppliedSampleTime) + " to " + String.format("%.3f", sample.sampleTime()));
        }
        lastAppliedSampleTime = sample.sampleTime();
        lastAppliedSample = sample;

        if (activeScope == null || activeScope.entity != entity) {
            endRenderFrame();
            activeScope = new Scope(entity);
        }

        CameraStateHandler.applyRecordedEntityPose(sample, entity);
        CameraModStateHandler.applyForCameraEntity(partialTick, sample);
        if (minecraft.getCameraEntity() != entity) {
            minecraft.setCameraEntity(entity);
        }
        return sample;
    }

    public static void beginEntityExtraction(float partialTick) {
        cameraEntitySubmitCount = 0;
        applyForRenderFrame(partialTick, "LevelExtractor.extract");
    }

    public static void endRenderFrame() {
        if (activeScope != null) {
            activeScope.restore();
            activeScope = null;
        }
        cameraEntitySubmitCount = 0;
    }

    public static @Nullable Entity getEffectiveCameraEntity() {
        if (activeScope != null && !activeScope.entity.isRemoved()) {
            return activeScope.entity;
        }
        return Minecraft.getInstance().getCameraEntity();
    }

    public static boolean shouldSubmitEntityRenderState(EntityRenderState renderState) {
        if (!Flashback.isInReplay()) {
            return true;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.options.getCameraType().isFirstPerson()) {
            return true;
        }

        Entity cameraEntity = getEffectiveCameraEntity();
        if (cameraEntity == null) {
            return true;
        }

        if (!(renderState instanceof AvatarRenderState avatarRenderState) || avatarRenderState.id != cameraEntity.getId()) {
            return true;
        }

        if (CameraModCompatibility.isRealCameraRendering()) {
            return false;
        }

        if (CameraModCompatibility.isFirstPersonModelCameraRenderState(renderState)) {
            cameraEntitySubmitCount += 1;
            return cameraEntitySubmitCount == 1;
        }

        return false;
    }

    public static boolean hasRecordedCameraSampleNear(double sampleTime, double maxDistanceTicks) {
        return CameraStateHandler.hasSampleForTime(sampleTime, maxDistanceTicks);
    }

    public static @Nullable FlashbackCameraState.Sample getLastAppliedSample() {
        return lastAppliedSample;
    }

    public static void reset() {
        endRenderFrame();
        lastAppliedSample = null;
        lastAppliedSampleTime = Double.NaN;
        cameraEntitySubmitCount = 0;
    }

    private static class Scope {
        private final Entity entity;
        private final double x;
        private final double y;
        private final double z;
        private final double xo;
        private final double yo;
        private final double zo;
        private final double xOld;
        private final double yOld;
        private final double zOld;
        private final float yRot;
        private final float xRot;
        private final float yRotO;
        private final float xRotO;
        private final boolean living;
        private final float yHeadRot;
        private final float yHeadRotO;
        private final float yBodyRot;
        private final float yBodyRotO;
        private final int lerpHeadSteps;

        private Scope(Entity entity) {
            this.entity = entity;
            this.x = entity.getX();
            this.y = entity.getY();
            this.z = entity.getZ();
            this.xo = entity.xo;
            this.yo = entity.yo;
            this.zo = entity.zo;
            this.xOld = entity.xOld;
            this.yOld = entity.yOld;
            this.zOld = entity.zOld;
            this.yRot = entity.getYRot();
            this.xRot = entity.getXRot();
            this.yRotO = entity.yRotO;
            this.xRotO = entity.xRotO;
            if (entity instanceof LivingEntity livingEntity) {
                this.living = true;
                this.yHeadRot = livingEntity.yHeadRot;
                this.yHeadRotO = livingEntity.yHeadRotO;
                this.yBodyRot = livingEntity.yBodyRot;
                this.yBodyRotO = livingEntity.yBodyRotO;
                this.lerpHeadSteps = livingEntity.lerpHeadSteps;
            } else {
                this.living = false;
                this.yHeadRot = 0.0f;
                this.yHeadRotO = 0.0f;
                this.yBodyRot = 0.0f;
                this.yBodyRotO = 0.0f;
                this.lerpHeadSteps = 0;
            }
        }

        private void restore() {
            if (this.entity.isRemoved()) {
                return;
            }

            this.entity.snapTo(this.x, this.y, this.z, this.yRot, this.xRot);
            this.entity.xo = this.xo;
            this.entity.yo = this.yo;
            this.entity.zo = this.zo;
            this.entity.xOld = this.xOld;
            this.entity.yOld = this.yOld;
            this.entity.zOld = this.zOld;
            this.entity.yRotO = this.yRotO;
            this.entity.xRotO = this.xRotO;
            this.entity.setYRot(this.yRot);
            this.entity.setXRot(this.xRot);
            if (this.living && this.entity instanceof LivingEntity livingEntity) {
                livingEntity.setYHeadRot(this.yHeadRot);
                livingEntity.setYBodyRot(this.yBodyRot);
                livingEntity.yHeadRotO = this.yHeadRotO;
                livingEntity.yBodyRotO = this.yBodyRotO;
                livingEntity.lerpHeadSteps = this.lerpHeadSteps;
            }
        }
    }
}
