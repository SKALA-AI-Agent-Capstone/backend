package org.skala.capstone.evidence;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** 제품의 증빙 등록·조회·다시 읽기·삭제. 등록은 읽기를 시작하고 바로 돌아온다(202). 같은 파일이면 200으로 기존 결과를 준다. */
@RestController
@RequestMapping("/api/v1/products/{productId}/evidence")
public class EvidenceController {
    private final EvidenceService evidence;

    public EvidenceController(EvidenceService evidence) {
        this.evidence = evidence;
    }

    @GetMapping
    public List<ObjectNode> list(@AuthenticationPrincipal Jwt jwt, @PathVariable String productId) {
        return evidence.list(tenant(jwt), productId);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ObjectNode> upload(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String productId,
            @RequestPart MultipartFile file,
            @RequestParam(required = false) String docType) {
        var upload = evidence.upload(tenant(jwt), jwt.getSubject(), productId, file, docType);
        ObjectNode body = upload.evidence().put("reused", upload.reused());
        return ResponseEntity.status(upload.reused() ? HttpStatus.OK : HttpStatus.ACCEPTED).body(body);
    }

    @GetMapping("/{id}")
    public ObjectNode get(@AuthenticationPrincipal Jwt jwt, @PathVariable String productId, @PathVariable String id) {
        return evidence.get(tenant(jwt), productId, id);
    }

    @PostMapping("/{id}/reread")
    public ResponseEntity<ObjectNode> reread(
            @AuthenticationPrincipal Jwt jwt, @PathVariable String productId, @PathVariable String id) {
        return ResponseEntity.accepted().body(evidence.reread(tenant(jwt), productId, id));
    }

    @DeleteMapping("/{id}")
    public Map<String, Boolean> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String productId, @PathVariable String id) {
        evidence.delete(tenant(jwt), productId, id);
        return Map.of("ok", true);
    }

    private static String tenant(Jwt jwt) {
        return jwt.getClaimAsString("tenantId");
    }
}
