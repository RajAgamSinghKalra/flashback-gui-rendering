package com.moulberry.flashback.compat;

import com.moulberry.flashback.Flashback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class CameraModCompatibility {

    public static final int FLAG_DO_A_BARREL_ROLL = 1;
    public static final int FLAG_FIRST_PERSON_MODEL = 1 << 1;
    public static final int FLAG_REAL_CAMERA = 1 << 2;

    private static boolean loggedLoadedMods = false;
    private static boolean attemptedDoABarrelRollAdapter = false;
    private static boolean loggedDoABarrelRollAdapterFailure = false;
    private static @Nullable Class<?> doABarrelRollEntityClass = null;
    private static @Nullable Method doABarrelRollIsRolling = null;
    private static @Nullable Method doABarrelRollGetRoll = null;
    private static @Nullable Method doABarrelRollGetRollPartial = null;
    private static @Nullable Method doABarrelRollSetRolling = null;
    private static @Nullable Method doABarrelRollSetRoll = null;
    private static boolean attemptedDoABarrelRollFields = false;
    private static @Nullable Field doABarrelRollIsRollingField = null;
    private static @Nullable Field doABarrelRollPrevRollField = null;
    private static @Nullable Field doABarrelRollRollField = null;
    private static boolean attemptedRealCameraAdapter = false;
    private static boolean loggedRealCameraAdapterFailure = false;
    private static @Nullable Class<?> realCameraCoreClass = null;
    private static @Nullable Method realCameraIsActive = null;
    private static @Nullable Method realCameraIsRendering = null;
    private static @Nullable Method realCameraReset = null;
    private static boolean attemptedFirstPersonModelRenderStateAdapter = false;
    private static boolean loggedFirstPersonModelRenderStateFailure = false;
    private static @Nullable Class<?> firstPersonModelRenderStateAccessClass = null;
    private static @Nullable Method firstPersonModelIsCameraEntity = null;

    public record DoABarrelRollState(boolean rolling, float roll, float prevRoll) {
    }

    public static boolean isDoABarrelRollLoaded() {
        return FabricLoader.getInstance().isModLoaded("do_a_barrel_roll");
    }

    public static boolean isFirstPersonModelLoaded() {
        return FabricLoader.getInstance().isModLoaded("firstperson");
    }

    public static boolean isRealCameraLoaded() {
        return FabricLoader.getInstance().isModLoaded("realcamera");
    }

    public static boolean hasKnownCameraModLoaded() {
        return isDoABarrelRollLoaded() || isFirstPersonModelLoaded() || isRealCameraLoaded();
    }

    public static boolean shouldDisableFlashbackHandSubstitution() {
        return isFirstPersonModelLoaded() || isRealCameraLoaded();
    }

    public static boolean hasDoABarrelRollState(@Nullable Entity entity) {
        return entity != null && isDoABarrelRollLoaded() && ensureDoABarrelRollAdapter() &&
            doABarrelRollEntityClass != null && doABarrelRollEntityClass.isInstance(entity);
    }

    public static @Nullable DoABarrelRollState readDoABarrelRollState(@Nullable Entity entity) {
        if (!hasDoABarrelRollState(entity)) {
            return null;
        }

        try {
            boolean rolling = (boolean) doABarrelRollIsRolling.invoke(entity);
            float roll = (float) doABarrelRollGetRoll.invoke(entity);
            float prevRoll = doABarrelRollGetRollPartial == null ? roll : (float) doABarrelRollGetRollPartial.invoke(entity, 0.0f);
            return new DoABarrelRollState(rolling, Mth.wrapDegrees(roll), Mth.wrapDegrees(prevRoll));
        } catch (Throwable throwable) {
            logDoABarrelRollAdapterFailure("read", throwable);
            return null;
        }
    }

    public static boolean applyDoABarrelRollState(@Nullable Entity entity, boolean rolling, float roll) {
        return applyDoABarrelRollState(entity, rolling, roll, 1.0f);
    }

    public static boolean applyDoABarrelRollState(@Nullable Entity entity, boolean rolling, float roll, float partialTick) {
        return applyDoABarrelRollState(entity, rolling, roll, Float.NaN, partialTick);
    }

    public static boolean applyDoABarrelRollState(@Nullable Entity entity, boolean rolling, float roll, float prevRoll, float partialTick) {
        if (!hasDoABarrelRollState(entity)) {
            return false;
        }
        if (!Float.isFinite(roll)) {
            return false;
        }

        try {
            if (Float.isFinite(prevRoll) && applyDoABarrelRollFields(entity, rolling, roll, prevRoll)) {
                return true;
            }

            doABarrelRollSetRolling.invoke(entity, rolling);
            doABarrelRollSetRoll.invoke(entity, rolling ? compensatedDoABarrelRollCurrentRoll(entity, roll, partialTick) : 0.0f);
            return true;
        } catch (Throwable throwable) {
            logDoABarrelRollAdapterFailure("apply", throwable);
            return false;
        }
    }

    public static boolean isRealCameraActive() {
        if (!isRealCameraLoaded() || !ensureRealCameraAdapter()) {
            return false;
        }
        try {
            return (boolean) realCameraIsActive.invoke(null);
        } catch (Throwable throwable) {
            logRealCameraAdapterFailure("query active", throwable);
            return false;
        }
    }

    public static boolean isRealCameraRendering() {
        if (!isRealCameraLoaded() || !ensureRealCameraAdapter()) {
            return false;
        }
        try {
            return (boolean) realCameraIsRendering.invoke(null);
        } catch (Throwable throwable) {
            logRealCameraAdapterFailure("query rendering", throwable);
            return false;
        }
    }

    public static void resetRealCameraSmoothing(String reason) {
        if (!isRealCameraLoaded() || !ensureRealCameraAdapter()) {
            return;
        }
        try {
            realCameraReset.invoke(null);
            Flashback.LOGGER.info("Flashback camera-mod compatibility: reset RealCamera smoothing for {}", reason);
        } catch (Throwable throwable) {
            logRealCameraAdapterFailure("reset", throwable);
        }
    }

    public static boolean isFirstPersonModelCameraRenderState(@Nullable Object renderState) {
        if (renderState == null || !isFirstPersonModelLoaded() || !ensureFirstPersonModelRenderStateAdapter()) {
            return false;
        }
        if (!firstPersonModelRenderStateAccessClass.isInstance(renderState)) {
            return false;
        }
        try {
            return (boolean) firstPersonModelIsCameraEntity.invoke(renderState);
        } catch (Throwable throwable) {
            logFirstPersonModelRenderStateFailure("query camera render state", throwable);
            return false;
        }
    }

    public static int currentCompatibilityFlags() {
        int flags = 0;
        if (isDoABarrelRollLoaded()) {
            flags |= FLAG_DO_A_BARREL_ROLL;
        }
        if (isFirstPersonModelLoaded()) {
            flags |= FLAG_FIRST_PERSON_MODEL;
        }
        if (isRealCameraLoaded()) {
            flags |= FLAG_REAL_CAMERA;
        }
        return flags;
    }

    public static String loadedCameraMods() {
        return "do_a_barrel_roll=" + isDoABarrelRollLoaded() +
            ", firstperson=" + isFirstPersonModelLoaded() +
            ", realcamera=" + isRealCameraLoaded();
    }

    public static void logLoadedModsOnce(String reason) {
        if (!hasKnownCameraModLoaded() || loggedLoadedMods) {
            return;
        }
        loggedLoadedMods = true;
        Flashback.LOGGER.info("Flashback camera-mod compatibility active for {}. {}", reason, loadedCameraMods());
    }

    public static void resetLogging() {
        loggedLoadedMods = false;
    }

    private static boolean ensureDoABarrelRollAdapter() {
        if (attemptedDoABarrelRollAdapter) {
            return doABarrelRollEntityClass != null;
        }
        attemptedDoABarrelRollAdapter = true;

        try {
            ClassLoader classLoader = CameraModCompatibility.class.getClassLoader();
            doABarrelRollEntityClass = Class.forName("nl.enjarai.doabarrelroll.api.RollEntity", false, classLoader);
            doABarrelRollIsRolling = doABarrelRollEntityClass.getMethod("doABarrelRoll$isRolling");
            doABarrelRollGetRoll = doABarrelRollEntityClass.getMethod("doABarrelRoll$getRoll");
            doABarrelRollGetRollPartial = doABarrelRollEntityClass.getMethod("doABarrelRoll$getRoll", float.class);
            doABarrelRollSetRolling = doABarrelRollEntityClass.getMethod("doABarrelRoll$setRolling", boolean.class);
            doABarrelRollSetRoll = doABarrelRollEntityClass.getMethod("doABarrelRoll$setRoll", float.class);
            Flashback.LOGGER.info("Flashback camera-mod compatibility: Do a Barrel Roll RollEntity adapter initialized");
            return true;
        } catch (Throwable throwable) {
            doABarrelRollEntityClass = null;
            doABarrelRollIsRolling = null;
            doABarrelRollGetRoll = null;
            doABarrelRollGetRollPartial = null;
            doABarrelRollSetRolling = null;
            doABarrelRollSetRoll = null;
            logDoABarrelRollAdapterFailure("initialize", throwable);
            return false;
        }
    }

    private static boolean applyDoABarrelRollFields(Entity entity, boolean rolling, float roll, float prevRoll) throws ReflectiveOperationException {
        if (!ensureDoABarrelRollFields(entity)) {
            return false;
        }

        boolean actualRolling = rolling && Float.isFinite(roll);
        doABarrelRollIsRollingField.setBoolean(entity, actualRolling);
        doABarrelRollPrevRollField.setFloat(entity, actualRolling ? Mth.wrapDegrees(prevRoll) : 0.0f);
        doABarrelRollRollField.setFloat(entity, actualRolling ? Mth.wrapDegrees(roll) : 0.0f);
        return true;
    }

    private static boolean ensureDoABarrelRollFields(Entity entity) {
        if (attemptedDoABarrelRollFields) {
            return doABarrelRollIsRollingField != null && doABarrelRollPrevRollField != null && doABarrelRollRollField != null;
        }
        attemptedDoABarrelRollFields = true;

        Class<?> clazz = entity.getClass();
        while (clazz != null && clazz != Object.class) {
            for (Field field : clazz.getDeclaredFields()) {
                String name = field.getName();
                if (doABarrelRollIsRollingField == null && (name.equals("isRolling") || name.endsWith("$isRolling"))) {
                    field.setAccessible(true);
                    doABarrelRollIsRollingField = field;
                } else if (doABarrelRollPrevRollField == null && (name.equals("prevRoll") || name.endsWith("$prevRoll"))) {
                    field.setAccessible(true);
                    doABarrelRollPrevRollField = field;
                } else if (doABarrelRollRollField == null && (name.equals("roll") || name.endsWith("$roll"))) {
                    field.setAccessible(true);
                    doABarrelRollRollField = field;
                }
            }
            clazz = clazz.getSuperclass();
        }

        return doABarrelRollIsRollingField != null && doABarrelRollPrevRollField != null && doABarrelRollRollField != null;
    }

    private static boolean ensureRealCameraAdapter() {
        if (attemptedRealCameraAdapter) {
            return realCameraCoreClass != null;
        }
        attemptedRealCameraAdapter = true;

        try {
            ClassLoader classLoader = CameraModCompatibility.class.getClassLoader();
            realCameraCoreClass = Class.forName("com.xtracr.realcamera.RealCameraCore", false, classLoader);
            realCameraIsActive = realCameraCoreClass.getMethod("isActive");
            realCameraIsRendering = realCameraCoreClass.getMethod("isRendering");
            realCameraReset = realCameraCoreClass.getMethod("reset");
            return true;
        } catch (Throwable throwable) {
            realCameraCoreClass = null;
            realCameraIsActive = null;
            realCameraIsRendering = null;
            realCameraReset = null;
            logRealCameraAdapterFailure("initialize", throwable);
            return false;
        }
    }

    private static boolean ensureFirstPersonModelRenderStateAdapter() {
        if (attemptedFirstPersonModelRenderStateAdapter) {
            return firstPersonModelRenderStateAccessClass != null;
        }
        attemptedFirstPersonModelRenderStateAdapter = true;

        try {
            ClassLoader classLoader = CameraModCompatibility.class.getClassLoader();
            firstPersonModelRenderStateAccessClass = Class.forName("dev.tr7zw.firstperson.access.LivingEntityRenderStateAccess", false, classLoader);
            firstPersonModelIsCameraEntity = firstPersonModelRenderStateAccessClass.getMethod("isCameraEntity");
            return true;
        } catch (Throwable throwable) {
            firstPersonModelRenderStateAccessClass = null;
            firstPersonModelIsCameraEntity = null;
            logFirstPersonModelRenderStateFailure("initialize", throwable);
            return false;
        }
    }

    private static void logDoABarrelRollAdapterFailure(String operation, Throwable throwable) {
        if (loggedDoABarrelRollAdapterFailure) {
            return;
        }
        loggedDoABarrelRollAdapterFailure = true;
        Flashback.LOGGER.warn("Flashback camera-mod compatibility: failed to {} Do a Barrel Roll RollEntity adapter", operation, throwable);
    }

    private static void logRealCameraAdapterFailure(String operation, Throwable throwable) {
        if (loggedRealCameraAdapterFailure) {
            return;
        }
        loggedRealCameraAdapterFailure = true;
        Flashback.LOGGER.warn("Flashback camera-mod compatibility: failed to {} RealCamera adapter", operation, throwable);
    }

    private static void logFirstPersonModelRenderStateFailure(String operation, Throwable throwable) {
        if (loggedFirstPersonModelRenderStateFailure) {
            return;
        }
        loggedFirstPersonModelRenderStateFailure = true;
        Flashback.LOGGER.warn("Flashback camera-mod compatibility: failed to {} FirstPersonModel render-state adapter", operation, throwable);
    }

    private static float compensatedDoABarrelRollCurrentRoll(Entity entity, float desiredRoll, float partialTick) throws ReflectiveOperationException {
        desiredRoll = Mth.wrapDegrees(desiredRoll);
        float tickDelta = Mth.clamp(partialTick, 0.0f, 1.0f);
        if (tickDelta <= 0.001f || tickDelta >= 0.999f || doABarrelRollGetRollPartial == null) {
            return desiredRoll;
        }

        float previousRoll = (float) doABarrelRollGetRollPartial.invoke(entity, 0.0f);
        float delta = Mth.wrapDegrees(desiredRoll - previousRoll);
        return Mth.wrapDegrees(previousRoll + delta / tickDelta);
    }
}
