package org.skala.capstone.review.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Agent 결과의 확장 필드는 보존하고, 과금 판단은 이 모델 한 곳에서 수행한다. */
public final class AnalysisResult {
    private final ObjectNode document;

    private AnalysisResult(ObjectNode document) {
        this.document = document.deepCopy();
    }

    public static AnalysisResult fromAgent(JsonNode response) {
        return from(response.path("result"));
    }

    public static AnalysisResult from(JsonNode result) {
        return new AnalysisResult(result instanceof ObjectNode node ? node : JsonNodeFactory.instance.objectNode());
    }

    public boolean chargeable() {
        JsonNode status = document.path("pipelineStatus");
        return "complete".equals(status.path("text").asText()) && "complete".equals(status.path("visual").asText());
    }

    public ObjectNode snapshot() {
        return document.deepCopy();
    }
}
