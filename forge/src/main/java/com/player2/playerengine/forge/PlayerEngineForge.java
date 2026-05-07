package com.player2.playerengine.forge;

import com.player2.playerengine.PlayerEngine;
import com.player2.playerengine.PlayerEngineClient;
import dev.architectury.platform.forge.EventBuses;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;


@Mod(PlayerEngine.MOD_ID)
public final class PlayerEngineForge {
    public PlayerEngineForge() {
        // Submit our event bus to let Architectury API register our content on the right time.
        EventBuses.registerModEventBus(PlayerEngine.MOD_ID, FMLJavaModLoadingContext.get().getModEventBus());
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::setup);
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::clientSetup);

    }

    private void setup(final FMLCommonSetupEvent event) {
        PlayerEngine.onInitialize();
    }

    private void clientSetup(final FMLClientSetupEvent event) {
        PlayerEngineClient.onInitializeClient();
    }
}
