package com.hex.hexrender.mixin.irisflw;

import com.hex.hexrender.render.SamplerBindings;
import dev.engine_room.flywheel.backend.gl.shader.GlProgram;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "top.leonx.irisflw.flywheel.IrisFlwCompatGlProgram", remap = false)
abstract class IrisFlwCompatGlProgramMixin {
    @Inject(method = "bind", at = @At("TAIL"))
    private void gemrender$bindSamplers(CallbackInfo ci) {
        SamplerBindings.apply((GlProgram) (Object) this);
    }
}
