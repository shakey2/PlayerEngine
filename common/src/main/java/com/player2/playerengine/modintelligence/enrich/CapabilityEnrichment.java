package com.player2.playerengine.modintelligence.enrich;

public final class CapabilityEnrichment {
    public static final int SCHEMA_VERSION = 1;
    public static final String PROMPT_VERSION = "b4-v2";

    public static final int MAX_SHORT_DESCRIPTION_LEN = 240;
    public static final int MAX_ALIASES = 12;
    public static final int MAX_ALIAS_LEN = 48;
    public static final int MAX_LIKELY_USES = 12;
    public static final int MAX_LIKELY_USE_LEN = 96;
    public static final int MAX_CAPABILITY_HINTS = 8;
    public static final int MAX_UNCERTAINTY_NOTES = 8;
    public static final int MAX_UNCERTAINTY_NOTE_LEN = 140;

    private CapabilityEnrichment() {}
}
