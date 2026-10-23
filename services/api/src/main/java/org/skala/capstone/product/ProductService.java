package org.skala.capstone.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.skala.capstone.product.ProductRepository.ProductImage;
import org.skala.capstone.review.ReviewAssetService;
import org.skala.capstone.review.ThumbnailRenderer;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * 제품 등록과 조회. 제품은 기업 안에서 이름이 겹치지 않는다. 대표 이미지는 등록할 때 썸네일까지 만들어 둔다. 제품 이름을 바꿔도 이미 끝난 검토의
 * productName(접수 당시 이름)은 그대로다.
 */
@Service
public class ProductService {
    public static final int NAME_MAX = 200;
    static final long IMAGE_MAX_BYTES = 10L * 1024 * 1024;
    static final Set<String> TYPES = Set.of("general", "functional", "children", "customized");
    static final Set<String> IMAGE_TYPES = Set.of("image/png", "image/jpeg", "image/webp");

    private final ProductRepository products;
    private final ThumbnailRenderer thumbnails;
    private final ObjectMapper json;

    public ProductService(
            ProductRepository products, ThumbnailRenderer thumbnails, ObjectMapper json) {
        this.products = products;
        this.thumbnails = thumbnails;
        this.json = json;
    }

    public List<ObjectNode> list(String tenant) {
        return products.list(tenant).stream().map(this::view).toList();
    }

    public ObjectNode get(String tenant, String id) {
        return view(products.find(tenant, uuid(id)).orElseThrow(ProductService::notFound));
    }

    /** 제품이 이 기업에 있는지 확인하고 이름을 돌려준다. 검토 접수와 제품별 검토 목록에서 쓴다. */
    public String requireName(String tenant, String id) {
        return products.name(tenant, uuid(id)).orElseThrow(ProductService::notFound);
    }

    public ObjectNode create(String tenant, String user, String metadata, MultipartFile image) {
        JsonNode meta = parse(metadata);
        String name = name(meta.path("name").asText(""));
        String type = meta.hasNonNull("type") ? type(meta.path("type").asText()) : "general";
        // 썸네일을 만들기 전에 이름부터 확인한다(겹치면 이미지 작업이 헛수고다). 동시에 등록되면 아래 UNIQUE가 막는다.
        if (products.nameTaken(tenant, name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PRODUCT_NAME_TAKEN");
        }
        ProductImage prepared = image == null || image.isEmpty() ? null : prepare(image);
        String id = UUID.randomUUID().toString();
        try {
            products.create(id, tenant, name, type, prepared, user);
        } catch (DuplicateKeyException taken) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PRODUCT_NAME_TAKEN");
        }
        return get(tenant, id);
    }

    /** 이름과 유형 중 보낸 것만 바꾼다. */
    public ObjectNode update(String tenant, String id, JsonNode input) {
        ObjectNode current = get(tenant, id);
        if (input == null || !input.isObject()) throw bad("INVALID_REQUEST");
        String name =
                input.hasNonNull("name")
                        ? name(input.path("name").asText())
                        : current.path("name").asText();
        String type =
                input.hasNonNull("type")
                        ? type(input.path("type").asText())
                        : current.path("type").asText();
        try {
            products.update(tenant, id, name, type);
        } catch (DuplicateKeyException taken) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PRODUCT_NAME_TAKEN");
        }
        return get(tenant, id);
    }

    /**
     * 검토가 0건인 제품만 지운다. 검토가 남아 있으면 409 PRODUCT_HAS_REVIEWS이며, 화면은 검토를 먼저 지우라고 안내한다.
     * 확인과 삭제 사이에 검토가 접수되면 외래 키가 삭제를 막고 같은 409가 된다.
     */
    public void delete(String tenant, String id) {
        String product = uuid(id);
        int deleted;
        try {
            deleted = products.deleteIfUnused(tenant, product);
        } catch (DataIntegrityViolationException inUse) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PRODUCT_HAS_REVIEWS");
        }
        if (deleted == 0) {
            products.find(tenant, product).orElseThrow(ProductService::notFound);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PRODUCT_HAS_REVIEWS");
        }
    }

    public ObjectNode replaceImage(String tenant, String id, MultipartFile image) {
        get(tenant, id);
        if (image == null || image.isEmpty()) throw bad("IMAGE_REQUIRED");
        products.replaceImage(tenant, id, prepare(image));
        return get(tenant, id);
    }

    /**
     * 원본 또는 썸네일과 그 형식. 이미지가 없으면 404 PRODUCT_IMAGE_NOT_FOUND, 썸네일을 만들 수 없던 형식이면 404
     * THUMBNAIL_UNAVAILABLE.
     */
    public StoredImage image(String tenant, String id, boolean thumbnail) {
        var rows = products.image(tenant, uuid(id), thumbnail);
        if (rows.isEmpty()) throw notFound();
        Map<String, Object> row = rows.getFirst();
        if (row.get("image_mime") == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PRODUCT_IMAGE_NOT_FOUND");
        }
        if (!(row.get("bytes") instanceof byte[] bytes)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "THUMBNAIL_UNAVAILABLE");
        }
        return new StoredImage(bytes, thumbnail ? "image/png" : row.get("image_mime").toString());
    }

    public static String name(String raw) {
        String name = raw == null ? "" : raw.strip();
        if (name.isEmpty()) throw bad("PRODUCT_NAME_REQUIRED");
        if (name.length() > NAME_MAX) throw bad("PRODUCT_NAME_TOO_LONG");
        return name;
    }

    private static String type(String raw) {
        if (!TYPES.contains(raw)) throw bad("INVALID_PRODUCT_TYPE");
        return raw;
    }

    private ProductImage prepare(MultipartFile image) {
        if (image.getSize() > IMAGE_MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "IMAGE_LIMIT_10_MIB");
        }
        byte[] bytes;
        try {
            bytes = image.getBytes();
        } catch (IOException unreadable) {
            throw bad("UPLOAD_READ_FAILED");
        }
        String mime;
        try {
            mime = ReviewAssetService.mime(bytes);
        } catch (ResponseStatusException unknown) {
            mime = "";
        }
        // PDF는 광고 파일로는 받지만 대표 이미지로는 받지 않는다
        if (!IMAGE_TYPES.contains(mime)) {
            throw new ResponseStatusException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE, "IMAGE_TYPE_UNSUPPORTED");
        }
        byte[] thumbnail = thumbnails.renderTransparent(bytes, mime).orElse(null);
        return new ProductImage(bytes, mime, ReviewAssetService.hash(bytes), thumbnail);
    }

    private JsonNode parse(String metadata) {
        try {
            JsonNode parsed = json.readTree(metadata == null ? "" : metadata);
            if (parsed != null && parsed.isObject()) return parsed;
        } catch (IOException invalid) {
            // 아래에서 같은 오류로 처리한다
        }
        throw bad("INVALID_METADATA");
    }

    private ObjectNode view(Map<String, Object> row) {
        ObjectNode node =
                json.createObjectNode()
                        .put("id", row.get("id").toString())
                        .put("name", row.get("name").toString())
                        .put("type", row.get("type").toString())
                        .put("createdAt", instant(row.get("created_at")))
                        .put("updatedAt", instant(row.get("updated_at")))
                        .put("reviewCount", ((Number) row.get("review_count")).intValue())
                        .put("evidenceReading", Boolean.TRUE.equals(row.get("evidence_reading")));
        String latest = instant(row.get("latest_review_at"));
        if (latest == null) node.putNull("latestReviewAt");
        else node.put("latestReviewAt", latest);
        if (row.get("image_mime") == null) {
            node.putNull("image");
        } else {
            // version은 이미지가 바뀌면 달라진다. 프론트는 이미지 URL 뒤에 ?v=로 붙여 예전 캐시를 피한다.
            node.putObject("image")
                    .put("version", row.get("image_sha256").toString().substring(0, 16))
                    .put("mime", row.get("image_mime").toString())
                    .put("thumbnail", Boolean.TRUE.equals(row.get("has_thumbnail")));
        }
        return node;
    }

    private static String instant(Object value) {
        if (value instanceof Timestamp timestamp) return timestamp.toInstant().toString();
        if (value instanceof OffsetDateTime time) return time.toInstant().toString();
        return value == null ? null : value.toString();
    }

    private static String uuid(String id) {
        try {
            return UUID.fromString(id).toString();
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw notFound();
        }
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND");
    }

    private static ResponseStatusException bad(String code) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, code);
    }

    public record StoredImage(byte[] bytes, String mimeType) {}
}
