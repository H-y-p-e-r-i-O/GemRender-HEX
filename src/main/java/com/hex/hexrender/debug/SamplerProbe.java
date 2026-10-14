package com.hex.hexrender.debug;

import com.hex.hexrender.render.BoneBuffer;
import com.hex.hexrender.render.MorphBuffer;
import com.hex.hexrender.render.TextureUnits;

import static org.lwjgl.opengl.GL31C.GL_TEXTURE_BINDING_BUFFER;
import static org.lwjgl.opengl.GL33C.*;

public final class SamplerProbe {
    private static final boolean ENABLED = Boolean.getBoolean("gemrender.probeunits");

    private static volatile String lastReport = "off";

    private SamplerProbe() {
    }

    public static void sample() {
        if (!ENABLED) {
            return;
        }

        int previous = glGetInteger(GL_ACTIVE_TEXTURE);
        StringBuilder report = new StringBuilder();

        append(report, BoneBuffer.TEXTURE_UNIT, BoneBuffer.getInstance()
                .textureId());
        report.append(',');
        append(report, MorphBuffer.TEXTURE_UNIT, MorphBuffer.getInstance()
                .textureId());

        org.lwjgl.opengl.GL13C.glActiveTexture(previous);
        lastReport = report.toString();
    }

    private static void append(StringBuilder report, int unit, int ours) {
        TextureUnits.activate(unit);

        int buffer = glGetInteger(GL_TEXTURE_BINDING_BUFFER);
        int flat = glGetInteger(GL_TEXTURE_BINDING_2D);

        report.append('u')
                .append(unit)
                .append('=')
                .append(buffer == 0 ? "none" : buffer == ours ? "ours" : "lost" + buffer);
        if (flat != 0) {
            report.append("+2d")
                    .append(flat);
        }
    }

    public static String report() {
        return lastReport;
    }
}
