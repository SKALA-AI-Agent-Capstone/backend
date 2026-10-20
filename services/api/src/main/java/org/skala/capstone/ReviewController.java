package org.skala.capstone;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.Instant;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class ReviewController {
    final ReviewStore store;final ReviewService reviews;
    public ReviewController(ReviewStore store,ReviewService reviews){this.store=store;this.reviews=reviews;}
    @GetMapping("/api/v1/reviews") public List<ObjectNode> list(@AuthenticationPrincipal Jwt jwt){return store.list(jwt.getClaimAsString("tenantId"));}
    @GetMapping("/api/v1/reviews/{id}") public ObjectNode get(@AuthenticationPrincipal Jwt jwt,@PathVariable String id){return store.get(jwt.getClaimAsString("tenantId"),id);}
    @PostMapping(value="/api/v1/reviews",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ObjectNode> create(@AuthenticationPrincipal Jwt jwt,@RequestParam String metadata,
        @RequestPart List<MultipartFile> files,@RequestPart(required=false)List<MultipartFile> evidence){
        return ResponseEntity.accepted().body(reviews.submit(jwt,metadata,files,evidence==null?List.of():evidence,null));
    }
    @PostMapping(value="/api/v1/reviews/{id}/re-review",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ObjectNode> version(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@RequestParam String metadata,
        @RequestPart List<MultipartFile> files,@RequestPart(required=false)List<MultipartFile> evidence){
        return ResponseEntity.accepted().body(reviews.submit(jwt,metadata,files,evidence==null?List.of():evidence,id));
    }
    @PostMapping("/api/v1/reviews/{id}/decision")
    public ObjectNode decide(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@RequestBody JsonNode input){
        if(!Set.of("admin","reviewer").contains(jwt.getClaimAsString("role")))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"REVIEWER_REQUIRED");
        String state=input.path("state").asText(),comment=input.path("comment").asText().trim(),tenant=jwt.getClaimAsString("tenantId");int no=input.path("versionNo").asInt();
        if(!Set.of("approved","rejected","changes_requested").contains(state) || comment.isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"DECISION_AND_REVIEW_NOTE_REQUIRED");
        // Preserve the decision even if Agent resume is temporarily unavailable.
        ObjectNode doc=store.mutate(tenant,id,d->{
            ObjectNode v=ReviewStore.latest(d);
            if(v.path("no").asInt()!=no || !v.path("status").asText().equals("done"))throw new ResponseStatusException(HttpStatus.CONFLICT,"VERSION_NOT_READY");
            if(!v.path("approval").path("state").asText().equals("pending"))throw new ResponseStatusException(HttpStatus.CONFLICT,"ALREADY_DECIDED");
            ObjectNode approval=(ObjectNode)v.path("approval");approval.put("state",state);
            String by=store.jdbc().queryForObject("SELECT name FROM app.users WHERE id=CAST(? AS uuid)",String.class,jwt.getSubject());
            approval.withArray("history").add(store.mapper().createObjectNode().put("action",state).put("by",by).put("role",jwt.getClaimAsString("role")).put("comment",comment).put("at",Instant.now().toString()));
            approval.put("agentResume","pending");
        });
        try{
            JsonNode command=store.mapper().createObjectNode().put("reviewId",id).put("tenantId",tenant).put("versionNo",no).put("state",state);
            reviews.callAgent("/internal/runs/resume",command);
            doc=store.mutate(tenant,id,d->((ObjectNode)ReviewStore.version(d,no).path("approval")).put("agentResume","completed"));
        }catch(Exception ignored){ }
        return doc;
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
