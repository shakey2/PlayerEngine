package com.player2.playerengine.modintelligence.inspect;

import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;

public final class InspectionContext {
    private final String minecraftVersion;
    private final String loader;
    private final int inspectorVersion;
    private final HolderLookup.Provider registryAccess;

    public InspectionContext(String minecraftVersion, String loader, int inspectorVersion,
                             HolderLookup.Provider registryAccess) {
        this.minecraftVersion = minecraftVersion;
        this.loader = loader;
        this.inspectorVersion = inspectorVersion;
        this.registryAccess = registryAccess;
    }

    public String getMinecraftVersion() { return minecraftVersion; }
    public String getLoader() { return loader; }
    public int getInspectorVersion() { return inspectorVersion; }
    public HolderLookup.Provider getRegistryAccess() { return registryAccess; }

    public String sourceModId(ResourceLocation id) {
        return id.getNamespace();
    }
}
