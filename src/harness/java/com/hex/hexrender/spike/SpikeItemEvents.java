package com.hex.hexrender.spike;

import com.hex.hexrender.GemRender;

import net.minecraft.core.registries.Registries;
//? if neoforge {
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;
//?} else {
/*import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.registries.RegisterEvent;
*///?}
//? if >=26.1 {
/*import com.hex.hexrender.direct.GemRenderItemRenderer;

import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
*///?}

//? if >=26.1 {
/*@EventBusSubscriber(modid = GemRender.MOD_ID)
*///?} else {
@EventBusSubscriber(modid = GemRender.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
//?}
public final class SpikeItemEvents {

	private SpikeItemEvents() {
	}

	@SubscribeEvent
	public static void onRegister(RegisterEvent event) {
		event.register(Registries.ITEM,
				helper -> helper.register(SpikeItems.SPIKE, SpikeItems.create()));
	}

	//? if >=26.1 {
	
	/*@SubscribeEvent
	public static void onClientSetup(FMLClientSetupEvent event) {
		GemRenderItemRenderer.register(SpikeItems.SPIKE, DirectSpike.itemRenderer());
	}
*///?}
}
