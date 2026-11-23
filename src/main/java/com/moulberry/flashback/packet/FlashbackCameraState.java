package com.moulberry.flashback.packet;

import com.moulberry.flashback.Flashback;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record FlashbackCameraState(int version, List<Sample> samples) implements CustomPacketPayload {
    public static final Type<FlashbackCameraState> TYPE = new Type<>(Flashback.createIdentifier("camera_state"));
    private static final int VERSIONED_SENTINEL = -1;
    private static final int VERSION = 5;

    public static final StreamCodec<FriendlyByteBuf, FlashbackCameraState> STREAM_CODEC = new CameraStateStreamCodec();

    public FlashbackCameraState(List<Sample> samples) {
        this(VERSION, samples);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public record Sample(float partialTick, double sampleTime, int cameraType, boolean detached, @Nullable UUID cameraEntityUuid,
                         double x, double y, double z,
                         float qx, float qy, float qz, float qw,
                         float fov, int compatibilityFlags,
                         boolean hasEntityPose, double entityX, double entityY, double entityZ,
                         float entityYaw, float entityPitch, float entityHeadYaw, float entityBodyYaw) {
    }

    public static class CameraStateStreamCodec implements StreamCodec<FriendlyByteBuf, FlashbackCameraState> {
        @Override
        public FlashbackCameraState decode(FriendlyByteBuf friendlyByteBuf) {
            int count = friendlyByteBuf.readVarInt();
            int version = 1;
            if (count == VERSIONED_SENTINEL) {
                version = friendlyByteBuf.readVarInt();
                count = friendlyByteBuf.readVarInt();
            }
            List<Sample> samples = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                float partialTick = friendlyByteBuf.readFloat();
                double sampleTime = version >= 2 ? friendlyByteBuf.readDouble() : Double.NaN;
                int cameraType = friendlyByteBuf.readVarInt();
                boolean detached = friendlyByteBuf.readBoolean();
                UUID cameraEntityUuid = friendlyByteBuf.readBoolean() ? friendlyByteBuf.readUUID() : null;
                double x = friendlyByteBuf.readDouble();
                double y = friendlyByteBuf.readDouble();
                double z = friendlyByteBuf.readDouble();
                float qx = friendlyByteBuf.readFloat();
                float qy = friendlyByteBuf.readFloat();
                float qz = friendlyByteBuf.readFloat();
                float qw = friendlyByteBuf.readFloat();
                float fov = friendlyByteBuf.readFloat();
                int compatibilityFlags = version >= 2 ? friendlyByteBuf.readVarInt() : 0;
                boolean hasEntityPose = version >= 4 && friendlyByteBuf.readBoolean();
                double entityX = hasEntityPose ? friendlyByteBuf.readDouble() : 0.0;
                double entityY = hasEntityPose ? friendlyByteBuf.readDouble() : 0.0;
                double entityZ = hasEntityPose ? friendlyByteBuf.readDouble() : 0.0;
                float entityYaw = hasEntityPose ? friendlyByteBuf.readFloat() : 0.0f;
                float entityPitch = hasEntityPose ? friendlyByteBuf.readFloat() : 0.0f;
                float entityHeadYaw = hasEntityPose ? friendlyByteBuf.readFloat() : entityYaw;
                float entityBodyYaw = hasEntityPose && version >= 5 ? friendlyByteBuf.readFloat() : Float.NaN;
                samples.add(new Sample(partialTick, sampleTime, cameraType, detached, cameraEntityUuid, x, y, z, qx, qy, qz, qw, fov, compatibilityFlags,
                    hasEntityPose, entityX, entityY, entityZ, entityYaw, entityPitch, entityHeadYaw, entityBodyYaw));
            }
            return new FlashbackCameraState(version, samples);
        }

        @Override
        public void encode(FriendlyByteBuf friendlyByteBuf, FlashbackCameraState cameraState) {
            friendlyByteBuf.writeVarInt(VERSIONED_SENTINEL);
            friendlyByteBuf.writeVarInt(VERSION);
            friendlyByteBuf.writeVarInt(cameraState.samples.size());
            for (Sample sample : cameraState.samples) {
                friendlyByteBuf.writeFloat(sample.partialTick);
                friendlyByteBuf.writeDouble(sample.sampleTime);
                friendlyByteBuf.writeVarInt(sample.cameraType);
                friendlyByteBuf.writeBoolean(sample.detached);
                friendlyByteBuf.writeBoolean(sample.cameraEntityUuid != null);
                if (sample.cameraEntityUuid != null) {
                    friendlyByteBuf.writeUUID(sample.cameraEntityUuid);
                }
                friendlyByteBuf.writeDouble(sample.x);
                friendlyByteBuf.writeDouble(sample.y);
                friendlyByteBuf.writeDouble(sample.z);
                friendlyByteBuf.writeFloat(sample.qx);
                friendlyByteBuf.writeFloat(sample.qy);
                friendlyByteBuf.writeFloat(sample.qz);
                friendlyByteBuf.writeFloat(sample.qw);
                friendlyByteBuf.writeFloat(sample.fov);
                friendlyByteBuf.writeVarInt(sample.compatibilityFlags);
                friendlyByteBuf.writeBoolean(sample.hasEntityPose);
                if (sample.hasEntityPose) {
                    friendlyByteBuf.writeDouble(sample.entityX);
                    friendlyByteBuf.writeDouble(sample.entityY);
                    friendlyByteBuf.writeDouble(sample.entityZ);
                    friendlyByteBuf.writeFloat(sample.entityYaw);
                    friendlyByteBuf.writeFloat(sample.entityPitch);
                    friendlyByteBuf.writeFloat(sample.entityHeadYaw);
                    friendlyByteBuf.writeFloat(sample.entityBodyYaw);
                }
            }
        }
    }

}
