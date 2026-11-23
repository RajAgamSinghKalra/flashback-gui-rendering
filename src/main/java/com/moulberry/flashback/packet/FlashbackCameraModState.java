package com.moulberry.flashback.packet;

import com.moulberry.flashback.Flashback;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record FlashbackCameraModState(int version, List<Sample> samples) implements CustomPacketPayload {
    public static final Type<FlashbackCameraModState> TYPE = new Type<>(Flashback.createIdentifier("camera_mod_state"));
    private static final int VERSIONED_SENTINEL = -1;
    private static final int VERSION = 3;

    public static final StreamCodec<FriendlyByteBuf, FlashbackCameraModState> STREAM_CODEC = new CameraModStateStreamCodec();

    public FlashbackCameraModState(List<Sample> samples) {
        this(VERSION, samples);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public record Sample(double sampleTime, UUID entityUuid, int compatibilityFlags,
                         boolean doABarrelRollRolling, float doABarrelRollRoll, float doABarrelRollPrevRoll) {
    }

    public static class CameraModStateStreamCodec implements StreamCodec<FriendlyByteBuf, FlashbackCameraModState> {
        @Override
        public FlashbackCameraModState decode(FriendlyByteBuf friendlyByteBuf) {
            int count = friendlyByteBuf.readVarInt();
            int version = 1;
            if (count == VERSIONED_SENTINEL) {
                version = friendlyByteBuf.readVarInt();
                count = friendlyByteBuf.readVarInt();
            }

            List<Sample> samples = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                double sampleTime = friendlyByteBuf.readDouble();
                UUID entityUuid = friendlyByteBuf.readUUID();
                int compatibilityFlags = friendlyByteBuf.readVarInt();
                boolean doABarrelRollRolling = friendlyByteBuf.readBoolean();
                float doABarrelRollRoll = friendlyByteBuf.readFloat();
                float doABarrelRollPrevRoll = version >= 3 ? friendlyByteBuf.readFloat() : doABarrelRollRoll;
                samples.add(new Sample(sampleTime, entityUuid, compatibilityFlags, doABarrelRollRolling, doABarrelRollRoll, doABarrelRollPrevRoll));
            }
            return new FlashbackCameraModState(version, samples);
        }

        @Override
        public void encode(FriendlyByteBuf friendlyByteBuf, FlashbackCameraModState cameraModState) {
            friendlyByteBuf.writeVarInt(VERSIONED_SENTINEL);
            friendlyByteBuf.writeVarInt(VERSION);
            friendlyByteBuf.writeVarInt(cameraModState.samples.size());
            for (Sample sample : cameraModState.samples) {
                friendlyByteBuf.writeDouble(sample.sampleTime);
                friendlyByteBuf.writeUUID(sample.entityUuid);
                friendlyByteBuf.writeVarInt(sample.compatibilityFlags);
                friendlyByteBuf.writeBoolean(sample.doABarrelRollRolling);
                friendlyByteBuf.writeFloat(sample.doABarrelRollRoll);
                friendlyByteBuf.writeFloat(sample.doABarrelRollPrevRoll);
            }
        }
    }
}
