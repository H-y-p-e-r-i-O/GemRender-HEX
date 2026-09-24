package com.wf.gemrender.fabric;

import java.util.Optional;

import dev.engine_room.flywheel.api.event.EndClientResourceReloadCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.ResourceManager;

public final class DirectHooks {
    private DirectHooks() {
    }

    //? if direct {
    public static void init() {
        EndClientResourceReloadCallback.EVENT.register(DirectHooks::onEndClientResourceReload);
    }

    private static void onEndClientResourceReload(Minecraft minecraft, ResourceManager resources,
            boolean first, Optional<Throwable> error) {
        if (error.isPresent()) {
            return;
        }

        com.wf.gemrender.direct.DirectReload.run();
    }
    //?} else {
    /*public static void init() {
    }
    *///?}
}
