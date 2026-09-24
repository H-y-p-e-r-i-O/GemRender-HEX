package com.wf.gemrender.fabric;

import com.wf.gemrender.render.LevelStage;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;

public final class LevelStageHooks {
    private LevelStageHooks() {
    }

    public static void init() {
        WorldRenderEvents.AFTER_ENTITIES.register(LevelStageHooks::afterEntities);
        WorldRenderEvents.BEFORE_DEBUG_RENDER.register(LevelStageHooks::beforeDebugRender);
        WorldRenderEvents.AFTER_TRANSLUCENT.register(LevelStageHooks::afterTranslucent);
        WorldRenderEvents.LAST.register(LevelStageHooks::last);
    }

    private static void afterEntities(WorldRenderContext context) {
        directFlush();
        waterAfterEntities(context);
    }

    private static void beforeDebugRender(WorldRenderContext context) {
        directFlush();
    }

    private static void afterTranslucent(WorldRenderContext context) {
        waterAfterTranslucent();
    }

    private static void last(WorldRenderContext context) {
        directFlush();
        waterAfterWeather();
    }

    //? if water {
    private static void waterAfterEntities(WorldRenderContext context) {
        com.wf.gemrender.water.WaterSplit.getInstance()
                .onAfterEntities(stageOf(context));
    }

    private static void waterAfterTranslucent() {
        com.wf.gemrender.water.WaterSplit.getInstance()
                .onAfterTranslucent();
    }

    private static void waterAfterWeather() {
        com.wf.gemrender.water.WaterSplit.getInstance()
                .onAfterWeather();
    }
    //?} else {
    /*private static void waterAfterEntities(WorldRenderContext context) {
    }

    private static void waterAfterTranslucent() {
    }

    private static void waterAfterWeather() {
    }
    *///?}

    //? if direct {
    private static void directFlush() {
        com.wf.gemrender.direct.DirectRenderer.flush(com.wf.gemrender.direct.DirectPass.LEVEL);
    }
    //?} else {
    /*private static void directFlush() {
    }
    *///?}

    //? if >=1.21 {
    private static LevelStage stageOf(WorldRenderContext context) {
        return new LevelStage(context.worldRenderer(), context.camera(), context.frustum(),
                context.matrixStack(), context.positionMatrix(), context.projectionMatrix());
    }
    //?} else {
	/*private static LevelStage stageOf(WorldRenderContext context) {
		return new LevelStage(context.worldRenderer(), context.camera(), context.frustum(),
				context.matrixStack(), null, context.projectionMatrix());
	}
*///?}
}
