package com.moulberry.flashback;

import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Properties;

public class IrisAmdTdrSafeMode {

    private static final String MARKER_FILE_NAME = "iris_amd_shader_session.properties";
    private static boolean loggedActive = false;
    private static boolean loggedEditorOverlayDisabled = false;
    private static boolean loggedDirectEditorOverlay = false;
    private static boolean loggedUiOverlayPreviewCpuMode = false;
    private static boolean loggedExportConservativeMode = false;
    private static boolean wroteSessionMarker = false;

    public static boolean isActive() {
        if (Flashback.getConfig().advanced.disableIrisAmdTdrSafeMode) {
            return false;
        }
        return Flashback.isIrisLoaded() && IrisShaderCompatibility.isShaderPackInUse() && isAmdOpenGl();
    }

    public static void handlePreviousUncleanShutdown() {
        Path marker = markerPath();
        if (!Files.exists(marker)) {
            return;
        }

        try {
            Path irisConfig = FabricLoader.getInstance().getConfigDir().resolve("iris.properties");
            Properties irisProperties = readProperties(irisConfig);
            boolean shadersEnabled = "true".equalsIgnoreCase(irisProperties.getProperty("enableShaders", "false"));
            if (shadersEnabled) {
                Path backup = irisConfig.resolveSibling("iris.properties.flashback-tdr-" + timestamp() + ".bak");
                Files.copy(irisConfig, backup);
                irisProperties.setProperty("enableShaders", "false");
                try (OutputStream outputStream = Files.newOutputStream(irisConfig)) {
                    irisProperties.store(outputStream, "Flashback disabled Iris shaders after an unclean AMD/Iris shader session");
                }
                Flashback.LOGGER.error(
                    "Flashback found an unclean AMD/Iris shader session marker and disabled Iris shaders to prevent another driver timeout. Backup: {}",
                    backup
                );
            } else {
                Flashback.LOGGER.warn("Flashback found an unclean AMD/Iris shader session marker, but Iris shaders are already disabled.");
            }
            Files.deleteIfExists(marker);
        } catch (Exception e) {
            Flashback.LOGGER.error("Unable to process Flashback AMD/Iris shader session marker at {}", marker, e);
        }
    }

    public static void beginRiskSessionIfNeeded(String reason) {
        if (!isActive()) {
            return;
        }
        logActive(reason);
        if (wroteSessionMarker) {
            return;
        }

        try {
            Files.createDirectories(Flashback.getConfigDirectory());
            Properties properties = new Properties();
            properties.setProperty("startedAt", LocalDateTime.now().toString());
            properties.setProperty("reason", reason);
            properties.setProperty("gpu", gpuInfo());

            Properties irisProperties = readIrisProperties();
            properties.setProperty("shaderPack", irisProperties.getProperty("shaderPack", "<none>"));
            properties.setProperty("maxShadowRenderDistance", irisProperties.getProperty("maxShadowRenderDistance", "unknown"));

            try (OutputStream outputStream = Files.newOutputStream(markerPath())) {
                properties.store(outputStream, "Flashback AMD/Iris shader session marker; removed on clean shutdown");
            }
            wroteSessionMarker = true;
        } catch (Exception e) {
            Flashback.LOGGER.warn("Unable to write Flashback AMD/Iris shader session marker", e);
        }
    }

    public static void markCleanShutdown() {
        try {
            Files.deleteIfExists(markerPath());
            wroteSessionMarker = false;
        } catch (IOException e) {
            Flashback.LOGGER.warn("Unable to clear Flashback AMD/Iris shader session marker", e);
        }
    }

    public static void logEditorOverlayDisabled() {
        beginRiskSessionIfNeeded("live replay editor overlay");
        if (!loggedEditorOverlayDisabled) {
            loggedEditorOverlayDisabled = true;
            Flashback.LOGGER.warn("Flashback AMD/Iris safety mode is hiding the live replay editor overlay while shaders are enabled to avoid a display-driver timeout.");
        }
    }

    public static void logDirectEditorOverlay() {
        beginRiskSessionIfNeeded("direct replay editor overlay");
        if (!loggedDirectEditorOverlay) {
            loggedDirectEditorOverlay = true;
            Flashback.LOGGER.warn("Flashback AMD/Iris safety mode is rendering the replay editor UI directly into Minecraft's main render target; offscreen editor compositing and platform windows are disabled while shaders are enabled.");
        }
    }

    public static void logUiOverlayPreviewCpuMode() {
        beginRiskSessionIfNeeded("recorded UI overlay preview");
        if (!loggedUiOverlayPreviewCpuMode) {
            loggedUiOverlayPreviewCpuMode = true;
            Flashback.LOGGER.warn("Flashback AMD/Iris safety mode is showing recorded UI overlay preview through the conservative CPU texture path while shaders are enabled.");
        }
    }

    public static void logExportConservativeMode() {
        beginRiskSessionIfNeeded("shader export");
        if (!loggedExportConservativeMode) {
            loggedExportConservativeMode = true;
            Flashback.LOGGER.warn("Flashback AMD/Iris safety mode is exporting conservatively: SSAA is disabled and recorded UI overlay is composited on the CPU after frame download.");
        }
    }

    private static void logActive(String reason) {
        if (loggedActive) {
            return;
        }
        loggedActive = true;

        Minecraft minecraft = Minecraft.getInstance();
        Properties irisProperties = readIrisProperties();

        Flashback.LOGGER.error(
            "Flashback AMD/Iris TDR safety mode ACTIVE for {}. shaderPack='{}', maxShadowRenderDistance={}, gpu='{}', fullscreen={}, exclusiveFullscreen={}, maxFps={}, renderDistance={}, simulationDistance={}",
            reason,
            irisProperties.getProperty("shaderPack", "<none>"),
            irisProperties.getProperty("maxShadowRenderDistance", "unknown"),
            gpuInfo(),
            minecraft.options.fullscreen().get(),
            minecraft.options.exclusiveFullscreen().get(),
            minecraft.options.framerateLimit().get(),
            minecraft.options.renderDistance().get(),
            minecraft.options.simulationDistance().get()
        );
    }

    private static boolean isAmdOpenGl() {
        String gpuInfo = gpuInfo().toLowerCase(Locale.ROOT);
        boolean amd = gpuInfo.contains("amd") || gpuInfo.contains("ati") || gpuInfo.contains("radeon");
        boolean opengl = gpuInfo.contains("opengl");
        return amd && opengl;
    }

    private static String gpuInfo() {
        try {
            DeviceInfo deviceInfo = RenderSystem.getDevice().getDeviceInfo();
            return deviceInfo.vendorName() + " / " + deviceInfo.name() + " / " + deviceInfo.backendName() + " / " + deviceInfo.driverInfo();
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    private static Properties readIrisProperties() {
        return readProperties(FabricLoader.getInstance().getConfigDir().resolve("iris.properties"));
    }

    private static Properties readProperties(Path path) {
        Properties properties = new Properties();
        if (!Files.exists(path)) {
            return properties;
        }
        try (InputStream inputStream = Files.newInputStream(path)) {
            properties.load(inputStream);
        } catch (IOException e) {
            Flashback.LOGGER.warn("Unable to read properties from {}", path, e);
        }
        return properties;
    }

    private static Path markerPath() {
        return Flashback.getConfigDirectory().resolve(MARKER_FILE_NAME);
    }

    private static String timestamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
    }
}
