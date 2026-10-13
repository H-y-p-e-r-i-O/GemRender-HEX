package com.wf.gemrender.light;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.ToDoubleFunction;
import java.util.function.Supplier;

/**
 * Dynamic point and spot lights, forward-shaded in terrain (vanilla + Sodium), entity, particle,
 * Flywheel and direct-path shaders. Shadows per light ({@link LightSink#point}).
 *
 * <p>Off while an Iris shader pack is in use.
 */
public final class Lights {
    /**
     * Lights shaded per frame after culling; the nearest win.
     */
    public static final int MAX = 64;

    private static final List<Provider> PROVIDERS = new CopyOnWriteArrayList<>();

    private static volatile Supplier<IrView> irView = () -> IrView.OFF;
    private static volatile ToDoubleFunction<IrView> nightSky = view -> 0.0;

    private Lights() {
    }

    /**
     * Polled on the render thread at the start of every level frame.
     */
    public static void register(Provider provider) {
        PROVIDERS.add(provider);
    }

    public static void unregister(Provider provider) {
        PROVIDERS.remove(provider);
    }

    /** False => providers not polled, nothing shaded (shader pack): keep a fallback. */
    public static boolean shading() {
        return !LightShaders.standDown();
    }

    /**
     * Any thread. Texture resampled to {@link LightCookies#SIZE}^2; reloaded with resources.
     */
    public static LightCookie cookie(ResourceLocation texture) {
        return LightCookies.cookie(texture);
    }

    /** Polled at every level pass start (main view and offscreen passes). One source; last call wins. */
    public static void irView(Supplier<IrView> source) {
        irView = source;
    }

    static IrView irView() {
        return irView.get();
    }

    /**
     * Moon + starlight on a fully sky-lit surface per pass mode: irradiance in light units x (lightmap sky level)^2.
     * {@link IrView#SPLIT} => IR channel (RGBA16F), {@link IrView#MERGED} => colour (8-bit: a value below ~1/255
     * after albedo vanishes, so a merged sensor pre-scales), {@link IrView#OFF} => not asked, none. Polled at every
     * level pass start; last call wins.
     */
    public static void nightSky(ToDoubleFunction<IrView> source) {
        nightSky = source;
    }

    static float nightSky(IrView view) {
        return (float) nightSky.applyAsDouble(view);
    }

    static List<Provider> providers() {
        return PROVIDERS;
    }

    /** What a level pass does with IR lights. */
    public enum IrView {
        /** Dropped at collection. */
        OFF,
        /** Main view only: IR irradiance -> {@link IrTarget}, colour unchanged. */
        SPLIT,
        /** Added into colour (IR sensor pass). */
        MERGED
    }

    @FunctionalInterface
    public interface Provider {
        void collect(LightSink sink, float partialTick);
    }
}
