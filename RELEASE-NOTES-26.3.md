# Flashback with All GUI Rendering — 26.3 experimental 1

**VIBECODED / AI-ASSISTED.** An unofficial modification of Flashback by Moulberry. More testing is required; this name is an aspiration, not a guarantee that all interfaces render correctly.

**Target:** Minecraft 26.3, Fabric. Tested locally with Fabric Loader 0.19.5, Fabric API 0.163.0+26.3, Java 25, Iris/Sodium and the edited iterationRP shader.

## Changes

- Records GUI layers and cursor data for replay and export, including tested inventory, container and pause screens. Overlays use recorded pixels, so quality and frame cadence depend on the original capture.
- Allows resource pack selection in the replay editor and warns about differences in recorded pack IDs/order.
- Adds camera and camera-mod state capture, paused-screen camera sampling, packet timing diagnostics and optional camera adapters.
- Adds EMF export-warm-up timing handling and conservative Iris-compatible overlay rendering.
- Corrects UI clear-event ordering and the No GUI renderer path that previously skipped Minecraft's frame cleanup; improves export progress and readback cleanup.

## Validation and known issues

In an isolated profile, two No GUI and one GUI-enabled shader exports completed at 1080p/60 FPS and decoded without errors. A test pause menu appeared and disappeared as expected. The new recording reported zero dropped UI encode jobs; dedicated GPU memory was stable at roughly 1.7–1.8 GiB during these short tests. These observations do not prove correctness for every replay, mod, GPU or long session.

Export still recorded 3–4 unavailable camera checks near the start. Iris shader safety suppresses some camera and visual overrides. Distant terrain flickering is unresolved, and the progress display was sampled rather than continuously captured. Multiplayer, fast movement, barrel rolls, seeking, screen types and other mod combinations need more testing.

For compatibility details and installation, see [README.md](README.md). If you find a problem, please share reproduction steps and relevant versions with this fork's maintainer; please review any logs or replay files for private information first. Thank you for testing!

## Distribution and credits

Original Flashback is copyright Moulberry. The upstream [LICENSE.md](LICENSE.md) states **“Do not redistribute.”** Check the rights holder's permissions before redistributing. This is not an official Flashback release.
