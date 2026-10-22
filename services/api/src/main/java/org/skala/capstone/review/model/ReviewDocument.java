package org.skala.capstone.review.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** 원본 필드와 예전 버전은 보존하면서 버전 접근과 상태 전이를 제공한다. */
public final class ReviewDocument {
    public static final class MissingVersion extends RuntimeException {}

    private final ObjectNode document;

    private ReviewDocument(ObjectNode document) {
        this.document = document;
    }

    public static ReviewDocument from(ObjectNode document) {
        return new ReviewDocument(document);
    }

    public ReviewVersion version(int number) {
        for (JsonNode node : document.withArray("versions")) {
            if (node instanceof ObjectNode version && version.path("no").asInt() == number) return new ReviewVersion(version);
        }
        throw new MissingVersion();
    }

    public ReviewVersion latestVersion() {
        var versions = document.withArray("versions");
        if (versions.isEmpty()) throw new MissingVersion();
        return new ReviewVersion((ObjectNode) versions.get(versions.size() - 1));
    }

    public ObjectNode snapshot() {
        return document.deepCopy();
    }
}
