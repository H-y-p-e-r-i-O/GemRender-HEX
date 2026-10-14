package com.hex.hexrender.particle;

import com.hex.hexrender.mixin.LevelRendererAccessor;
import com.hex.hexrender.render.Vanilla;

import net.minecraft.client.Minecraft;

public final class ParticleClock {
    private ParticleClock() {
    }

    public static float seconds() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.levelRenderer == null) {
            return 0.0f;
        }

        int ticks = ((LevelRendererAccessor) minecraft.levelRenderer).gemrender$getTicks();
        return (ticks + Vanilla.partialTick()) / 20.0f;
    }
}
