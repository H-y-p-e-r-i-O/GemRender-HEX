package com.hex.hexrender.mixin.direct26;

import com.hex.hexrender.direct.DirectPass;
import com.hex.hexrender.direct.DirectRenderer;
import com.hex.hexrender.render.Vanilla;
import net.minecraft.client.renderer.ItemInHandRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemInHandRenderer.class)
public class ItemInHandRendererMixin {
    @Inject(method = "renderHandsWithItems", at = @At("HEAD"))
    private void gemrender$beginHand(CallbackInfo ci) {
        DirectRenderer.beginSink(DirectPass.HAND);
    }

    @Inject(method = "renderHandsWithItems", at = @At("RETURN"))
    private void gemrender$endHand(CallbackInfo ci) {

        Vanilla.intoMainTarget(DirectRenderer::endSink);
    }
}
