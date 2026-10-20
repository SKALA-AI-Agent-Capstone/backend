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

@RestController
public class ReviewController {
    final ReviewStore store;final ReviewService reviews;final ViolationService violations;
    public ReviewController(ReviewStore store,ReviewService reviews,ViolationService violations){this.store=store;this.reviews=reviews;this.violations=violations;}
    @GetMapping("/api/v1/reviews") public List<ObjectNode> list(@AuthenticationPrincipal Jwt jwt){return violations.list(jwt.getClaimAsString("tenantId"));}
    @GetMapping("/api/v1/reviews/{id}") public ObjectNode get(@AuthenticationPrincipal Jwt jwt,@PathVariable String id){return violations.view(jwt.getClaimAsString("tenantId"),id);}
    @PostMapping(value="/api/v1/reviews",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ObjectNode> create(@AuthenticationPrincipal Jwt jwt,@RequestParam String metadata,
        @RequestPart List<MultipartFile> files,@RequestPart(required=false)List<MultipartFile> evidence){
        ObjectNode doc=reviews.submit(jwt,metadata,files,evidence==null?List.of():evidence,null);
        return ResponseEntity.accepted().body(violations.view(jwt.getClaimAsString("tenantId"),doc.path("id").asText()));
    }
    @PostMapping(value="/api/v1/reviews/{id}/re-review",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ObjectNode> version(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@RequestParam String metadata,
        @RequestPart List<MultipartFile> files,@RequestPart(required=false)List<MultipartFile> evidence){
        reviews.submit(jwt,metadata,files,evidence==null?List.of():evidence,id);
        return ResponseEntity.accepted().body(violations.view(jwt.getClaimAsString("tenantId"),id));
    }
    @PostMapping("/api/v1/reviews/{id}/decision")
    public ObjectNode decide(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@RequestBody JsonNode input){
        return violations.decideReview(jwt,id,input);
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
    @PostMapping("/internal/reviews/{id}/progress")
    public Map<String,Boolean> progress(@PathVariable String id,@RequestHeader("X-Run-Token")String token,@RequestBody JsonNode body){
        int no=body.path("versionNo").asInt();reviews.authorizeRun(id,no,token);
        String tenant=store.jdbc().queryForObject("SELECT tenant_id::text FROM app.reviews WHERE id=CAST(? AS uuid)",String.class,id);
        int step=body.path("step").asInt(-1);if(step<0||step>4)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"INVALID_STEP");
        store.mutate(tenant,id,d->((ArrayNode)ReviewStore.version(d,no).path("steps")).set(step,store.mapper().getNodeFactory().numberNode(100)));
        return Map.of("ok",true);
    }
}
