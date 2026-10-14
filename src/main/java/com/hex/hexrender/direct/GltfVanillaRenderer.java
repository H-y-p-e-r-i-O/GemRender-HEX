package com.hex.hexrender.direct;

import com.hex.hexrender.gltf.GemRenderGltfModel;
import com.hex.hexrender.gltf.GltfAnimation;
import com.hex.hexrender.gltf.GltfMaterial;
import com.hex.hexrender.gltf.GltfMesh;
import com.hex.hexrender.gltf.GltfPose;
import com.hex.hexrender.gltf.MeshGeometry;
import com.hex.hexrender.gltf.blend.AnimationBlend;
import com.hex.hexrender.texture.VariantUv;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.engine_room.flywheel.api.model.Mesh;
import dev.engine_room.flywheel.api.model.Model;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.Arrays;

/**
 * Universal software-skinning renderer that outputs directly to Minecraft's {@link VertexConsumer}.
 * <p>
 * This bypasses the custom instanced OpenGL shader pipeline used by {@link DirectRenderer} when
 * an Iris/Oculus shaderpack is active (or when vanilla compatibility is desired). Because vertices
 * are routed directly through Minecraft's {@link MultiBufferSource}, Iris intercepts and shades
 * them using the active shaderpack's own native gbuffer/entity/hand/shadow programs.
 * <p>
 * This guarantees 100% compatibility with all shaderpacks (Bliss, Photon, Solas, Complementary,
 * BSL, AstraLex, Kappa, SEUS, Nostalgia, etc.) without corrupted G-buffers, rainbow static,
 * or neon glows.
 */
public final class GltfVanillaRenderer {

    private static final class ScratchHolder {
        final GltfPose.Scratch poseScratch = new GltfPose.Scratch();
        Matrix4f[] palette = new Matrix4f[0];

        Matrix4f[] palette(int size) {
            if (palette.length < size) {
                int grown = palette.length;
                palette = Arrays.copyOf(palette, size);
                for (int i = grown; i < size; i++) {
                    palette[i] = new Matrix4f();
                }
            }
            return palette;
        }
    }

    private static final class TransformBuffers {
        float[] positions = new float[1024 * 3];
        float[] normals = new float[1024 * 3];

        void ensureCapacity(int vertexCount) {
            int needed = vertexCount * 3;
            if (positions.length < needed) {
                int newCap = Math.max(positions.length * 2, needed);
                positions = new float[newCap];
                normals = new float[newCap];
            }
        }
    }

    private static final ThreadLocal<ScratchHolder> SCRATCH = ThreadLocal.withInitial(ScratchHolder::new);
    private static final ThreadLocal<TransformBuffers> BUFFERS = ThreadLocal.withInitial(TransformBuffers::new);

    private GltfVanillaRenderer() {
    }

    public static void render(
            GemRenderGltfModel model,
            @Nullable GltfAnimation clip,
            float seconds,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int light,
            int overlay,
            int tint,
            VariantUv variant,
            TextureSlots slots
    ) {
        Matrix4f[] palette = null;
        if (model.jointCount() > 0) {
            ScratchHolder scratch = SCRATCH.get();
            palette = scratch.palette(model.jointCount());
            GltfPose.evaluate(model.layout(), clip, seconds, palette, model.morphs(), null, scratch.poseScratch);
        }
        renderWithPalette(model, palette, poseStack, bufferSource, light, overlay, tint, variant, slots);
    }

    public static void render(
            GemRenderGltfModel model,
            AnimationBlend blend,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int light,
            int overlay,
            int tint,
            VariantUv variant,
            TextureSlots slots
    ) {
        Matrix4f[] palette = null;
        if (model.jointCount() > 0) {
            ScratchHolder scratch = SCRATCH.get();
            palette = scratch.palette(model.jointCount());
            GltfPose.evaluate(model.layout(), blend, palette, model.morphs(), null, scratch.poseScratch);
        }
        renderWithPalette(model, palette, poseStack, bufferSource, light, overlay, tint, variant, slots);
    }

    public static void render(
            GemRenderGltfModel model,
            float[] state,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int light,
            int overlay,
            int tint,
            VariantUv variant,
            TextureSlots slots
    ) {
        Matrix4f[] palette = null;
        if (model.jointCount() > 0) {
            ScratchHolder scratch = SCRATCH.get();
            palette = scratch.palette(model.jointCount());
            GltfPose.evaluate(model.layout(), state, palette, model.morphs(), null, scratch.poseScratch);
        }
        renderWithPalette(model, palette, poseStack, bufferSource, light, overlay, tint, variant, slots);
    }

    public static void render(
            GemRenderGltfModel model,
            @Nullable Matrix4f[] palette,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int light,
            int overlay,
            int tint,
            VariantUv variant,
            TextureSlots slots
    ) {
        renderWithPalette(model, palette, poseStack, bufferSource, light, overlay, tint, variant, slots);
    }

    public static void renderWithPalette(
            GemRenderGltfModel model,
            @Nullable Matrix4f[] palette,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int light,
            int overlay,
            int tint,
            VariantUv variant,
            TextureSlots slots
    ) {
        // 1. Regular configured meshes
        for (Model.ConfiguredMesh configured : model.model().meshes()) {
            DirectMaterial material = DirectMaterial.of(configured.material());
            if (material == null) {
                continue;
            }

            Mesh mesh = configured.mesh();
            if (!(mesh instanceof GltfMesh gltfMesh)) {
                continue;
            }

            MeshGeometry geometry = gltfMesh.geometry();
            ResourceLocation texture = material.texture();
            RenderType renderType = getRenderType(texture, material.blended(), material.doubleSided());
            VertexConsumer consumer = bufferSource.getBuffer(renderType);

            renderGeometry(geometry, palette, poseStack, consumer, light, overlay, tint, variant);
        }

        // 2. Slot meshes (substitutable textures)
        for (GemRenderGltfModel.SlotMesh slot : model.slotMeshes()) {
            ResourceLocation texture = slots != null ? slots.texture(slot.slot()) : null;
            if (texture == null) {
                continue;
            }

            GltfMaterial mat = slot.material();
            boolean blended = mat.alphaMode() == GltfMaterial.AlphaMode.BLEND;
            RenderType renderType = getRenderType(texture, blended, mat.doubleSided());
            VertexConsumer consumer = bufferSource.getBuffer(renderType);

            renderGeometry(slot.mesh().geometry(), palette, poseStack, consumer, light, overlay, tint, variant);
        }
    }

    public static void renderToConsumer(
            GemRenderGltfModel model,
            float[] state,
            PoseStack poseStack,
            VertexConsumer consumer,
            int light,
            int overlay,
            int tint,
            VariantUv variant
    ) {
        Matrix4f[] palette = null;
        if (model.jointCount() > 0) {
            ScratchHolder scratch = SCRATCH.get();
            palette = scratch.palette(model.jointCount());
            GltfPose.evaluate(model.layout(), state, palette, model.morphs(), null, scratch.poseScratch);
        }
        renderToConsumer(model, palette, poseStack, consumer, light, overlay, tint, variant);
    }

    public static void renderToConsumer(
            GemRenderGltfModel model,
            @Nullable Matrix4f[] palette,
            PoseStack poseStack,
            VertexConsumer consumer,
            int light,
            int overlay,
            int tint,
            VariantUv variant
    ) {
        for (Model.ConfiguredMesh configured : model.model().meshes()) {
            Mesh mesh = configured.mesh();
            if (!(mesh instanceof GltfMesh gltfMesh)) {
                continue;
            }
            renderGeometry(gltfMesh.geometry(), palette, poseStack, consumer, light, overlay, tint, variant);
        }
    }

    public static RenderType getRenderType(ResourceLocation texture, boolean blended, boolean doubleSided) {
        if (blended) {
            return RenderType.entityTranslucent(texture);
        }
        if (doubleSided) {
            return RenderType.entityCutoutNoCull(texture);
        }
        return RenderType.entityCutout(texture);
    }

    private static void renderGeometry(
            MeshGeometry geometry,
            @Nullable Matrix4f[] palette,
            PoseStack poseStack,
            VertexConsumer consumer,
            int light,
            int overlay,
            int tint,
            VariantUv variant
    ) {
        int vertexCount = geometry.vertexCount();
        if (vertexCount == 0) {
            return;
        }

        TransformBuffers tb = BUFFERS.get();
        tb.ensureCapacity(vertexCount);
        float[] outPositions = tb.positions;
        float[] outNormals = tb.normals;

        if (palette == null || palette.length == 0) {
            for (int v = 0; v < vertexCount; v++) {
                int base = v * 3;
                outPositions[base] = geometry.position(v, 0);
                outPositions[base + 1] = geometry.position(v, 1);
                outPositions[base + 2] = geometry.position(v, 2);

                outNormals[base] = geometry.normal(v, 0);
                outNormals[base + 1] = geometry.normal(v, 1);
                outNormals[base + 2] = geometry.normal(v, 2);
            }
        } else {
            for (int v = 0; v < vertexCount; v++) {
                float px = geometry.position(v, 0);
                float py = geometry.position(v, 1);
                float pz = geometry.position(v, 2);

                float nx = geometry.normal(v, 0);
                float ny = geometry.normal(v, 1);
                float nz = geometry.normal(v, 2);

                int packed = geometry.packedJoints(v);
                int j0 = packed & 0xFF;
                int j1 = (packed >>> 8) & 0xFF;
                int j2 = (packed >>> 16) & 0xFF;
                int j3 = (packed >>> 24) & 0xFF;

                float w0 = geometry.weightChannel(v, 0);
                float w1 = geometry.weightChannel(v, 1);
                float w2 = geometry.weightChannel(v, 2);
                float w3 = geometry.weightChannel(v, 3);

                float tx = 0.0f, ty = 0.0f, tz = 0.0f;
                float tnx = 0.0f, tny = 0.0f, tnz = 0.0f;

                if (w0 >= 0.999f && j0 < palette.length) {
                    Matrix4f m = palette[j0];
                    tx = m.m00() * px + m.m10() * py + m.m20() * pz + m.m30();
                    ty = m.m01() * px + m.m11() * py + m.m21() * pz + m.m31();
                    tz = m.m02() * px + m.m12() * py + m.m22() * pz + m.m32();

                    tnx = m.m00() * nx + m.m10() * ny + m.m20() * nz;
                    tny = m.m01() * nx + m.m11() * ny + m.m21() * nz;
                    tnz = m.m02() * nx + m.m12() * ny + m.m22() * nz;
                } else {
                    if (w0 > 0.0f && j0 < palette.length) {
                        Matrix4f m = palette[j0];
                        tx += w0 * (m.m00() * px + m.m10() * py + m.m20() * pz + m.m30());
                        ty += w0 * (m.m01() * px + m.m11() * py + m.m21() * pz + m.m31());
                        tz += w0 * (m.m02() * px + m.m12() * py + m.m22() * pz + m.m32());

                        tnx += w0 * (m.m00() * nx + m.m10() * ny + m.m20() * nz);
                        tny += w0 * (m.m01() * nx + m.m11() * ny + m.m21() * nz);
                        tnz += w0 * (m.m02() * nx + m.m12() * ny + m.m22() * nz);
                    }
                    if (w1 > 0.0f && j1 < palette.length) {
                        Matrix4f m = palette[j1];
                        tx += w1 * (m.m00() * px + m.m10() * py + m.m20() * pz + m.m30());
                        ty += w1 * (m.m01() * px + m.m11() * py + m.m21() * pz + m.m31());
                        tz += w1 * (m.m02() * px + m.m12() * py + m.m22() * pz + m.m32());

                        tnx += w1 * (m.m00() * nx + m.m10() * ny + m.m20() * nz);
                        tny += w1 * (m.m01() * nx + m.m11() * ny + m.m21() * nz);
                        tnz += w1 * (m.m02() * nx + m.m12() * ny + m.m22() * nz);
                    }
                    if (w2 > 0.0f && j2 < palette.length) {
                        Matrix4f m = palette[j2];
                        tx += w2 * (m.m00() * px + m.m10() * py + m.m20() * pz + m.m30());
                        ty += w2 * (m.m01() * px + m.m11() * py + m.m21() * pz + m.m31());
                        tz += w2 * (m.m02() * px + m.m12() * py + m.m22() * pz + m.m32());

                        tnx += w2 * (m.m00() * nx + m.m10() * ny + m.m20() * nz);
                        tny += w2 * (m.m01() * nx + m.m11() * ny + m.m21() * nz);
                        tnz += w2 * (m.m02() * nx + m.m12() * ny + m.m22() * nz);
                    }
                    if (w3 > 0.0f && j3 < palette.length) {
                        Matrix4f m = palette[j3];
                        tx += w3 * (m.m00() * px + m.m10() * py + m.m20() * pz + m.m30());
                        ty += w3 * (m.m01() * px + m.m11() * py + m.m21() * pz + m.m31());
                        tz += w3 * (m.m02() * px + m.m12() * py + m.m22() * pz + m.m32());

                        tnx += w3 * (m.m00() * nx + m.m10() * ny + m.m20() * nz);
                        tny += w3 * (m.m01() * nx + m.m11() * ny + m.m21() * nz);
                        tnz += w3 * (m.m02() * nx + m.m12() * ny + m.m22() * nz);
                    }
                }

                float len = (float) Math.sqrt(tnx * tnx + tny * tny + tnz * tnz);
                if (len > 1e-4f) {
                    float inv = 1.0f / len;
                    tnx *= inv;
                    tny *= inv;
                    tnz *= inv;
                } else {
                    tnx = 0.0f;
                    tny = 1.0f;
                    tnz = 0.0f;
                }

                int base = v * 3;
                outPositions[base] = tx;
                outPositions[base + 1] = ty;
                outPositions[base + 2] = tz;

                outNormals[base] = tnx;
                outNormals[base + 1] = tny;
                outNormals[base + 2] = tnz;
            }
        }

        int a = (tint >>> 24) & 0xFF;
        if (a == 0) a = 255;
        int r = (tint >>> 16) & 0xFF;
        int g = (tint >>> 8) & 0xFF;
        int b = tint & 0xFF;

        float uOffset = variant != null ? variant.u() : 0.0f;
        float vOffset = variant != null ? variant.v() : 0.0f;

        PoseStack.Pose poseEntry = poseStack.last();
        int indexCount = geometry.indexCount();

        for (int i = 0; i < indexCount; i += 3) {
            int i0 = geometry.index(i);
            int i1 = geometry.index(i + 1);
            int i2 = geometry.index(i + 2);

            emitVertex(consumer, poseEntry, i0, outPositions, outNormals, geometry, uOffset, vOffset, r, g, b, a, overlay, light);
            emitVertex(consumer, poseEntry, i1, outPositions, outNormals, geometry, uOffset, vOffset, r, g, b, a, overlay, light);
            emitVertex(consumer, poseEntry, i2, outPositions, outNormals, geometry, uOffset, vOffset, r, g, b, a, overlay, light);
            emitVertex(consumer, poseEntry, i2, outPositions, outNormals, geometry, uOffset, vOffset, r, g, b, a, overlay, light);
        }
    }

    private static void emitVertex(
            VertexConsumer consumer,
            PoseStack.Pose poseEntry,
            int index,
            float[] positions,
            float[] normals,
            MeshGeometry geometry,
            float uOffset,
            float vOffset,
            int r, int g, int b, int a,
            int overlay,
            int light
    ) {
        int base = index * 3;
        float x = positions[base];
        float y = positions[base + 1];
        float z = positions[base + 2];

        float nx = normals[base];
        float ny = normals[base + 1];
        float nz = normals[base + 2];

        float u = geometry.texCoord(index, 0) + uOffset;
        float v = geometry.texCoord(index, 1) + vOffset;

        consumer.addVertex(poseEntry, x, y, z)
                .setColor(r, g, b, a)
                .setUv(u, v)
                .setOverlay(overlay)
                .setLight(light)
                .setNormal(poseEntry, nx, ny, nz);
    }
}
