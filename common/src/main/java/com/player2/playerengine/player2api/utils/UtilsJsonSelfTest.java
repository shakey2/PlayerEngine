package com.player2.playerengine.player2api.utils;

import com.google.gson.JsonObject;

/**
 * Lightweight harness for LLM JSON cleanup cases.
 *
 * <p>Not invoked automatically; useful for quick manual verification.
 */
public final class UtilsJsonSelfTest {
    private UtilsJsonSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        JsonObject direct = Utils.parseCleanedJson("{\"reason\":\"ok\",\"command\":\"idle\",\"message\":\"hi\"}");
        assert "idle".equals(direct.get("command").getAsString());

        JsonObject doubleBraced = Utils.parseCleanedJson(
                "{{\"reason\":\"wrapped\",\"command\":\"follow Bastien46\",\"message\":\"j'arrive\"}}");
        assert "follow Bastien46".equals(doubleBraced.get("command").getAsString());

        JsonObject fencedDoubleBraced = Utils.parseCleanedJson(
                "```json\n{{\"reason\":\"wrapped\",\"command\":\"idle\",\"message\":\"salut\"}}\n```");
        assert "salut".equals(fencedDoubleBraced.get("message").getAsString());
    }
}
