package com.player2.playerengine.modintelligence.capability;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public final class CapabilityGson {
    private static final Gson GSON = new GsonBuilder().create();

    private CapabilityGson() {}

    public static Gson instance() {
        return GSON;
    }
}
