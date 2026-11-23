# Group 1 compatibility notes

The recording stores the ordered selected client resource-pack IDs in `metadata.json` as `resourcePacks`. On opening a new replay, Flashback compares these IDs with the currently selected client packs and displays a warning and logs both ordered lists if they differ. Replays without this metadata still load. Flashback does not change or package resource packs, and this is not a content hash: packs changed in place, server packs, pack changes during recording and differing mod configurations may still change visuals without a warning.

Continuity applies model wrappers and resource reload listeners on the client. Block and item model packs normally use the same current client resource system during playback and export. This change does not replace those renderers and does not prove that connected textures, item models, pack versions, shader interactions, or frame-to-frame animation are correct.

OptiGUI reacts to actual screen changes and replaces textures for supported screens. The existing custom UI overlay captures rendered GUI images; it does not reconstruct screen state and may have capture gaps. It cannot guarantee a pixel-perfect replay or high-resolution re-rendering of that interface.

No game was launched for this change. Check each pack and mod during recording, replay, and export before considering it compatible. Use the same ordered packs and mod versions when testing. The custom build remains work in progress.
