package com.moulberry.flashback.mixin.playback;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.RenderSystem;
import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.IrisShaderCompatibility;
import com.moulberry.flashback.combo_options.ExportProjection;
import com.moulberry.flashback.exporting.ExportJob;
import com.moulberry.flashback.ext.MinecraftExt;
import com.moulberry.flashback.ext.ProjectionExt;
import com.moulberry.flashback.packet.FlashbackCameraState;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.EditorStateManager;
import com.moulberry.flashback.visuals.AccurateEntityPositionHandler;
import com.moulberry.flashback.visuals.CameraRotation;
import com.moulberry.flashback.visuals.CameraStateHandler;
import com.moulberry.flashback.visuals.RecordedCameraRenderState;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector2f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Objects;

@Mixin(value = Camera.class, priority = 1100)
public abstract class MixinCamera {

    @Shadow
    private float eyeHeightOld;

    @Shadow
    public float eyeHeight;

    @Shadow
    protected abstract void setRotation(float f, float g);

    @Shadow
    protected abstract void setPosition(double d, double e, double f);

    @Shadow
    private @Nullable Entity entity;

    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    private int matrixPropertiesDirty;

    @Shadow
    protected abstract void setupOrtho(float zNear, float zFar, float width, float height, boolean invertY);

    @Shadow
    private float depthFar;

    @Shadow
    @Final
    private net.minecraft.client.renderer.Projection projection;

    @Shadow
    private float fov;

    @Shadow
    private Vec3 position;

    @Shadow
    @Final
    private Vector3f forwards;

    @Shadow
    private boolean isPanoramicMode;

    @Inject(method = "update", at = @At(value = "HEAD"))
    public void flashback$applyRecordedCameraOwnershipBeforeUpdate(DeltaTracker deltaTracker, CallbackInfo ci) {
        if (!Flashback.isInReplay()) {
            return;
        }

        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);
        FlashbackCameraState.Sample cameraStateSample = RecordedCameraRenderState.applyForRenderFrame(partialTick, "Camera.update");
        if (cameraStateSample != null) {
            Entity sampleCameraEntity = CameraStateHandler.applyCameraEntity(cameraStateSample);
            if (sampleCameraEntity != null) {
                this.entity = sampleCameraEntity;
            }
        }
    }

    @Inject(method = "update", at = @At(value = "RETURN"))
    public void afterSetPosition(DeltaTracker deltaTracker, CallbackInfo ci)  {
        if (this.entity == null) {
            return;
        }

        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);
        if (IrisShaderCompatibility.shouldAllowCameraOverrides("post Camera.update pose replacement")) {
            Vector2f rotation = AccurateEntityPositionHandler.getAccurateRotation(this.entity, partialTick);
            if (rotation != null) {
                this.setRotation(rotation.y, rotation.x);
            }
            Vector3d position = AccurateEntityPositionHandler.getAccuratePosition(this.entity, partialTick);
            if (position != null) {
                this.setPosition(position.x, position.y + Mth.lerp(partialTick, this.eyeHeightOld, this.eyeHeight), position.z);
            }

            FlashbackCameraState.Sample cameraStateSample = CameraStateHandler.getPoseSample(partialTick);
            if (cameraStateSample != null) {
                Entity sampleCameraEntity = CameraStateHandler.applyCameraEntity(cameraStateSample);
                if (sampleCameraEntity != null) {
                    this.entity = sampleCameraEntity;
                }
                CameraStateHandler.applyCameraType(cameraStateSample);
                this.setPosition(cameraStateSample.x(), cameraStateSample.y(), cameraStateSample.z());
                CameraStateHandler.setActiveSample(cameraStateSample);
                this.matrixPropertiesDirty |= 3;
            } else {
                CameraStateHandler.setActiveSample(null);
            }
            this.matrixPropertiesDirty |= 3;
        } else {
            CameraStateHandler.setActiveSample(null);
        }

        if (Flashback.RECORDER != null) {
            Flashback.RECORDER.trackCameraState((Camera)(Object)this, partialTick);
        }
    }

    @WrapMethod(method = "getCameraEntityPartialTicks")
    public float getCameraEntityPartialTicks(DeltaTracker deltaTracker, Operation<Float> original) {
        float originalPartialTick = original.call(deltaTracker);
        if (Flashback.isInReplay() && this.entity == this.minecraft.player) {
            return ((MinecraftExt)this.minecraft).flashback$getLocalPlayerPartialTick(originalPartialTick);
        }
        return originalPartialTick;
    }

    @WrapMethod(method = "rotation")
    public Quaternionf rotation(Operation<Quaternionf> original) {
        FlashbackCameraState.Sample cameraStateSample = CameraStateHandler.getActiveSample();
        if (cameraStateSample != null) {
            this.matrixPropertiesDirty |= 3;
            return CameraStateHandler.rotationFromSample(cameraStateSample);
        }

        Quaternionf originalQuaternion = original.call();
        if (!IrisShaderCompatibility.shouldAllowCameraOverrides("camera roll/shake quaternion override")) {
            return originalQuaternion;
        }
        Quaternionf modifiedQuaternion = CameraRotation.modifyViewQuaternion(originalQuaternion);
        if (!Objects.equals(originalQuaternion, modifiedQuaternion)) {
            this.matrixPropertiesDirty |= 3;
        }
        return modifiedQuaternion;
    }

    @WrapMethod(method = "calculateFov")
    public float calculateFov(float partialTicks, Operation<Float> original) {
        if (!IrisShaderCompatibility.shouldAllowCameraOverrides("camera FOV override")) {
            return original.call(partialTicks);
        }

        FlashbackCameraState.Sample cameraStateSample = CameraStateHandler.getPoseSample(partialTicks);
        if (cameraStateSample != null) {
            return cameraStateSample.fov();
        }

        if (Flashback.isInReplay()) {
            if (this.isPanoramicMode) {
                return 90.0f;
            }

            EditorState editorState = EditorStateManager.getCurrent();
            if (editorState != null && editorState.replayVisuals.overrideFov) {
                return editorState.replayVisuals.overrideFovAmount;
            } else {
                return this.minecraft.options.fov().get().intValue();
            }
        }

        return original.call(partialTicks);
    }

    @WrapOperation(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setupPerspective(FFFFF)V"))
    public void update_setupPerspective(Camera instance, float zNear, float zFar, float fov, float width, float height, Operation<Void> original) {
        ExportJob exportJob = Flashback.EXPORT_JOB;
        if (exportJob != null) {
            ExportProjection projection = exportJob.getEffectiveProjection();
            if (!IrisShaderCompatibility.shouldAllowCameraOverrides("export projection override")) {
                original.call(instance, zNear, zFar, fov, width, height);
                return;
            }
            if (projection == ExportProjection.ORTHOGRAPHIC) {
                float orthoHeight = (float)(Math.tan(Math.toRadians(fov/2)) * zFar / 4f) / exportJob.getSettings().orthographicZoom();
                float orthoWidth = width / height * orthoHeight;

                this.setupOrtho(-zFar, zFar, orthoWidth, orthoHeight, false);
                ((ProjectionExt)this.projection).flashback$setCenteredOrtho(true);
                return;
            } else if (projection == ExportProjection.CUBE_MAP || projection == ExportProjection.EQUIRECTANGULAR) {
                width = Math.max(width, height);
                height = Math.max(width, height);
            }
        }

        original.call(instance, zNear, zFar, fov, width, height);
    }

    @Inject(method = "createProjectionMatrixForCulling", at = @At("HEAD"), cancellable = true)
    public void createProjectionMatrixForCulling(CallbackInfoReturnable<Matrix4f> cir) {
        ExportJob exportJob = Flashback.EXPORT_JOB;
        if (exportJob != null && exportJob.getEffectiveProjection() == ExportProjection.ORTHOGRAPHIC &&
            IrisShaderCompatibility.shouldAllowCameraOverrides("orthographic culling projection override")) {
            Matrix4f projection = new Matrix4f();

            float fovForCulling = Math.max(this.fov, this.minecraft.options.fov().get().intValue());

            float width = this.minecraft.getWindow().getWidth();
            float height = this.minecraft.getWindow().getHeight();
            float orthoHeight = (float)(Math.tan(Math.toRadians(fovForCulling/2)) * this.depthFar / 4f) / exportJob.getSettings().orthographicZoom();
            float orthoWidth = width / height * orthoHeight;

            projection.setOrtho(
                -orthoWidth/2,
                orthoWidth/2,
                -orthoHeight/2,
                orthoHeight/2,
                -this.depthFar,
                this.depthFar,
                RenderSystem.getDevice().getDeviceInfo().isZZeroToOne()
            );
            cir.setReturnValue(projection);
        }
    }

}
