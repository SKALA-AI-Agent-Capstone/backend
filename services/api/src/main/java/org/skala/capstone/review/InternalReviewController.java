package org.skala.capstone.review;

import org.skala.capstone.review.model.ReviewDocument;

import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.skala.capstone.evidence.EvidenceService;
import org.skala.capstone.review.events.ReviewEvents;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class InternalReviewController {
    private final ReviewRepository store;
    private final ReviewAssetService assets;
    private final RunTokenRepository tokens;
    private final ReviewEvents events;
    private final EvidenceService productEvidence;
    private final com.fasterxml.jackson.databind.ObjectMapper json;

    public InternalReviewController(
            ReviewRepository store,
            ReviewAssetService assets,
            RunTokenRepository tokens,
            ReviewEvents events,
            EvidenceService productEvidence,
            com.fasterxml.jackson.databind.ObjectMapper json) {
        this.store = store;
        this.assets = assets;
        this.tokens = tokens;
        this.events = events;
        this.productEvidence = productEvidence;
        this.json = json;
    }

    @GetMapping("/internal/reviews/{id}/assets/{assetId}")
    public ResponseEntity<byte[]> asset(
            @PathVariable String id,
            @PathVariable String assetId,
            @RequestParam int versionNo,
            @RequestHeader("X-Run-Token") String token) {
        authorizeRun(id, versionNo, token);
        requireActiveRun(id, versionNo);
        var asset = assets.readRunAsset(id, versionNo, assetId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(asset.mimeType()))
                .body(asset.bytes());
    }

    /** 이 검토의 제품에 등록된 증빙의 읽기 결과. 검토는 증빙 파일을 다시 읽지 않고 이것을 쓴다. */
    @GetMapping("/internal/reviews/{id}/product-evidence")
    public Map<String, Object> productEvidence(
            @PathVariable String id, @RequestParam int versionNo, @RequestHeader("X-Run-Token") String token) {
        authorizeRun(id, versionNo, token);
        requireActiveRun(id, versionNo);
        return Map.of("documents", productEvidence.forRun(id));
    }

    /**
     * Agent progress events: step_done marks the stored step, and every event is relayed to open
     * SSE streams.
     */
    @PostMapping("/internal/reviews/{id}/events")
    public Map<String, Boolean> agentEvent(
            @PathVariable String id,
            @RequestHeader("X-Run-Token") String token,
            @RequestBody ObjectNode body) {
        int no = body.path("versionNo").asInt();
        authorizeRun(id, no, token);
        String type = body.path("type").asText();
        if (!ReviewEvents.AGENT_TYPES.contains(type))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_EVENT_TYPE");
        ObjectNode data = json.createObjectNode();
        if (type.equals("step_done")) {
            int step = body.path("step").asInt(-1);
            if (step < 0 || step > 4)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_STEP");
            String tenant = store.tenant(id);
            store.mutate(tenant, id, d -> ReviewDocument.from(d).version(no).completeStep(step));
            data.put("step", step);
        } else {
            String tool = body.path("tool").asText("");
            if (!tool.matches("[a-z_]{1,64}"))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_TOOL");
            data.put("tool", tool);
            if (type.equals("tool_finished")) data.put("ok", body.path("ok").asBoolean(true));
        }
        events.publishAfterCommit(id, no, type, data);
        return Map.of("ok", true);
    }

    /**
     * 원본이 DB에 보존되고 실행 토큰은 지워지지 않으므로, 실행이 끝난 버전의 원본은 내주지 않는다. Agent와 Business MCP는 분석 중(queued,
     * analyzing)에만 원본을 읽는다.
     */
    private void requireActiveRun(String id, int no) {
        var version = ReviewDocument.from(store.get(store.tenant(id), id)).version(no);
        if (!version.status().running())
            throw new ResponseStatusException(HttpStatus.GONE, "RUN_FINISHED");
    }

    private void authorizeRun(String id, int no, String runToken) {
        var rows = tokens.find(id, no);
        String digest =
                ReviewAssetService.hash(
                        Objects.toString(runToken, "").getBytes(StandardCharsets.UTF_8));
        if (rows.size() != 1
                || !MessageDigest.isEqual(
                        rows.getFirst()
                                .get("token_hash")
                                .toString()
                                .getBytes(StandardCharsets.UTF_8),
                        digest.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "RUN_SCOPE_MISMATCH");
    }
}
