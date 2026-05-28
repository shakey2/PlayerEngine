package com.player2.playerengine.retrieval.learning;

import java.util.List;
import java.util.Optional;

public record DeepCheckResponse(
        int schemaVersion,
        List<String> retryQueries,
        Optional<AliasLearnSuggestion> learn
) {}
