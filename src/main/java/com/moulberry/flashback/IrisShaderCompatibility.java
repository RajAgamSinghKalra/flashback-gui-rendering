package com.moulberry.flashback;

import com.moulberry.flashback.combo_options.ExportProjection;
import com.moulberry.flashback.compat.IrisApiWrapper;
import com.moulberry.flashback.configuration.FlashbackConfigV1;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

public class IrisShaderCompatibility {

    private static final long SHADER_STATE_REFRESH_NANOS = 250_000_000L;

    private static Boolean cachedShaderPackInUse = null;
    private static long nextShaderStateRefreshNanos = Long.MIN_VALUE;
    private static boolean loggedIsolationSession = false;
    private static boolean loggedApiFailure = false;
    private static final Set<String> LOGGED_HOOKS = new LinkedHashSet<>();

    public static boolean isActive() {
        return isIsolationEnabled() && isShaderPackInUse();
    }

    public static boolean isShaderPackInUse() {
        if (!Flashback.isIrisLoaded()) {
            cachedShaderPackInUse = false;
            return false;
        }

        long now = System.nanoTime();
        Boolean cached = cachedShaderPackInUse;
        if (cached != null && now < nextShaderStateRefreshNanos) {
            return cached;
        }

        synchronized (IrisShaderCompatibility.class) {
            cached = cachedShaderPackInUse;
            if (cached != null && now < nextShaderStateRefreshNanos) {
                return cached;
            }

            boolean shaderPackInUse = false;
            try {
                shaderPackInUse = IrisApiWrapper.isShaderPackInUse();
            } catch (Throwable throwable) {
                if (!loggedApiFailure) {
                    loggedApiFailure = true;
                    Flashback.LOGGER.warn("Unable to query Iris shaderpack runtime state through Iris API; falling back to iris.properties.", throwable);
                }
                shaderPackInUse = Flashback.areIrisShadersEnabled();
            }

            cachedShaderPackInUse = shaderPackInUse;
            nextShaderStateRefreshNanos = now + SHADER_STATE_REFRESH_NANOS;
            return shaderPackInUse;
        }
    }

    public static boolean shouldAllowWorldRenderingMutations(String hook) {
        if (!isActive()) {
            return true;
        }
        logSuppressed(hook, "Iris shader isolation lets Iris own world render targets, terrain phases, depth, sky/cloud passes, and shader uniforms.");
        return false;
    }

    public static boolean shouldAllowCameraOverrides(String hook) {
        if (!isActive()) {
            return true;
        }
        logSuppressed(hook, "Iris shader isolation keeps camera/projection state in the vanilla/Iris path for terrain tessellation and shader uniforms.");
        return false;
    }

    public static boolean shouldAllowRecordedUiOverlayCapture(String hook) {
        if (!isActive()) {
            return true;
        }
        FlashbackConfigV1 config = Flashback.getConfig();
        if (config != null && config.advanced.disableUiOverlayCaptureWithIrisShaders) {
            logSuppressed(hook, "UI overlay capture with Iris shaders is disabled by advanced config.");
            return false;
        }
        logAllowed(hook, "Recorded UI capture is limited to transparent GUI-only targets and does not touch Iris terrain/framegraph render targets.");
        return true;
    }

    public static boolean shouldAllowRecordedUiOverlayPreview(String hook) {
        if (!isActive()) {
            return true;
        }
        logAllowed(hook, "Recorded UI overlay preview uses the conservative CPU texture path while shaders are active.");
        return true;
    }

    public static boolean shouldAllowRecordedUiOverlayExport(String hook) {
        if (!isActive()) {
            return true;
        }
        logAllowed(hook, "Recorded UI overlay is composited on downloaded CPU frames while shaders are active.");
        return true;
    }

    public static boolean shouldAllowRecordedUiLiveGuiSuppression(String hook) {
        if (!isActive()) {
            return true;
        }
        logAllowed(hook, "Live GUI is suppressed only when a recorded screen overlay is visible, then restored from recorded UI pixels.");
        return true;
    }

    public static boolean shouldAllowVisualOverrides(String hook) {
        if (!isActive()) {
            return true;
        }
        logSuppressed(hook, "Iris shader isolation ignores Flashback visual render filters while shaders are active.");
        return false;
    }

    public static ExportProjection effectiveExportProjection(ExportProjection requested) {
        if (isActive() && requested != ExportProjection.PERSPECTIVE) {
            logSuppressed("export projection " + requested, "Iris shader isolation forces perspective exports while shaderpacks are active.");
            return ExportProjection.PERSPECTIVE;
        }
        return requested;
    }

    public static void logDirectEditorUi() {
        if (isActive()) {
            logAllowed("direct editor UI", "Flashback editor controls are rendered directly onto Minecraft's main render target after world rendering.");
        }
    }

    public static void logConservativeExport(ExportProjection requested) {
        if (!isActive()) {
            return;
        }
        logAllowed("conservative shader export", "SSAA, panorama projections, and Flashback world overlays are disabled; recorded UI overlay uses CPU compositing after frame download.");
        if (requested != ExportProjection.PERSPECTIVE) {
            logSuppressed("requested " + requested + " export", "Perspective export is used instead while Iris shaders are active.");
        }
    }

    public static void logStartupDiagnostics() {
        if (!Flashback.isIrisLoaded()) {
            return;
        }
        Properties irisProperties = readIrisProperties();
        Flashback.LOGGER.warn(
            "Flashback Iris compatibility: renderIsolation={}, shaderPackInUse={}, configuredShaderPack='{}', maxShadowRenderDistance={}. " +
                "Flashback terrain/chunk render wrappers are constrained with @IfModAbsent(\"iris\") so Iris owns terrain phases when installed.",
            isIsolationEnabled(),
            isShaderPackInUse(),
            irisProperties.getProperty("shaderPack", "<none>"),
            irisProperties.getProperty("maxShadowRenderDistance", "unknown")
        );
    }

    public static void logIsolationSession(String reason) {
        if (!isActive() || loggedIsolationSession) {
            return;
        }
        loggedIsolationSession = true;
        Properties irisProperties = readIrisProperties();
        Flashback.LOGGER.error(
            "Flashback Iris shader render isolation ACTIVE for {}. shaderPack='{}', maxShadowRenderDistance={}, hooks=world-render-mutations:blocked, camera-overrides:blocked, ui-overlay:cpu-safe, export-extras:blocked, editor-ui:direct-main-target",
            reason,
            irisProperties.getProperty("shaderPack", "<none>"),
            irisProperties.getProperty("maxShadowRenderDistance", "unknown")
        );
    }

    public static void logSuppressed(String hook, String reason) {
        if (!isActive()) {
            return;
        }
        logIsolationSession(hook);
        if (!shouldLogHooks()) {
            return;
        }
        String key = "suppressed:" + hook.toLowerCase(Locale.ROOT);
        if (LOGGED_HOOKS.add(key)) {
            Flashback.LOGGER.warn("Flashback Iris shader isolation suppressed {}: {}", hook, reason);
        }
    }

    public static void logAllowed(String hook, String reason) {
        if (!isActive() || !shouldLogHooks()) {
            return;
        }
        logIsolationSession(hook);
        String key = "allowed:" + hook.toLowerCase(Locale.ROOT);
        if (LOGGED_HOOKS.add(key)) {
            Flashback.LOGGER.warn("Flashback Iris shader isolation allowed {}: {}", hook, reason);
        }
    }

    private static boolean isIsolationEnabled() {
        FlashbackConfigV1 config = Flashback.getConfig();
        return config == null || config.advanced.irisShaderRenderIsolation;
    }

    private static boolean shouldLogHooks() {
        FlashbackConfigV1 config = Flashback.getConfig();
        return config == null || config.advanced.logIrisShaderHooks;
    }

    private static Properties readIrisProperties() {
        Properties properties = new Properties();
        Path path = FabricLoader.getInstance().getConfigDir().resolve("iris.properties");
        if (!Files.exists(path)) {
            return properties;
        }
        try (InputStream inputStream = Files.newInputStream(path)) {
            properties.load(inputStream);
        } catch (IOException e) {
            Flashback.LOGGER.warn("Unable to read Iris properties at {}", path, e);
        }
        return properties;
    }
}
