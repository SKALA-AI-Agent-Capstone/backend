package org.skala.capstone.violation;

import org.skala.capstone.review.model.ReviewDocument;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import java.util.*;
import org.skala.capstone.review.ReviewRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ReviewViewAssembler {
    private final ReviewRepository reviews;
    private final ViolationRepository violations;
    private final ObjectMapper json;
    private final int humanRewriteLimit;

    public ReviewViewAssembler(ReviewRepository reviews, ViolationRepository violations, ObjectMapper json,
            @Value("${capstone.review.human-rewrite-limit:3}") int humanRewriteLimit) {
        this.reviews = reviews;
        this.violations = violations;
        this.json = json;
        this.humanRewriteLimit = humanRewriteLimit;
    }

    public ObjectNode view(String tenant, String id) {
        UUID.fromString(id);
        ObjectNode doc = reviews.get(tenant, id);
        attach(doc, id);
        return doc;
    }

    public List<ObjectNode> list(String tenant) {
        Map<String, ObjectNode> progress = new HashMap<>();
        for (var row : violations.progress(tenant)) {
            progress.put(row.reviewId(), hitlNode(row.total(), row.undecided()));
        }
        List<ObjectNode> docs = reviews.list(tenant);
        for (ObjectNode doc : docs) {
            ObjectNode hitl = progress.get(doc.path("id").asText());
            if (hitl != null) doc.set("hitl", hitl);
        }
        return docs;
    }

    private void attach(ObjectNode doc, String id) {
        Map<String, String> findingIds = new HashMap<>();
        Map<String, Integer> order = new HashMap<>();
        if (!doc.withArray("versions").isEmpty()) {
            int index = 0;
            for (JsonNode finding : ReviewDocument.from(doc).latestVersion().result().snapshot().path("findings")) {
                String violationId = finding.path("violationId").asText("");
                if (!violationId.isEmpty()) {
                    findingIds.put(violationId, finding.path("id").asText(null));
                    order.put(violationId, index);
                }
                index++;
            }
        }
        Map<String, ArrayNode> rewrites = new HashMap<>();
        for (var row : violations.rewrites(id)) {
            rewrites.computeIfAbsent(row.violationId(), key -> json.createArrayNode()).add(json.createObjectNode()
                .put("attemptNo", row.attempt()).put("text", row.text()).put("requestedByHuman", row.human())
                .put("retryNote", row.note()).put("createdAt", iso(row.createdAt())));
        }
        List<ObjectNode> rows = new ArrayList<>();
        for (var row : violations.violations(id)) {
            ArrayNode history = rewrites.getOrDefault(row.id(), json.createArrayNode());
            int human = 0;
            for (JsonNode rewrite : history) if (rewrite.path("requestedByHuman").asBoolean()) human++;
            ObjectNode violation = json.createObjectNode().put("id", row.id()).put("findingId", findingIds.get(row.id()))
                .put("textExcerpt", row.excerpt()).put("severity", row.severity()).put("rewriteSuggestion", row.suggestion())
                .put("rewriteAttemptCount", row.attempts()).put("humanRetryCount", human).put("humanRetryLimit", humanRewriteLimit)
                .put("finalResolution", row.resolution()).put("finalText", row.finalText()).put("finalTextValidated", row.validated())
                .put("decidedBy", row.decidedBy()).put("decidedByName", row.decidedByName()).put("decidedAt", iso(row.decidedAt()));
            violation.set("rewrites", history);
            rows.add(violation);
        }
        rows.sort(Comparator.comparing(row -> order.getOrDefault(row.path("id").asText(), Integer.MAX_VALUE)));
        doc.set("violations", json.valueToTree(rows));
        int undecided = (int) rows.stream().filter(row -> row.path("finalResolution").isNull()).count();
        doc.set("hitl", hitlNode(rows.size(), undecided));
    }

    private ObjectNode hitlNode(int total, int undecided) {
        return json.createObjectNode().put("violationCount", total).put("undecidedCount", undecided);
    }

    private static String iso(OffsetDateTime at) {
        return at == null ? null : at.toInstant().toString();
    }
}
