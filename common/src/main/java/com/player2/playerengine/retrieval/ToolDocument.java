package com.player2.playerengine.retrieval;

import java.util.Collections;
import java.util.List;

/**
 * Metadata for a single NPC command/tool used by the retrieval layer.
 *
 * <p>All fields are indexed via {@link #indexedText()}. The {@code keywords} field is the
 * primary mechanism for paraphrase and synonym coverage — author-supplied at authoring time
 * (Phase B2) rather than inferred at retrieval time.
 */
public final class ToolDocument {

    private final String id;
    private final String name;
    private final String description;
    private final String whenToUse;
    private final List<String> examples;
    private final List<String> keywords;
    private final List<String> categoryTags;

    public ToolDocument(String id,
                        String name,
                        String description,
                        String whenToUse,
                        List<String> examples,
                        List<String> keywords,
                        List<String> categoryTags) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.whenToUse = whenToUse;
        this.examples = Collections.unmodifiableList(examples);
        this.keywords = Collections.unmodifiableList(keywords);
        this.categoryTags = Collections.unmodifiableList(categoryTags);
    }

    public String id()                  { return id; }
    public String name()                { return name; }
    public String description()         { return description; }
    public String whenToUse()           { return whenToUse; }
    public List<String> examples()      { return examples; }
    public List<String> keywords()      { return keywords; }
    public List<String> categoryTags()  { return categoryTags; }

    /**
     * Returns the concatenation of all text fields used by {@link LexicalIndex} and
     * {@link MinHashIndex} for indexing. Keywords appear verbatim, giving synonym
     * coverage without a separate expansion step at query time.
     */
    public String indexedText() {
        StringBuilder sb = new StringBuilder();
        sb.append(name).append(' ');
        sb.append(description).append(' ');
        sb.append(whenToUse).append(' ');
        for (String e : examples)  { sb.append(e).append(' '); }
        for (String k : keywords)  { sb.append(k).append(' '); }
        return sb.toString();
    }
}
