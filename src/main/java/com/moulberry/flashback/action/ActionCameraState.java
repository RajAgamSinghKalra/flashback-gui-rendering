package com.moulberry.flashback.action;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.playback.ReplayServer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;

public class ActionCameraState implements Action {

    private static final Identifier NAME = Flashback.createIdentifier("action/camera_state_optional");
    public static final ActionCameraState INSTANCE = new ActionCameraState();
    private ActionCameraState() {
    }

    @Override
    public Identifier name() {
        return NAME;
    }

    @Override
    public void handle(ReplayServer replayServer, RegistryFriendlyByteBuf friendlyByteBuf) {
        replayServer.handleCameraState(friendlyByteBuf);
    }

}
