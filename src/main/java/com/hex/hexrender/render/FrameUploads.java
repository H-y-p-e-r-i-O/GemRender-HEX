package com.hex.hexrender.render;

import com.hex.hexrender.debug.SamplerProbe;
import com.hex.hexrender.particle.ParticleBuffer;
import com.hex.hexrender.texture.PaintArray;
import com.hex.hexrender.volume.Volumetrics;

public final class FrameUploads {

    private FrameUploads() {
    }

    public static void run() {
        SamplerProbe.sample();
        long uploadStart = System.nanoTime();

        GlAudit.Scope audit = GlAudit.open("gemrender:upload");
        try {
            BoneBuffer.getInstance()
                    .uploadAndBind();
            MorphBuffer.getInstance()
                    .uploadAndBind();
            ParticleBuffer.getInstance()
                    .uploadAndBind();
            Volumetrics.getInstance()
                    .upload();
            PaintArray.bind();
        } finally {
            audit.close();
        }

        FrameCost.getInstance()
                .addUploadNanos(System.nanoTime() - uploadStart);
        PoseCache.getInstance()
                .endFrame();
        FrameCost.getInstance()
                .endFrame();
    }
}
