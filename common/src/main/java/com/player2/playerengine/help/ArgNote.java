package com.player2.playerengine.help;

/**
 * A single per-argument help note for a {@link HelpEntry}.
 *
 * <p>{@code argName} is the English argument name as it appears in the brigadier usage string
 * (e.g. {@code "username"}, {@code "toolId"}); it is NEVER translated. {@code noteKey} is a
 * {@code Component.translatable} key whose value is human prose describing the argument and IS
 * translated.</p>
 *
 * <p>Authoring constraint (Layer-1 lint): {@code noteKey} must be passed as a plain double-quoted
 * String literal at every callsite — no variable indirection or concatenation.</p>
 */
public record ArgNote(String argName, String noteKey) {

    public ArgNote {
        if (argName == null || argName.isBlank()) {
            throw new IllegalArgumentException("ArgNote.argName must be non-blank");
        }
        if (noteKey == null || noteKey.isBlank()) {
            throw new IllegalArgumentException("ArgNote.noteKey must be a non-blank translatable key");
        }
    }
}
