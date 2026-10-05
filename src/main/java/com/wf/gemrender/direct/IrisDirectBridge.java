package com.wf.gemrender.direct;

import java.lang.reflect.Method;

public final class IrisDirectBridge {
    private static boolean initialized;
    private static boolean available;

    private static Object irisApiInstance;
    private static Method isShaderPackInUseMethod;

    private static Method getPipelineManagerMethod;
    private static Method getPipelineNullableMethod;
    private static Method getShaderMapMethod;
    private static Method getShaderMethod;
    private static Object handCutoutKey;
    private static Object entitiesCutoutKey;
    private static Method applyMethod;

    private IrisDirectBridge() {
    }

    private static void init() {
        if (initialized) {
            return;
        }
        initialized = true;

        try {
            Class<?> irisApiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Method getInstance = irisApiClass.getMethod("getInstance");
            irisApiInstance = getInstance.invoke(null);
            isShaderPackInUseMethod = irisApiClass.getMethod("isShaderPackInUse");

            Class<?> irisClass = Class.forName("net.irisshaders.iris.Iris");
            getPipelineManagerMethod = irisClass.getMethod("getPipelineManager");

            Class<?> pipelineManagerClass = Class.forName("net.irisshaders.iris.pipeline.PipelineManager");
            getPipelineNullableMethod = pipelineManagerClass.getMethod("getPipelineNullable");

            Class<?> irisRenderingPipelineClass = Class.forName("net.irisshaders.iris.pipeline.IrisRenderingPipeline");
            getShaderMapMethod = irisRenderingPipelineClass.getMethod("getShaderMap");

            Class<?> shaderMapClass = Class.forName("net.irisshaders.iris.pipeline.programs.ShaderMap");
            Class<?> shaderKeyClass = Class.forName("net.irisshaders.iris.pipeline.programs.ShaderKey");
            getShaderMethod = shaderMapClass.getMethod("getShader", shaderKeyClass);

            handCutoutKey = Enum.valueOf((Class<Enum>) shaderKeyClass.asSubclass(Enum.class), "HAND_CUTOUT");
            try {
                entitiesCutoutKey = Enum.valueOf((Class<Enum>) shaderKeyClass.asSubclass(Enum.class), "ENTITIES_CUTOUT");
            } catch (Throwable ignored) {
            }

            Class<?> shaderInstanceClass = Class.forName("net.minecraft.client.renderer.ShaderInstance");
            applyMethod = shaderInstanceClass.getMethod("apply");

            available = true;
        } catch (Throwable t) {
            available = false;
        }
    }

    public static boolean isShaderPackInUse() {
        init();
        if (!available || irisApiInstance == null || isShaderPackInUseMethod == null) {
            return false;
        }
        try {
            return (boolean) isShaderPackInUseMethod.invoke(irisApiInstance);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void preparePass(DirectPass pass) {
        if (!isShaderPackInUse()) {
            return;
        }

        Object key = null;
        if (pass == DirectPass.HAND) {
            key = handCutoutKey;
        } else if (pass == DirectPass.LEVEL) {
            key = entitiesCutoutKey;
        }

        if (key == null) {
            return;
        }

        try {
            Object pipelineManager = getPipelineManagerMethod.invoke(null);
            if (pipelineManager == null) {
                return;
            }

            Object pipeline = getPipelineNullableMethod.invoke(pipelineManager);
            if (pipeline == null || !getShaderMapMethod.getDeclaringClass().isInstance(pipeline)) {
                return;
            }

            Object shaderMap = getShaderMapMethod.invoke(pipeline);
            if (shaderMap == null) {
                return;
            }

            Object shader = getShaderMethod.invoke(shaderMap, key);
            if (shader == null) {
                return;
            }

            applyMethod.invoke(shader);
        } catch (Throwable ignored) {
        }
    }
}
