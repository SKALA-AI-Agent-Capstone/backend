package org.skala.capstone;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class ReviewController {
    final ReviewStore store;final ReviewService reviews;final ViolationService violations;final ReviewEvents events;
    public ReviewController(ReviewStore store,ReviewService reviews,ViolationService violations,ReviewEvents events){this.store=store;this.reviews=reviews;this.violations=violations;this.events=events;}
    @GetMapping("/api/v1/reviews") public List<ObjectNode> list(@AuthenticationPrincipal Jwt jwt){return violations.list(jwt.getClaimAsString("tenantId"));}
    @GetMapping("/api/v1/reviews/{id}") public ObjectNode get(@AuthenticationPrincipal Jwt jwt,@PathVariable String id){return violations.view(jwt.getClaimAsString("tenantId"),id);}
    /** 위반 검토/리포트 화면에서 원본을 보여줄 때 쓴다. run-token 전용인 /internal 엔드포인트와 다른 경로다. */
    @GetMapping("/api/v1/reviews/{id}/assets/{assetId}")
    public ResponseEntity<byte[]> userAsset(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@PathVariable String assetId){
        var asset=reviews.readPersistedAsset(jwt.getClaimAsString("tenantId"),id,assetId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType(asset.mimeType())).body(asset.bytes());
    }
    @PostMapping(value="/api/v1/reviews",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ObjectNode> create(@AuthenticationPrincipal Jwt jwt,@RequestParam String metadata,
        @RequestPart List<MultipartFile> files,@RequestPart(required=false)List<MultipartFile> evidence){
        ObjectNode doc=reviews.submit(jwt,metadata,files,evidence==null?List.of():evidence);
        return ResponseEntity.accepted().body(violations.view(jwt.getClaimAsString("tenantId"),doc.path("id").asText()));
    }
    @PostMapping("/api/v1/reviews/{id}/violations/{violationId}/decision")
    public ObjectNode decideViolation(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@PathVariable String violationId,@RequestBody JsonNode input){
        return violations.decideViolation(jwt,id,violationId,input);
    }
    @PostMapping("/api/v1/reviews/{id}/violations/{violationId}/rewrite-requests")
    public ObjectNode requestRewrite(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@PathVariable String violationId,@RequestBody(required=false) JsonNode input){
        return violations.requestRewrite(jwt,id,violationId,input==null?store.mapper().createObjectNode():input);
    }
    @DeleteMapping("/api/v1/reviews/{id}") public Map<String,Boolean> delete(@AuthenticationPrincipal Jwt jwt,@PathVariable String id){
        String tenant=jwt.getClaimAsString("tenantId");var doc=store.get(tenant,id);
        if(!doc.path("authorId").asText().equals(jwt.getSubject())&&!"admin".equals(jwt.getClaimAsString("role")))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"DELETE_FORBIDDEN");
        if(Set.of("queued","analyzing").contains(ReviewStore.latest(doc).path("status").asText()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"WAIT_FOR_ANALYSIS_BEFORE_DELETE");
        try{
            reviews.callAgent("/internal/runs/delete",store.mapper().createObjectNode().put("reviewId",id).put("tenantId",tenant)
                .put("versionNo",ReviewStore.latest(doc).path("no").asInt()).put("state","rejected"));
        }catch(Exception error){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"CHECKPOINT_DELETE_PENDING_RETRY");}
        for(JsonNode v:doc.path("versions"))reviews.purge(id,v.path("no").asInt());
        store.jdbc().update("DELETE FROM app.reviews WHERE id=CAST(? AS uuid) AND tenant_id=CAST(? AS uuid)",id,tenant);
        return Map.of("ok",true);
    }
    @DeleteMapping("/api/v1/reviews") public Map<String,Integer> deleteAll(@AuthenticationPrincipal Jwt jwt){
        int count=0;for(var doc:store.list(jwt.getClaimAsString("tenantId")))
            if("admin".equals(jwt.getClaimAsString("role"))||doc.path("authorId").asText().equals(jwt.getSubject())){delete(jwt,doc.path("id").asText());count++;}
        return Map.of("deleted",count);
    }
    @GetMapping("/internal/reviews/{id}/assets/{assetId}")
    public ResponseEntity<byte[]> asset(@PathVariable String id,@PathVariable String assetId,@RequestParam int versionNo,@RequestHeader("X-Run-Token")String token){
        reviews.authorizeRun(id,versionNo,token);
        var rows=store.jdbc().queryForList("SELECT mime_type FROM app.review_assets WHERE id=CAST(? AS uuid) AND review_id=CAST(? AS uuid) AND version_no=?",assetId,id,versionNo);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"ASSET_NOT_FOUND");
        String value=reviews.readCachedAsset(assetId);
        if(value==null)throw new ResponseStatusException(HttpStatus.GONE,"ORIGINAL_EXPIRED_REUPLOAD_REQUIRED");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType(rows.getFirst().get("mime_type").toString())).body(Base64.getDecoder().decode(value));
    }
    /** Agent progress events: step_done marks the stored step, and every event is relayed to open SSE streams. */
    @PostMapping("/internal/reviews/{id}/events")
    public Map<String,Boolean> agentEvent(@PathVariable String id,@RequestHeader("X-Run-Token")String token,@RequestBody ObjectNode body){
        int no=body.path("versionNo").asInt();reviews.authorizeRun(id,no,token);
        String type=body.path("type").asText();
        if(!ReviewEvents.AGENT_TYPES.contains(type))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"INVALID_EVENT_TYPE");
        ObjectNode data=store.mapper().createObjectNode();
        if(type.equals("step_done")){
            int step=body.path("step").asInt(-1);if(step<0||step>4)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"INVALID_STEP");
            String tenant=store.jdbc().queryForObject("SELECT tenant_id::text FROM app.reviews WHERE id=CAST(? AS uuid)",String.class,id);
            store.mutate(tenant,id,d->((ArrayNode)ReviewStore.version(d,no).path("steps")).set(step,store.mapper().getNodeFactory().numberNode(100)));
            data.put("step",step);
        }else{
            String tool=body.path("tool").asText("");
            if(!tool.matches("[a-z_]{1,64}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"INVALID_TOOL");
            data.put("tool",tool);
            if(type.equals("tool_finished"))data.put("ok",body.path("ok").asBoolean(true));
        }
        events.publish(id,no,type,data);
        return Map.of("ok",true);
    }
    /**
     * Progress stream for one review (text/event-stream). The first event is a snapshot of the stored review;
     * then started, step_done, tool_started, tool_finished, retry_scheduled and finally completed or failed.
     * A review that is not running gets only the snapshot.
     */
    @GetMapping(value="/api/v1/reviews/{id}/events",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> events(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,
        @RequestHeader(value="Last-Event-ID",required=false)String lastEventId){
        ObjectNode snapshot=violations.view(jwt.getClaimAsString("tenantId"),id);
        JsonNode latest=ReviewStore.latest(snapshot);
        // 분석 중인 동안만 스트림을 연다.
        boolean running=Set.of("queued","analyzing").contains(latest.path("status").asText());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Accel-Buffering","no")
            .body(events.subscribe(id,snapshot,running,lastEventId));
    }
}
