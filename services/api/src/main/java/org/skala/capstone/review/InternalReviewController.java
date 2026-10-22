package org.skala.capstone.review;

import org.skala.capstone.review.model.ReviewDocument;

import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
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
    private final com.fasterxml.jackson.databind.ObjectMapper json;

    public InternalReviewController(ReviewRepository store,ReviewAssetService assets,RunTokenRepository tokens,ReviewEvents events,com.fasterxml.jackson.databind.ObjectMapper json) {
        this.store=store;this.assets=assets;this.tokens=tokens;this.events=events;this.json=json;
    }

    @GetMapping("/internal/reviews/{id}/assets/{assetId}")
    public ResponseEntity<byte[]> asset(@PathVariable String id,@PathVariable String assetId,@RequestParam int versionNo,@RequestHeader("X-Run-Token")String token){
        authorizeRun(id,versionNo,token);
        var asset=assets.readRunAsset(id,versionNo,assetId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType(asset.mimeType())).body(asset.bytes());
    }
    /** Agent progress events: step_done marks the stored step, and every event is relayed to open SSE streams. */
    @PostMapping("/internal/reviews/{id}/events")
    public Map<String,Boolean> agentEvent(@PathVariable String id,@RequestHeader("X-Run-Token")String token,@RequestBody ObjectNode body){
        int no=body.path("versionNo").asInt();authorizeRun(id,no,token);
        String type=body.path("type").asText();
        if(!ReviewEvents.AGENT_TYPES.contains(type))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"INVALID_EVENT_TYPE");
        ObjectNode data=json.createObjectNode();
        if(type.equals("step_done")){
            int step=body.path("step").asInt(-1);if(step<0||step>4)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"INVALID_STEP");
            String tenant=store.tenant(id);
            store.mutate(tenant,id,d->ReviewDocument.from(d).version(no).completeStep(step));
            data.put("step",step);
        }else{
            String tool=body.path("tool").asText("");
            if(!tool.matches("[a-z_]{1,64}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"INVALID_TOOL");
            data.put("tool",tool);
            if(type.equals("tool_finished"))data.put("ok",body.path("ok").asBoolean(true));
        }
        events.publishAfterCommit(id,no,type,data);
        return Map.of("ok",true);
    }
    private void authorizeRun(String id,int no,String runToken){
        var rows=tokens.find(id,no);
        String digest=ReviewAssetService.hash(Objects.toString(runToken,"").getBytes(StandardCharsets.UTF_8));
        if(rows.size()!=1 || !MessageDigest.isEqual(rows.getFirst().get("token_hash").toString().getBytes(StandardCharsets.UTF_8),digest.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"RUN_SCOPE_MISMATCH");
    }
}
