package org.skala.capstone.review.model;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** JSONB의 확장 필드는 보존하고 상태 변경 규칙을 한 곳에 모은다. */
public final class ReviewVersion {
    private final ObjectNode document;

    ReviewVersion(ObjectNode document) {
        this.document = document;
    }

    public int number() {
        return document.path("no").asInt();
    }

    public ReviewStatus status() {
        return ReviewStatus.from(document.path("status").asText());
    }

    public AnalysisResult result() {
        return AnalysisResult.from(document.path("result"));
    }

    public void start() {
        requireRunning();
        document.put("status", ReviewStatus.ANALYZING.value());
    }

    public void complete(AnalysisResult result) {
        requireRunning();
        document.set("result", result.snapshot());
        document.put("status", ReviewStatus.DONE.value());
        var steps = document.putArray("steps");
        for (int step = 0; step < 5; step++) steps.add(100);
    }

    public boolean fail(String error) {
        if (status() == ReviewStatus.DONE) return false;
        document.put("status", ReviewStatus.FAILED.value()).put("error", error);
        return true;
    }

    public void completeStep(int step) {
        if (step < 0 || step > 4) throw new IllegalArgumentException("INVALID_STEP");
        document.withArray("steps").set(step, com.fasterxml.jackson.databind.node.IntNode.valueOf(100));
    }

    public ObjectNode snapshot() {
        return document.deepCopy();
    }

    private void requireRunning() {
        if (!status().running()) throw new IllegalStateException("REVIEW_ALREADY_TERMINAL");
    }
}
