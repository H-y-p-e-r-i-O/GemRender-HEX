package com.wf.gemrender.gltf;

import com.wf.gemrender.vendor.jgltf.model.NodeModel;
import org.jetbrains.annotations.Nullable;

/**
 * Import-time rig edit: joints added under existing nodes, an unskinned node's vertices weighted onto them. Added
 * joints rest at identity local, so a vertex stays in its owner's space and is skinned without inverse binds.
 */
@FunctionalInterface
public interface RigPatch {

    void patch(Rig rig);

    interface Rig {
        /** First node named {@code name}; null = absent. */
        @Nullable
        NodeModel node(String name);

        /** New joint under {@code parent}, identity rest. */
        NodeModel addJoint(NodeModel parent, String name);

        /**
         * Splits {@code owner}'s triangles crossing {@code plane} (owner space, affine) at it: weights can then change
         * exactly there. Float attributes only. Before {@link #weigh}.
         */
        void slice(NodeModel owner, Plane plane);

        /** {@code owner}'s unskinned primitives: vertex position (owner space) -> weights. */
        void weigh(NodeModel owner, Weigher weigher);
    }

    /** Signed distance, affine in position: lerp along an edge must reach 0 where the plane is. */
    @FunctionalInterface
    interface Plane {
        float distance(float x, float y, float z);
    }

    @FunctionalInterface
    interface Weigher {
        /**
         * Writes up to 4 {@code joints}/{@code weights} (sum 1) for the vertex at {@code (x, y, z)}; returns the
         * count. The owner itself is a valid joint.
         */
        int weigh(float x, float y, float z, NodeModel[] joints, float[] weights);
    }
}
