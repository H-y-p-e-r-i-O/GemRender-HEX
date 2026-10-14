package com.hex.hexrender.direct;

public final class DirectReload {
    private DirectReload() {
    }

    public static void run() {
        ResidentModels.freeAll();
        DirectRenderer.freeAll();
        DirectProgram.getInstance()
                .delete();
    }
}
