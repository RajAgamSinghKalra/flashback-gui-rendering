package com.moulberry.flashback.io;

import com.google.gson.JsonObject;
import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.FlashbackGson;
import com.moulberry.flashback.action.Action;
import com.moulberry.flashback.action.ActionLevelChunkCached;
import com.moulberry.flashback.action.ActionRegistry;
import com.moulberry.flashback.playback.ReplayChunkCache;
import com.moulberry.flashback.record.FlashbackChunkMeta;
import com.moulberry.flashback.record.FlashbackMeta;
import com.moulberry.flashback.record.ReplayMarker;
import com.moulberry.flashback.ui.UiOverlayManifest;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.resources.Identifier;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ReplayCombiner {

    public static void combine(RegistryAccess registryAccess, String replayName, Path first, Path second, Path output) throws Exception {
        StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> gamePacketCodec = GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(registryAccess)).codec();

        try (FileSystem firstFileSystem = FileSystems.newFileSystem(first);
             FileSystem secondFileSystem = FileSystems.newFileSystem(second)) {

            List<ClientboundLevelChunkWithLightPacket> levelChunkPackets = new ArrayList<>();
            Int2IntMap levelChunkMappingsFirst = new Int2IntOpenHashMap();
            Int2IntMap levelChunkMappingsSecond = new Int2IntOpenHashMap();

            extractChunks(registryAccess, firstFileSystem, gamePacketCodec, levelChunkPackets, levelChunkMappingsFirst);
            extractChunks(registryAccess, secondFileSystem, gamePacketCodec, levelChunkPackets, levelChunkMappingsSecond);

            // Read metadata
            Path metadataPath = firstFileSystem.getPath("/metadata.json");
            String metadataJson = Files.readString(metadataPath);
            FlashbackMeta firstMetadata = FlashbackMeta.fromJson(FlashbackGson.COMPRESSED.fromJson(metadataJson, JsonObject.class));
            if (firstMetadata == null) {
                throw new RuntimeException("Unable to load /metadata.json from first replay");
            }

            Path secondMetadataPath = secondFileSystem.getPath("/metadata.json");
            String secondMetadataJson = Files.readString(secondMetadataPath);
            FlashbackMeta secondMetadata = FlashbackMeta.fromJson(FlashbackGson.COMPRESSED.fromJson(secondMetadataJson, JsonObject.class));
            if (secondMetadata == null) {
                throw new RuntimeException("Unable to load /metadata.json from second replay");
            }

            if (firstMetadata.dataVersion != secondMetadata.dataVersion) {
                throw new RuntimeException("Replays were created on different versions of the game, unable to combine");
            }

            int firstReplayDuration = firstMetadata.totalTicks;
            UiOverlayManifest firstUiOverlay = readUiOverlayManifest(firstFileSystem, firstMetadata);
            UiOverlayManifest secondUiOverlay = readUiOverlayManifest(secondFileSystem, secondMetadata);
            UiOverlayManifest combinedUiOverlay = combineUiOverlayManifests(firstUiOverlay, secondUiOverlay, firstReplayDuration);

            firstMetadata.replayIdentifier = UUID.randomUUID();
            firstMetadata.name = replayName;
            for (Map.Entry<Integer, ReplayMarker> entry : secondMetadata.replayMarkers.entrySet()) {
                firstMetadata.replayMarkers.put(entry.getKey() + firstMetadata.totalTicks, entry.getValue());
            }
            firstMetadata.totalTicks = firstMetadata.totalTicks + secondMetadata.totalTicks;
            firstMetadata.uiOverlay = combinedUiOverlay == null ? null : combinedUiOverlay.toMetadata();

            record ReplayChunk(Path path, Int2IntMap levelChunkMappings) {}
            Map<String, ReplayChunk> newReplayChunks = new HashMap<>();

            for (String name : firstMetadata.chunks.keySet()) {
                newReplayChunks.put(name, new ReplayChunk(firstFileSystem.getPath("/" + name), levelChunkMappingsFirst));
            }

            boolean isFirstChunkOfSecondReplay = true;
            for (Map.Entry<String, FlashbackChunkMeta> entry : secondMetadata.chunks.entrySet()) {
                String newName = "c" + firstMetadata.chunks.size() + ".flashback";
                if (isFirstChunkOfSecondReplay) {
                    isFirstChunkOfSecondReplay = false;
                    entry.getValue().forcePlaySnapshot = true;
                }
                firstMetadata.chunks.put(newName, entry.getValue());
                newReplayChunks.put(newName, new ReplayChunk(secondFileSystem.getPath("/" + entry.getKey()), levelChunkMappingsSecond));
            }

            // Actually write
            FileOutputStream fos = new FileOutputStream(output.toFile());
            BufferedOutputStream bos = new BufferedOutputStream(fos);
            ZipOutputStream zipOut = new ZipOutputStream(bos);
            zipOut.setLevel(Deflater.BEST_SPEED);

            // Write metadata
            ZipEntry zipEntry = new ZipEntry("metadata.json");
            zipOut.putNextEntry(zipEntry);
            zipOut.write(FlashbackGson.COMPRESSED.toJson(firstMetadata.toJson()).getBytes(StandardCharsets.UTF_8));
            zipOut.closeEntry();

            // Write chunked level chunk caches
            int lastCacheIndex = -1;
            RegistryFriendlyByteBuf chunkCacheOutput = null;
            for (int i = 0; i < levelChunkPackets.size(); i++) {
                int cacheIndex = i / ReplayChunkCache.CHUNK_CACHE_SIZE;

                if (chunkCacheOutput == null) {
                    lastCacheIndex = cacheIndex;
                    chunkCacheOutput = new RegistryFriendlyByteBuf(Unpooled.buffer(), registryAccess);
                } else if (cacheIndex != lastCacheIndex) {
                    byte[] bytes = new byte[chunkCacheOutput.writerIndex()];
                    chunkCacheOutput.getBytes(0, bytes);

                    zipEntry = new ZipEntry("level_chunk_caches/" + lastCacheIndex);
                    zipOut.putNextEntry(zipEntry);
                    zipOut.write(bytes);
                    zipOut.closeEntry();

                    lastCacheIndex = cacheIndex;
                    chunkCacheOutput = new RegistryFriendlyByteBuf(Unpooled.buffer(), registryAccess);
                }

                // Write placeholder value for size
                int startWriterIndex = chunkCacheOutput.writerIndex();
                chunkCacheOutput.writeInt(-1);

                // Write chunk packet
                gamePacketCodec.encode(chunkCacheOutput, levelChunkPackets.get(i));
                int endWriterIndex = chunkCacheOutput.writerIndex();

                // Write real size value
                int size = endWriterIndex - startWriterIndex - 4;
                chunkCacheOutput.writerIndex(startWriterIndex);
                chunkCacheOutput.writeInt(size);
                chunkCacheOutput.writerIndex(endWriterIndex);
            }

            if (chunkCacheOutput != null) {
                byte[] bytes = new byte[chunkCacheOutput.writerIndex()];
                chunkCacheOutput.getBytes(0, bytes);

                zipEntry = new ZipEntry("level_chunk_caches/" + lastCacheIndex);
                zipOut.putNextEntry(zipEntry);
                zipOut.write(bytes);
                zipOut.closeEntry();
            }

            // Write icon
            zipEntry = new ZipEntry("icon.png");
            zipOut.putNextEntry(zipEntry);
            Files.copy(firstFileSystem.getPath("/icon.png"), zipOut);
            zipOut.closeEntry();

            writeUiOverlay(zipOut, combinedUiOverlay, firstFileSystem, firstUiOverlay, secondFileSystem, secondUiOverlay);

            // Write chunks
            for (Map.Entry<String, ReplayChunk> entry : newReplayChunks.entrySet()) {
                zipEntry = new ZipEntry(entry.getKey());
                zipOut.putNextEntry(zipEntry);

                Path path = entry.getValue().path();
                byte[] replayChunk = Files.readAllBytes(path);
                RegistryFriendlyByteBuf inputBuf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(replayChunk), registryAccess);
                FriendlyByteBuf outputBuf = new FriendlyByteBuf(Unpooled.buffer());

                int magic = inputBuf.readInt();
                if (magic != Flashback.MAGIC) {
                    throw new RuntimeException("Invalid magic");
                }
                outputBuf.writeInt(magic);

                int levelChunkCachedActionId = -1;
                int actions = inputBuf.readVarInt();
                outputBuf.writeVarInt(actions);
                for (int i = 0; i < actions; i++) {
                    Identifier actionName = inputBuf.readIdentifier();
                    outputBuf.writeIdentifier(actionName);

                    Action action = ActionRegistry.getAction(actionName);

                    if (action instanceof ActionLevelChunkCached) {
                        levelChunkCachedActionId = i;
                    }
                }

                if (levelChunkCachedActionId == -1) {
                    Files.copy(entry.getValue().path, zipOut);
                } else {
                    int snapshotSize = inputBuf.readInt();
                    if (snapshotSize < 0) {
                        throw new RuntimeException("Invalid snapshot size: " + snapshotSize + " (0x" + Integer.toHexString(snapshotSize) + ")");
                    }

                    int snapshotInputEnd = inputBuf.readerIndex() + snapshotSize;

                    int snapshotOutputWriterIndex = outputBuf.writerIndex();
                    outputBuf.writeInt(0xDEADBEEF);
                    int snapshotStartWriterIndex = outputBuf.writerIndex();
                    int snapshotEndWriterIndex = outputBuf.writerIndex();

                    while (inputBuf.readerIndex() < inputBuf.writerIndex()) {
                        boolean inSnapshot = inputBuf.readerIndex() < snapshotInputEnd;

                        int id = inputBuf.readVarInt();
                        int size = inputBuf.readInt();
                        if (id == levelChunkCachedActionId) {
                            int cachedChunkId = inputBuf.readVarInt();

                            if (!entry.getValue().levelChunkMappings.containsKey(cachedChunkId)) {
                                throw new RuntimeException("Missing cached chunk id " + cachedChunkId);
                            }

                            int newCachedChunkId = entry.getValue().levelChunkMappings.get(cachedChunkId);

                            outputBuf.writeVarInt(id);
                            int sizeWriterIndex = outputBuf.writerIndex();
                            outputBuf.writeInt(0);
                            int cachedIdWriterIndex = outputBuf.writerIndex();
                            outputBuf.writeVarInt(newCachedChunkId);
                            int endWriterIndex = outputBuf.writerIndex();

                            outputBuf.writerIndex(sizeWriterIndex);
                            outputBuf.writeInt(endWriterIndex - cachedIdWriterIndex);
                            outputBuf.writerIndex(endWriterIndex);
                        } else {
                            outputBuf.writeVarInt(id);
                            outputBuf.writeInt(size);
                            outputBuf.writeBytes(inputBuf, size);
                        }

                        if (inSnapshot) {
                            snapshotEndWriterIndex = outputBuf.writerIndex();
                        }
                    }

                    int endWriterIndex = outputBuf.writerIndex();
                    outputBuf.writerIndex(snapshotOutputWriterIndex);
                    outputBuf.writeInt(snapshotEndWriterIndex - snapshotStartWriterIndex);
                    outputBuf.writerIndex(endWriterIndex);

                    byte[] bytes = new byte[outputBuf.writerIndex()];
                    outputBuf.getBytes(0, bytes);
                    zipOut.write(bytes);
                }

                zipOut.closeEntry();
            }

            zipOut.close();
            bos.close();
            fos.close();
        }
    }

    private static @org.jetbrains.annotations.Nullable UiOverlayManifest readUiOverlayManifest(FileSystem fileSystem, FlashbackMeta metadata) throws IOException {
        if (metadata.uiOverlay == null) {
            return null;
        }

        Path manifestPath = fileSystem.getPath("/" + metadata.uiOverlay.manifest);
        if (!Files.exists(manifestPath)) {
            Flashback.LOGGER.warn("Replay metadata references UI overlay, but {} is missing", manifestPath);
            return null;
        }

        JsonObject json = FlashbackGson.COMPRESSED.fromJson(Files.readString(manifestPath), JsonObject.class);
        UiOverlayManifest manifest = UiOverlayManifest.fromJson(json);
        if (manifest == null || (manifest.samples.isEmpty() && manifest.cursorSamples.isEmpty())) {
            Flashback.LOGGER.warn("Replay UI overlay manifest is invalid or empty: {}", manifestPath);
            return null;
        }

        return manifest;
    }

    private static @org.jetbrains.annotations.Nullable UiOverlayManifest combineUiOverlayManifests(
        @org.jetbrains.annotations.Nullable UiOverlayManifest first,
        @org.jetbrains.annotations.Nullable UiOverlayManifest second,
        int secondTickOffset
    ) {
        if (first == null && second == null) {
            return null;
        }

        UiOverlayManifest combined = new UiOverlayManifest();
        if (first != null) {
            combined.append(first.copyWithTickOffset(0));
        }
        if (second != null) {
            combined.append(second.copyWithTickOffset(secondTickOffset));
        }
        return combined.samples.isEmpty() && combined.cursorSamples.isEmpty() ? null : combined;
    }

    private static void writeUiOverlay(ZipOutputStream zipOut, @org.jetbrains.annotations.Nullable UiOverlayManifest combined,
                                       FileSystem firstFileSystem, @org.jetbrains.annotations.Nullable UiOverlayManifest first,
                                       FileSystem secondFileSystem, @org.jetbrains.annotations.Nullable UiOverlayManifest second) throws IOException {
        if (combined == null) {
            return;
        }

        ZipEntry zipEntry = new ZipEntry(UiOverlayManifest.MANIFEST_ENTRY);
        zipOut.putNextEntry(zipEntry);
        zipOut.write(FlashbackGson.COMPRESSED.toJson(combined.toJson()).getBytes(StandardCharsets.UTF_8));
        zipOut.closeEntry();

        Set<String> copiedAssets = new HashSet<>();
        copyUiOverlayAssets(zipOut, firstFileSystem, first, copiedAssets);
        copyUiOverlayAssets(zipOut, secondFileSystem, second, copiedAssets);
    }

    private static void copyUiOverlayAssets(ZipOutputStream zipOut, FileSystem fileSystem,
                                            @org.jetbrains.annotations.Nullable UiOverlayManifest manifest,
                                            Set<String> copiedAssets) throws IOException {
        if (manifest == null) {
            return;
        }

        for (UiOverlayManifest.Sample sample : manifest.samples) {
            copyUiOverlayLayer(zipOut, fileSystem, sample.beforeBlur, copiedAssets);
            copyUiOverlayLayer(zipOut, fileSystem, sample.afterBlur, copiedAssets);
        }
    }

    private static void copyUiOverlayLayer(ZipOutputStream zipOut, FileSystem fileSystem,
                                           @org.jetbrains.annotations.Nullable UiOverlayManifest.Layer layer,
                                           Set<String> copiedAssets) throws IOException {
        if (layer == null) {
            return;
        }

        for (UiOverlayManifest.Layer tile : layer.tiles) {
            copyUiOverlayLayer(zipOut, fileSystem, tile, copiedAssets);
        }

        if (layer.path == null || !copiedAssets.add(layer.path)) {
            return;
        }

        Path path = fileSystem.getPath("/" + layer.path);
        if (!Files.exists(path)) {
            Flashback.LOGGER.warn("Skipping missing UI overlay frame {}", path);
            return;
        }

        ZipEntry zipEntry = new ZipEntry(layer.path);
        zipOut.putNextEntry(zipEntry);
        Files.copy(path, zipOut);
        zipOut.closeEntry();
    }

    private static void extractChunks(RegistryAccess registryAccess, FileSystem fileSystem, StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> gamePacketCodec,
            List<ClientboundLevelChunkWithLightPacket> packets, Int2IntMap levelChunkMappings) throws IOException {
        Path levelChunkCachePath = fileSystem.getPath("/level_chunk_cache");
        if (Files.exists(levelChunkCachePath)) {
            loadLevelChunkCache(gamePacketCodec, registryAccess, levelChunkCachePath, 0, packets, levelChunkMappings);
        }

        int index = 0;
        while (true) {
            levelChunkCachePath = fileSystem.getPath("/level_chunk_caches/"+index);
            if (Files.exists(levelChunkCachePath)) {
                loadLevelChunkCache(gamePacketCodec, registryAccess, levelChunkCachePath, index * ReplayChunkCache.CHUNK_CACHE_SIZE, packets, levelChunkMappings);
                index += 1;
            } else {
                break;
            }
        }
    }

    private static void loadLevelChunkCache(StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> gamePacketCodec, RegistryAccess registryAccess,
            Path levelChunkCachePath, int chunkCacheIndex, List<ClientboundLevelChunkWithLightPacket> packets, Int2IntMap levelChunkMappings) throws IOException {
        try (InputStream is = Files.newInputStream(levelChunkCachePath)) {
            while (true) {
                byte[] sizeBuffer = is.readNBytes(4);
                if (sizeBuffer.length < 4) {
                    break;
                }

                int size = (sizeBuffer[0] & 0xff) << 24 |
                    (sizeBuffer[1] & 0xff) << 16 |
                    (sizeBuffer[2] & 0xff) <<  8 |
                    sizeBuffer[3] & 0xff;

                byte[] chunk = is.readNBytes(size);
                if (chunk.length < size) {
                    Flashback.LOGGER.error("Ran out of bytes while reading level_chunk_cache, needed {}, had {}",
                        size, chunk.length);
                    break;
                }

                RegistryFriendlyByteBuf registryFriendlyByteBuf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(chunk), registryAccess);

                try {
                    Packet<?> packet = gamePacketCodec.decode(registryFriendlyByteBuf);
                    if (packet instanceof ClientboundLevelChunkWithLightPacket levelChunkWithLightPacket) {
                        levelChunkMappings.put(chunkCacheIndex, packets.size());
                        packets.add(levelChunkWithLightPacket);
                    } else {
                        throw new IllegalStateException("Level chunk cache contains wrong packet: " + packet);
                    }
                } catch (Exception e) {
                    Flashback.LOGGER.error("Encountered error while reading level_chunk_cache", e);
                }

                chunkCacheIndex += 1;
            }
        }
    }

}
