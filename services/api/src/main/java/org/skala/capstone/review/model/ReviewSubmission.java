package org.skala.capstone.review.model;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/** DB 트랜잭션에 들어가기 전에 검증하고 읽은 입력이다. */
public record ReviewSubmission(ObjectNode metadata, List<Asset> assets) {
    public ReviewSubmission {
        metadata = metadata.deepCopy();
        assets = List.copyOf(assets);
    }

    public record Asset(String id, String name, String mime, String role, String docType, byte[] bytes) {}
}
