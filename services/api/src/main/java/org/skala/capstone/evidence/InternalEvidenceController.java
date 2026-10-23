package org.skala.capstone.evidence;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Agent가 읽을 증빙 파일. /internal/** 이므로 InternalTokenFilter의 내부 토큰이 있어야 열린다. */
@RestController
public class InternalEvidenceController {
    private final EvidenceService evidence;

    public InternalEvidenceController(EvidenceService evidence) {
        this.evidence = evidence;
    }

    @GetMapping("/internal/evidence/{id}/file")
    public ResponseEntity<byte[]> file(@PathVariable String id) {
        var stored = evidence.file(id);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(stored.mimeType()))
                .body(stored.bytes());
    }
}
