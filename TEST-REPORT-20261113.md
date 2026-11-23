# Flashback 26.3 candidate test — 13 November 2026

Candidate: investigator-work/flashback-custom-263/build/libs/merged-0.43.6.jar. The main profile still uses official Flashback; candidate installed in isolated test profiles only.

## Full-modpack, shader-enabled

Candidate launched into a singleplayer ocean world with iterationRP, Iris, Voxy, Physics Mod and Do a Barrel Roll. Started a recording, opened the creative inventory, stopped and saved it. The replay ZIP includes ui_overlay/manifest.json and 111 QOI assets; playback displayed the recorded inventory with shaders active. The camera-mod adapter initialized, but no actual barrel-roll maneuver was recorded, so its visual fidelity is unverified. Log reports overlay sampling and cursor gaps up to 0.344 seconds. Log also reports camera and camera-mod sample gaps, and Iris isolation suppresses camera/projection overrides. No controlled performance comparison was performed.

Closing the shader-heavy replay client via window close hung during IntegratedServer.halt and produced crash-reports/crash-2026-11-13_18.47.51-client.txt. This does not identify a specific cause or mod. An owned hung test process was terminated after its watchdog report.

## Minimal, no shader

Separate test profile with Fabric API, candidate Flashback and investigator development bridge/profiler. Launched in a copied world, then opened a copy of the above replay, acknowledging the expected registry mismatch because it was created with many other mods. The recorded inventory appeared during playback. Exported 1920x1080 PNG 2026-11-13T18_53_58.png and a 320x180, 10 FPS, 43.7-second H.264 video 2026-11-13T18_57_15.mp4. Screenshot and a video frame at 24 seconds visibly contain the inventory; video decoded fully using ffmpeg. The replay's recorded UI samples were made in the full profile, so this minimal playback cannot isolate the source of recording gaps. Video quality and visual fidelity at production settings were not tested.

## Not verified

A completed shader-enabled screenshot or video export; real flying barrel-roll motion; newly recorded minimal-profile replay; independent with/without-mod FPS/frametime comparison; other camera mods and multiplayer. The candidate remains a work in progress, not a release-ready replacement.
