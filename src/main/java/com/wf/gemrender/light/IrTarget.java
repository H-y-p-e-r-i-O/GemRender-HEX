package com.wf.gemrender.light;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL20C.glDrawBuffers;
import static org.lwjgl.opengl.GL30C.*;

/**
 * IR irradiance x albedo of the main view ({@link Lights.IrView#SPLIT}): RGBA16F, main target size. COLOR1 of
 * the main framebuffer from {@code AFTER_SKY} to the end of the hand pass; detached otherwise.
 * <p>Unpatched programs drawing meanwhile (clouds, weather, outlines) leave COLOR1 as is: driver behaviour,
 * gated by {@code LightShadingGlTest.unwrittenLocationKeepsContents}.
 * <p>Gap: Fabulous translucent/particle/weather targets carry no COLOR1 => no IR there.
 */
public final class IrTarget {
    private static final float[] ZERO = new float[4];

    private static int texture;
    private static int width;
    private static int height;
    private static boolean pending;
    private static boolean attached;
    private static boolean filled;

    private IrTarget() {
    }

    /** Texture name; valid content when {@link #filled()}. 0 before the first SPLIT pass. */
    public static int texture() {
        return texture;
    }

    /** The last main view drew IR into {@link #texture()}. Read after the level render, before the next. */
    public static boolean filled() {
        return filled;
    }

    /** Nested level pass (scope PiP inside the main frame) never re-attaches or clears. */
    static void pass(boolean split) {
        pending = split && !attached;
    }

    static void afterSky() {
        if (!pending || attached) {
            return;
        }
        pending = false;
        RenderSystem.assertOnRenderThread();
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        if (texture == 0 || width != main.width || height != main.height) {
            allocate(main.width, main.height);
        }
        int previous = glGetInteger(GL_FRAMEBUFFER_BINDING);
        GlStateManager._glBindFramebuffer(GL_FRAMEBUFFER, main.frameBufferId);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_TEXTURE_2D, texture, 0);
        glDrawBuffers(new int[] {GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1});
        glClearBufferfv(GL_COLOR, 1, ZERO);
        GlStateManager._glBindFramebuffer(GL_FRAMEBUFFER, previous);
        attached = true;
    }

    static void end() {
        pending = false;
        filled = attached;
        if (!attached) {
            return;
        }
        attached = false;
        int previous = glGetInteger(GL_FRAMEBUFFER_BINDING);
        GlStateManager._glBindFramebuffer(GL_FRAMEBUFFER, Minecraft.getInstance().getMainRenderTarget().frameBufferId);
        glDrawBuffers(new int[] {GL_COLOR_ATTACHMENT0});
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_TEXTURE_2D, 0, 0);
        GlStateManager._glBindFramebuffer(GL_FRAMEBUFFER, previous);
    }

    private static void allocate(int w, int h) {
        if (texture == 0) {
            texture = glGenTextures();
        }
        width = w;
        height = h;
        int previous = glGetInteger(GL_TEXTURE_BINDING_2D);
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, w, h, 0, GL_RGBA, GL_HALF_FLOAT, 0L);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glBindTexture(GL_TEXTURE_2D, previous);
    }
}
