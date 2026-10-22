package org.skala.capstone.review;

import org.skala.capstone.review.model.ReviewDocument;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.skala.capstone.review.events.ReviewEvents;
import org.skala.capstone.violation.ReviewViewAssembler;
import org.skala.capstone.violation.ViolationDecisionService;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class ReviewController {
    private final ReviewService reviews;
    private final ReviewAssetService assets;
    private final AssetThumbnailService thumbnails;
    private final ViolationDecisionService decisions;
    private final ReviewViewAssembler views;
    private final ReviewEvents events;
    private final ObjectMapper json;

    public ReviewController(
            ReviewService reviews,
            ReviewAssetService assets,
            AssetThumbnailService thumbnails,
            ViolationDecisionService decisions,
            ReviewViewAssembler views,
            ReviewEvents events,
            ObjectMapper json) {
        this.reviews = reviews;
        this.assets = assets;
        this.thumbnails = thumbnails;
        this.decisions = decisions;
        this.views = views;
        this.events = events;
        this.json = json;
    }

    /**
     * 검토 목록. view=summary면 버전마다 result 대신 분석 시각과 위험도별 문구 수만 싣는다(목록 화면용).
     * 값이 없거나 다른 값이면 예전과 같은 전체 문서다(배포 순서가 달라도 깨지지 않게 기본값을 유지한다).
     */
    @GetMapping("/api/v1/reviews")
    public List<ObjectNode> list(
            @AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) String view) {
        String tenant = jwt.getClaimAsString("tenantId");
        return "summary".equals(view) ? views.summaries(tenant) : views.list(tenant);
    }

    @GetMapping("/api/v1/reviews/{id}")
    public ObjectNode get(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return views.view(jwt.getClaimAsString("tenantId"), id);
    }

    /**
     * 원본과 썸네일은 asset id마다 내용이 바뀌지 않으므로 브라우저에 하루 캐시를 허용한다. private이라 공용 캐시(CDN)에는 남지 않는다.
     * 같은 브라우저에서는 로그아웃 뒤에도 하루 동안 캐시가 남을 수 있다.
     */
    private static final CacheControl IMMUTABLE_ASSET =
            CacheControl.maxAge(java.time.Duration.ofDays(1)).cachePrivate();

    /** 위반 검토/리포트 화면에서 원본을 보여줄 때 쓴다. run-token 전용인 /internal 엔드포인트와 다른 경로다. */
    @GetMapping("/api/v1/reviews/{id}/assets/{assetId}")
    public ResponseEntity<byte[]> userAsset(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String id,
            @PathVariable String assetId) {
        var asset = assets.readPersistedAsset(jwt.getClaimAsString("tenantId"), id, assetId);
        return ResponseEntity.ok()
                .cacheControl(IMMUTABLE_ASSET)
                .contentType(MediaType.parseMediaType(asset.mimeType()))
                .body(asset.bytes());
    }

    /** 목록 화면 썸네일(너비 160px JPEG). 만들 수 없는 원본은 404 THUMBNAIL_UNAVAILABLE이다. */
    @GetMapping("/api/v1/reviews/{id}/assets/{assetId}/thumbnail")
    public ResponseEntity<byte[]> thumbnail(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String id,
            @PathVariable String assetId) {
        byte[] image = thumbnails.thumbnail(jwt.getClaimAsString("tenantId"), id, assetId);
        return ResponseEntity.ok()
                .cacheControl(IMMUTABLE_ASSET)
                .contentType(MediaType.IMAGE_JPEG)
                .body(image);
    }

    @PostMapping(value = "/api/v1/reviews", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ObjectNode> create(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam String metadata,
            @RequestPart List<MultipartFile> files,
            @RequestPart(required = false) List<MultipartFile> evidence) {
        ObjectNode doc =
                reviews.submit(jwt, metadata, files, evidence == null ? List.of() : evidence);
        return ResponseEntity.accepted()
                .body(views.view(jwt.getClaimAsString("tenantId"), doc.path("id").asText()));
    }

    @PostMapping("/api/v1/reviews/{id}/violations/{violationId}/decision")
    public ObjectNode decideViolation(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String id,
            @PathVariable String violationId,
            @RequestBody JsonNode input) {
        return decisions.decideViolation(jwt, id, violationId, input);
    }

    @PostMapping("/api/v1/reviews/{id}/violations/{violationId}/rewrite-requests")
    public ObjectNode requestRewrite(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String id,
            @PathVariable String violationId,
            @RequestBody(required = false) JsonNode input) {
        return decisions.requestRewrite(
                jwt, id, violationId, input == null ? json.createObjectNode() : input);
    }

    @DeleteMapping("/api/v1/reviews/{id}")
    public Map<String, Boolean> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return reviews.delete(jwt, id);
    }

    @DeleteMapping("/api/v1/reviews")
    public Map<String, Integer> deleteAll(@AuthenticationPrincipal Jwt jwt) {
        return reviews.deleteAll(jwt);
    }

    /**
     * Progress stream for one review (text/event-stream). The first event is a snapshot of the
     * stored review; then started, step_done, tool_started, tool_finished, retry_scheduled and
     * finally completed or failed. A review that is not running gets only the snapshot.
     */
    @GetMapping(value = "/api/v1/reviews/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> events(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String id,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        ObjectNode snapshot = views.view(jwt.getClaimAsString("tenantId"), id);
        // 분석 중인 동안만 스트림을 연다.
        boolean running = ReviewDocument.from(snapshot).latestVersion().status().running();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("X-Accel-Buffering", "no")
                .body(events.subscribe(id, snapshot, running, lastEventId));
    }
}
