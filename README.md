# THIS PROJECT IS VIBECODED

This is an **experimental, AI-assisted, unofficial modification** of [Flashback by Moulberry](https://github.com/Moulberry/Flashback). It needs substantially more testing. Despite the project name, **not every GUI or mod is guaranteed to render correctly**.

# Flashback with All GUI Rendering

**Minecraft 26.3 · Fabric · experimental build**

Record gameplay, replay it, and export video with recorded interfaces such as inventories and containers. You can change shaders and resource packs before exporting. This build adds features to Flashback; it is not an official Flashback release and is not endorsed by its creator.

## Important distribution notice

Flashback is copyright Moulberry. Its [license](LICENSE.md) says **“Do not redistribute.”** This unofficial fork retains that license; anyone wishing to redistribute it should verify the rights holder's permissions. Localization files have a separate CC BY 4.0 license, as described in the license. The original README is preserved in [UPSTREAM-README.md](UPSTREAM-README.md).

## Installation

1. Use Minecraft **26.3**, Fabric Loader **0.19.5**, Fabric API compatible with 26.3, and a supported Java runtime. The isolated test used Fabric API 0.163.0+26.3 and Java 25.
2. Back up your profile and replays. Close Minecraft.
3. Download the JAR from [Releases](https://github.com/RajAgamSinghKalra/flashback-gui-rendering/releases) and put it in the profile's `mods` folder. Remove or disable any other Flashback JAR first: both use the same `flashback` mod ID and cannot be loaded together.
4. Launch the game and enable **Record UI Overlay** in Flashback's recording settings if it is not already enabled. New recordings must contain overlay data for interfaces to appear later.

The release file is an **unofficial build**, not a download from the upstream creator. It does not include resource packs, shader packs or other mods.

## What this build adds

- **Recorded interfaces:** captures GUI layers and cursor states during recording, saves them in the replay, and composites them during preview and export. Inventory, container and pause screens were exercised in local tests. The overlay stores captured pixels, so it is not a fully re-rendered high-resolution interface; dropped or sparse samples can still cause visual differences.
- **Resource packs:** records the selected pack IDs and their order and warns if playback uses a different selection. **File → Select Resource Packs** in the replay editor lets you reload another selection before export. Packs are not embedded or hashed, and changes made during the original recording are not tracked completely.
- **Camera and motion:** records camera and supported camera-mod state, with interpolation for playback. The recorder keeps a camera sample while a paused GUI advances replay time. Export waits briefly for late camera packets and reports missing-sample diagnostics; missing data in older recordings cannot be reconstructed.
- **Export reliability:** improves progress labels, UI capture ordering and memory management. No GUI export skips the GUI draw without skipping Minecraft's per-frame resource cleanup. Export-owned readback resources are closed and counted in the log.
- **Entity animation:** EMF's frame-time value is held near zero during synthetic export warm-up frames to avoid advancing certain stateful animations repeatedly. This is a targeted adjustment, not a general solution for all animation or seeking problems.
- **Shader safety:** an Iris compatibility path uses conservative CPU compositing for recorded UI and restricts some rendering and camera overrides while shaders are active. This trades some features and export speed for stability; see limitations below.

## Compatibility work

These names identify integrations, active-resource-system support or test coverage, **not a guarantee of complete compatibility**. Mods and packs must be installed separately.

| Mod or pack | What is implemented or exercised | Status |
| --- | --- | --- |
| Iris, Sodium, iterationRP | Shader-aware conservative rendering and UI compositing; shader-on 1080p/60 FPS exports completed in the isolated profile. | More shader, driver and long-session testing needed. Some camera/visual overrides are deliberately suppressed with Iris active. |
| Do a Barrel Roll | Optional camera-state adapter and roll-state recording/playback code. Adapter initialization observed. | Actual flying barrel rolls and accurate motion need further testing. |
| First-person Model, Real Camera | Optional detection and camera compatibility adapters. | Not validated end to end in the recent tests. |
| Entity Model Features (EMF), Entity Texture Features (ETF), EMF Compat Core | EMF warm-up timing adjustment; entity models and textures use the active client resource system. | Tested together in a limited isolated scene; animation, seeking, variants and armor need broader tests. |
| Fresh Animations, FA+Details, Detailed Animations, Traben's 3D Armor | Loaded in the entity-compatibility test profile using EMF/ETF. | Not guaranteed 1:1; test each pack, animation and armor combination. |
| Continuity, OptiGUI | Replay resource-pack selection/reload and recorded UI overlay can work alongside them. | GUI textures are captured pixels; connected textures and OptiGUI interactions need further tests. |
| Connected World, 1.15 PwS, Crops 3D, Door Tweaks, Better Lanterns, AL's 3D Potions, Os' Colorful Grasses, Undopia 3D Lily Pad, RAY's 3D Rails, Fresh Flower Pots, Even Better Enchants | Loaded as part of the resource-pack test setup through Minecraft's resource system. | Coverage is incomplete; pack order, versions and content matter. |

The upstream code also includes integrations for mods such as Simple Voice Chat, Bobby and Distant Horizons. Their presence in the code is **not evidence that they were tested in this 26.3 build**. Other mods and packs are not automatically supported just because they are installed.

## How to use

1. Record a **new** replay while opening the interfaces you want to show.
2. Open the replay. Choose shaders in Iris and resource packs under **File → Select Resource Packs**, applying the reload before export.
3. Select **File → Export Video**. Use **Perspective**, set the resolution and frame rate you want, and leave **No GUI unchecked** to include the recorded interfaces. Check it to omit the GUI.
4. Review the resulting video, particularly interface transitions, camera movement and modded animations. Export happens offline: a 60 FPS file does not imply the game rendered at 60 FPS during recording.

## Tested and known limitations

- In an isolated Fabric 26.3 profile, a new test replay had zero dropped UI encode jobs and no camera-sample gaps over two ticks. A recorded pause menu appeared and disappeared in the exported video.
- With iterationRP and Iris enabled, two No GUI exports and one GUI-enabled export completed at 1920 × 1080, 60 FPS, and decoded without reported errors. Dedicated GPU memory stayed roughly 1.7–1.8 GiB during these short repeated tests, rather than rising continuously as it did on the old No GUI path. This is not a long-duration leak guarantee.
- The latest exports reported 3–4 unavailable camera checks near the start. Some camera poses and visual overrides are suppressed while Iris is active; exact camera reconstruction remains unproven.
- Distant terrain flicker remains unresolved. The progress display showed Rendering during sampled checks, but it was not continuously recorded to rule out every brief flicker.
- Multiplayer, many screen types, fast cursor movement, long recordings, camera mods, seeking, audio and all combinations of mods/resource packs still need testing. Back up valuable replays.

## Reporting problems

If you encounter a problem, please share the Minecraft/Fabric/mod versions, reproduction steps, whether shaders were enabled, and a relevant log or small replay when you are comfortable doing so. Please review logs and recordings for private information first. Please report issues on [this fork's issue tracker](https://github.com/RajAgamSinghKalra/flashback-gui-rendering/issues), rather than the upstream Flashback project: these changes are unofficial. Thank you for helping test it!

## Credits and license

Original Flashback: Moulberry and upstream contributors. This experimental build contains AI-assisted changes on top of their work. See [LICENSE.md](LICENSE.md) for the original license and localization credits. Any third-party components retain their own licenses.
