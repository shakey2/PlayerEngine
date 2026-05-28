package com.player2.playerengine.retrieval;

import java.util.List;

/** Hits plus deterministic confidence for one retrieval pass (Phase B5). */
public record RetrievalResult(List<RetrievalHit> hits, RetrievalConfidence confidence) {}
