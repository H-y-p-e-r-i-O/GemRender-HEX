package com.hex.hexrender.fabric;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;

public final class IrisHooks {
    private IrisHooks() {
    }

    //? if iris {
    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> com.hex.hexrender.iris.IrisPbrBridge
                .install(FabricLoader.getInstance()
                        .isModLoaded("iris")));
    }
    //?} else {
    /*public static void init() {
    }
    *///?}
}
