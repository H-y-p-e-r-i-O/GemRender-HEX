package com.wf.gemrender.particle;

import com.wf.gemrender.GemRender;
import com.wf.gemrender.water.Absorbance;
import dev.engine_room.flywheel.api.material.*;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.lib.material.*;
import dev.engine_room.flywheel.lib.model.QuadMesh;
import dev.engine_room.flywheel.lib.model.SingleMeshModel;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ParticleModels {
    public static final MaterialShaders ABSORBANCE_SHADERS = new SimpleMaterialShaders(
            ResourceLocation.fromNamespaceAndPath("flywheel", "material/default.vert"),
            ResourceLocation.fromNamespaceAndPath(GemRender.MOD_ID, "material/absorbance.frag"));

    private static final Map<Key, Model> BILLBOARDS = new ConcurrentHashMap<>();

    static {
        Absorbance.getInstance()
                .register(ABSORBANCE_SHADERS);
    }

    private ParticleModels() {
    }

    /**
     * A glow: the fragment is added to what is already there, so it can only ever brighten.
     *
     * <p>Which is why it writes no depth — see {@link #writeMaskFor}.
     */
    public static Model additive(ResourceLocation texture) {
        return billboard(texture, Transparency.ADDITIVE);
    }

    /**
     * Smoke, steam, dust: alpha-composited, and order-independent so the backend resolves the stack per
     * pixel rather than in submission order.
     *
     * <p>This is the one blend GemRender splits around translucent terrain, so a particle that crosses a
     * water surface composites on the correct side of it. An {@code ADDITIVE} particle cannot be split and
     * is simply drawn before the water.
     */
    public static Model translucent(ResourceLocation texture) {
        return billboard(texture, Transparency.ORDER_INDEPENDENT);
    }

    public static Model absorbance(ResourceLocation texture) {
        return BILLBOARDS.computeIfAbsent(
                new Key(ParticleQuad.INSTANCE, texture, Transparency.ORDER_INDEPENDENT, CutoutShaders.EPSILON,
                        ABSORBANCE_SHADERS),
                key -> new SingleMeshModel(key.mesh(), SimpleMaterial.builder()
                        .texture(key.texture())
                        .transparency(key.transparency())
                        .cutout(key.cutout())
                        .shaders(key.shaders())
                        .writeMask(WriteMask.COLOR)
                        .fog(FogShaders.NONE)
                        .blur(true)
                        .backfaceCulling(false)
                        .cardinalLightingMode(CardinalLightingMode.OFF)
                        .mipmap(false)
                        .build()));
    }

    public static Model cutout(ResourceLocation texture) {
        return billboard(texture, Transparency.OPAQUE, CutoutShaders.ONE_TENTH);
    }

    /**
     * A cutout billboard drawn from a window of an atlas rather than from a texture of its own — see
     * {@link ParticleQuad#ofUv}. Opaque with a cutout, because that is what a chip of a solid block is.
     */
    public static Model sprite(QuadMesh mesh, ResourceLocation atlas) {
        return billboard(mesh, atlas, Transparency.OPAQUE, CutoutShaders.ONE_TENTH);
    }

    public static Model blended(ResourceLocation texture) {
        return BILLBOARDS.computeIfAbsent(new Key(ParticleQuad.INSTANCE, texture, Transparency.TRANSLUCENT,
                        CutoutShaders.EPSILON, StandardMaterialShaders.DEFAULT),
                key -> new SingleMeshModel(key.mesh(),
                        SimpleMaterial.builder()
                                .texture(key.texture())
                                .transparency(Transparency.TRANSLUCENT)
                                .cutout(key.cutout())
                                .writeMask(WriteMask.COLOR)
                                .backfaceCulling(false)
                                .cardinalLightingMode(CardinalLightingMode.OFF)
                                .mipmap(false)
                                .build()));
    }

    public static Model billboard(ResourceLocation texture, Transparency transparency) {
        return billboard(texture, transparency, CutoutShaders.EPSILON);
    }

    public static Model billboard(ResourceLocation texture, Transparency transparency, CutoutShader cutout) {
        return billboard(ParticleQuad.INSTANCE, texture, transparency, cutout);
    }

    public static Model billboard(QuadMesh mesh, ResourceLocation texture, Transparency transparency,
                                  CutoutShader cutout) {
        return BILLBOARDS.computeIfAbsent(new Key(mesh, texture, transparency, cutout,
                        StandardMaterialShaders.DEFAULT),
                key -> new SingleMeshModel(key.mesh(),
                        material(key.texture(), key.transparency(), key.cutout())));
    }

    public static Material material(ResourceLocation texture, Transparency transparency) {
        return material(texture, transparency, CutoutShaders.EPSILON);
    }

    public static Material material(ResourceLocation texture, Transparency transparency, CutoutShader cutout) {
        return SimpleMaterial.builder()
                .texture(texture)
                .transparency(transparency)
                .cutout(cutout)
                .writeMask(writeMaskFor(transparency))
                .backfaceCulling(false)
                .cardinalLightingMode(CardinalLightingMode.OFF)
                .mipmap(false)
                .build();
    }

    /**
     * Depth for geometry that hides what is behind it, colour only for anything blended.
     *
     * <p>Flywheel draws its instances after entities and <em>before</em> translucent terrain, so a blended
     * particle that writes depth deletes the water behind it: the water pass is rejected against a depth the
     * particle had no business writing, and a puff of smoke over a lake is a puff-shaped hole in the lake
     * with the bed showing through. Additive is the clearest case — a fragment that can only add light must
     * never occlude anything — but it is equally true of translucent, whose depth is written by the OIT
     * composite instead, where it can be split around the water.
     *
     * <p>The cost of writing none is that a blended particle in front of translucent terrain is painted over
     * by it rather than drawn on top of it. That is a misordering; the depth write was an erasure.
     */
    private static WriteMask writeMaskFor(Transparency transparency) {
        return transparency == Transparency.OPAQUE ? WriteMask.COLOR_DEPTH : WriteMask.COLOR;
    }

    private record Key(QuadMesh mesh, ResourceLocation texture, Transparency transparency, CutoutShader cutout,
                       MaterialShaders shaders) {
    }
}
