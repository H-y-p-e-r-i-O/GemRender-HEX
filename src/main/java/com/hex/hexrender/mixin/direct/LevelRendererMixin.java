package com.hex.hexrender.mixin.direct;

import com.hex.hexrender.direct.DirectRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class LevelRendererMixin {
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void gemrender$beginLevel(CallbackInfo ci) {
        DirectRenderer.beginLevel();
    }

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void gemrender$endLevel(CallbackInfo ci) {
        DirectRenderer.endLevel();
    }
}
