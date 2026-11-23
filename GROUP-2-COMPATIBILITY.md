# Entity models, textures and animation: compatibility notes

## Implemented

EMF's `frame_time` normally advances stateful pack animations using the client frame delta. Flashback's export warm-up renders the same replay instant repeatedly while retaining that delta. Some Fresh Animations files use `frame_time` to accumulate state; advancing it on those synthetic frames can move the animation ahead before the exported frame. When EMF is present, Flashback now supplies EMF's own near-zero frozen-frame value only during those extra warm-up renders. This does not change normal recording, playback, or regular export frames. The integration is optional if EMF is absent.

The previously added resource-pack selection menu, recorded pack-order warning, and custom UI overlay remain in the build. Entity texture/model packs use the active client resource system; switching packs still requires a resource reload. ETF's random variant API normally uses entity UUID-based seeds, and replay spawn handling preserves UUIDs, but that alone does not guarantee every variant or state will match.

## Not verified or solved

This code has not been exercised in Minecraft with EMF, ETF, EMF Compat Core or any of the named entity resource packs. It does not record all client-only entity/model state, guarantee exact timing after seeking, or prove correct animated textures, model geometry, armor, emissive textures, shaders, multiple render passes or custom mod integrations. The exporter currently resets entity tick counts during some seeks; this may affect age-based animations. Camera-dependent and locally simulated values may also differ in replay. Warm-up frame handling does not make playback or export 1:1 by itself.

Test a newly recorded replay with several animals and hostile mobs, a named/random-variant mob, armor, texture-pack changes and both normal and paused/seeking playback. Compare recording and 60 fps export. Test group 1 packs again in the same profile. No game was launched for this change.
