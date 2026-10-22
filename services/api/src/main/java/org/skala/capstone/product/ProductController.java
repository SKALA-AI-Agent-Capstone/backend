package org.skala.capstone.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.List;
import org.skala.capstone.violation.ReviewViewAssembler;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** 제품 등록, 조회, 수정과 제품별 광고 검토 목록. 같은 기업의 사용자는 모두 제품을 등록하고 고칠 수 있다. */
@RestController
@RequestMapping("/api/v1/products")
public class ProductController {
    /** 이미지 URL은 바뀌지 않으므로 프론트가 image.version을 ?v=로 붙여 부른다. 그래서 하루 캐시를 허용한다. */
    private static final CacheControl VERSIONED_IMAGE =
            CacheControl.maxAge(Duration.ofDays(1)).cachePrivate();

    private final ProductService products;
    private final ReviewViewAssembler views;

    public ProductController(ProductService products, ReviewViewAssembler views) {
        this.products = products;
        this.views = views;
    }

    @GetMapping
    public List<ObjectNode> list(@AuthenticationPrincipal Jwt jwt) {
        return products.list(tenant(jwt));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ObjectNode> create(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam String metadata,
            @RequestPart(required = false) MultipartFile image) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(products.create(tenant(jwt), jwt.getSubject(), metadata, image));
    }

    @GetMapping("/{id}")
    public ObjectNode get(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return products.get(tenant(jwt), id);
    }

    @PatchMapping("/{id}")
    public ObjectNode update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String id,
            @RequestBody JsonNode input) {
        return products.update(tenant(jwt), id, input);
    }

    @PutMapping(value = "/{id}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ObjectNode replaceImage(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String id,
            @RequestPart MultipartFile image) {
        return products.replaceImage(tenant(jwt), id, image);
    }

    @GetMapping("/{id}/image")
    public ResponseEntity<byte[]> image(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return image(products.image(tenant(jwt), id, false));
    }

    /** 목록 카드용 썸네일(너비 160px JPEG). */
    @GetMapping("/{id}/image/thumbnail")
    public ResponseEntity<byte[]> thumbnail(
            @AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return image(products.image(tenant(jwt), id, true));
    }

    /** 이 제품의 광고 검토. 형식은 GET /api/v1/reviews와 같고 view=summary도 같다. */
    @GetMapping("/{id}/reviews")
    public List<ObjectNode> reviews(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String id,
            @RequestParam(required = false) String view) {
        String tenant = tenant(jwt);
        products.requireName(tenant, id);
        return "summary".equals(view) ? views.summaries(tenant, id) : views.list(tenant, id);
    }

    private static ResponseEntity<byte[]> image(ProductService.StoredImage image) {
        return ResponseEntity.ok()
                .cacheControl(VERSIONED_IMAGE)
                .contentType(MediaType.parseMediaType(image.mimeType()))
                .body(image.bytes());
    }

    private static String tenant(Jwt jwt) {
        return jwt.getClaimAsString("tenantId");
    }
}
