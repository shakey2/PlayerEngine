package com.player2.playerengine.player2api;

import java.util.Set;

/**
 * Lightweight self-test for the mention parser.
 *
 * Not invoked automatically; useful for quick manual verification.
 */
public final class CallByNameMentionParserSelfTest {
    private CallByNameMentionParserSelfTest() {
    }

    public static void main(String[] args) {
        // Enable assertions with -ea when running manually.

        var bots = Set.of("ellie", "brad", "chatgpt");

        var r1 = CallByNameMentionParser.parse("I love the color purple ellie, what about you?", bots);
        assert r1.intents().stream().anyMatch(i -> i instanceof CallByNameMentionParser.MentionIntent.Unqualified u
                && "ellie".equals(CallByNameMentionParser.normalizeKey(u.bot())));

        var r2 = CallByNameMentionParser.parse(
                "Hey chatgpt, did you see what Ellie just did? what about you Brad?",
                bots);
        assert r2.intents().stream().filter(i -> i instanceof CallByNameMentionParser.MentionIntent.Unqualified).count() >= 3;

        var r3 = CallByNameMentionParser.parse("hey Rick's Ellie, what did you think about my ellie?", bots);
        assert r3.intents().stream().anyMatch(i -> i instanceof CallByNameMentionParser.MentionIntent.Qualified);

        var r4 = CallByNameMentionParser.parse("@\"Chat GPT\": hello there", Set.of("chat gpt"));
        assert r4.stripLeadingAddressing().isPresent();
    }
}

