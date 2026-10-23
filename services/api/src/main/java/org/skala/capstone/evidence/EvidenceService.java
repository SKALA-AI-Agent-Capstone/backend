package org.skala.capstone.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.skala.capstone.product.ProductService;
import org.skala.capstone.review.ReviewAssetService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * 제품에 붙은 증빙. 증빙의 정체는 "종류 + 기관이 붙인 코드 + 버전 + 파일 해시"이고, 같은 해시는 다시 읽지 않는다.
 * 읽는 중인 증빙이 하나라도 있으면 그 제품의 검토는 접수되지 않는다(PRODUCT_EVIDENCE_READING).
 */
@Service
public class EvidenceService {
    static final long FILE_MAX_BYTES = 25L * 1024 * 1024;
    static final int DOC_TYPE_MAX = 40;

    private final EvidenceRepository evidence;
    private final EvidenceReader reader;
    private final ProductService products;
    private final ObjectMapper json;

    public EvidenceService(EvidenceRepository evidence, EvidenceReader reader, ProductService products, ObjectMapper json) {
        this.evidence = evidence;
        this.reader = reader;
        this.products = products;
        this.json = json;
    }

    public List<ObjectNode> list(String tenant, String product) {
        String id = productId(tenant, product);
        return evidence.list(tenant, id).stream().map(this::view).toList();
    }

    public ObjectNode get(String tenant, String product, String id) {
        return view(evidence.find(tenant, productId(tenant, product), uuid(id)).orElseThrow(EvidenceService::notFound));
    }

    /** 새 파일이면 읽기를 시작한다. 이미 읽은 같은 파일(같은 판독기)이면 다시 읽지 않고 그 결과를 돌려준다. */
    public Upload upload(String tenant, String user, String product, MultipartFile file, String docType) {
        String productId = productId(tenant, product);
        if (file == null || file.isEmpty()) throw bad("EVIDENCE_FILE_REQUIRED");
        if (file.getSize() > FILE_MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_LIMIT_25_MIB");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException unreadable) {
            throw bad("UPLOAD_READ_FAILED");
        }
        String mime = ReviewAssetService.mime(bytes);
        String sha = ReviewAssetService.hash(bytes);
        String type = docType == null || docType.isBlank() ? "other" : docType.strip();
        if (type.length() > DOC_TYPE_MAX) throw bad("DOC_TYPE_TOO_LONG");

        var existing = evidence.findByHash(tenant, productId, sha);
        if (existing.isPresent()) return known(tenant, productId, existing.get());
        String id = UUID.randomUUID().toString();
        try {
            evidence.insert(id, tenant, productId, type, fileName(file), mime, bytes, sha, user);
        } catch (DuplicateKeyException sameTime) {
            return known(tenant, productId, evidence.findByHash(tenant, productId, sha).orElseThrow());
        }
        reader.submit(id);
        return new Upload(get(tenant, productId, id), false);
    }

    /** 읽는 중이면 그대로, 끝난 결과가 지금 판독기로 읽은 것이면 재사용, 아니면(실패·옛 판독기) 다시 읽는다. */
    private Upload known(String tenant, String product, Map<String, Object> row) {
        String id = row.get("id").toString(), status = row.get("status").toString();
        String current = reader.currentReaderVersion();
        boolean stale = status.equals("failed")
                || (status.equals("done") && current != null && !current.equals(row.get("reader_version")));
        if (stale && evidence.restart(tenant, id) > 0) {
            reader.submit(id);
            return new Upload(get(tenant, product, id), false);
        }
        return new Upload(view(row), true);
    }

    /** 같은 파일을 지금 판독기로 다시 읽는다(읽는 중이면 409). */
    public ObjectNode reread(String tenant, String product, String id) {
        String productId = productId(tenant, product);
        evidence.find(tenant, productId, uuid(id)).orElseThrow(EvidenceService::notFound);
        if (evidence.restart(tenant, uuid(id)) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "EVIDENCE_ALREADY_READING");
        }
        reader.submit(uuid(id));
        return get(tenant, productId, id);
    }

    public void delete(String tenant, String product, String id) {
        if (evidence.delete(tenant, productId(tenant, product), uuid(id)) == 0) throw notFound();
    }

    /** 읽기가 끝난 현재 판 증빙의 수. Agent가 증빙 도구를 쓸지 정하는 데 쓴다. */
    public int readyCount(String tenant, String product) {
        return evidence.readyCount(tenant, product);
    }

    /** 검토 접수가 부른다. 읽는 중인 증빙이 있는 제품이면 409. */
    public void requireNotReading(String tenant, String product) {
        if (evidence.reading(tenant, product)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PRODUCT_EVIDENCE_READING");
        }
    }

    /**
     * 검토(Agent)가 쓰는 제품 증빙. 보고서가 여러 제품을 다루면 등록한 제품의 쪽만 글자를 싣고(productMatch.pages),
     * 이 제품이 보고서에 나오지 않으면 글자 없이 productNotInReport로 알린다. 제품을 구분할 수 없는 문서(제품 목록 없음)는 전체 쪽을 싣는다.
     */
    public List<ObjectNode> forRun(String reviewId) {
        List<ObjectNode> out = new java.util.ArrayList<>();
        for (Map<String, Object> row : evidence.readyForReview(uuid(reviewId))) {
            JsonNode listed = tree(row.get("report_products")), match = tree(row.get("product_match"));
            boolean narrowed = listed.isArray() && !listed.isEmpty();
            java.util.Set<Integer> wanted = new java.util.HashSet<>();
            match.path("pages").forEach(page -> wanted.add(page.asInt()));
            StringBuilder text = new StringBuilder();
            for (JsonNode page : tree(row.get("pages"))) {
                if (narrowed && !wanted.contains(page.path("n").asInt())) continue;
                if (!page.path("text").asText("").isBlank()) text.append(page.path("text").asText()).append('\n');
            }
            ObjectNode node = json.createObjectNode()
                    .put("id", row.get("id").toString())
                    .put("docType", row.get("doc_type").toString())
                    .put("name", row.get("file_name").toString())
                    .put("pages", ((Number) row.get("page_count")).intValue())
                    .put("readerVersion", String.valueOf(row.get("reader_version")))
                    .put("productNotInReport", narrowed && match.isNull())
                    .put("text", text.toString());
            text(node, "version", row.get("version_label"));
            node.set("issuer", row.get("issuer_key") == null ? json.nullNode()
                    : json.createObjectNode().put("key", row.get("issuer_key").toString()).put("name", String.valueOf(row.get("issuer_name"))));
            node.set("codes", tree(row.get("codes")));
            node.set("unreadPages", tree(row.get("unread_pages")));
            node.set("productPages", match.path("pages"));
            node.set("results", match.path("results"));
            out.add(node);
        }
        return out;
    }

    /** Agent가 읽을 파일. 내부 토큰으로만 열린다. */
    public StoredFile file(String id) {
        var row = evidence.file(uuid(id)).orElseThrow(EvidenceService::notFound);
        return new StoredFile((byte[]) row.get("file_bytes"), row.get("file_mime").toString());
    }

    private String productId(String tenant, String product) {
        products.requireName(tenant, product);
        return product;
    }

    private ObjectNode view(Map<String, Object> row) {
        ObjectNode node = json.createObjectNode()
                .put("id", row.get("id").toString())
                .put("productId", row.get("product_id").toString())
                .put("docType", row.get("doc_type").toString())
                .put("fileName", row.get("file_name").toString())
                .put("fileMime", row.get("file_mime").toString())
                .put("fileSize", ((Number) row.get("file_size")).intValue())
                .put("fileSha256", row.get("file_sha256").toString())
                .put("status", row.get("status").toString())
                .put("isCurrent", Boolean.TRUE.equals(row.get("is_current")))
                .put("createdAt", instant(row.get("created_at")))
                .put("readStartedAt", instant(row.get("read_started_at")))
                .put("readFinishedAt", instant(row.get("read_finished_at")));
        text(node, "failureCode", row.get("failure_code"));
        text(node, "readerVersion", row.get("reader_version"));
        text(node, "version", row.get("version_label"));
        text(node, "issuedOn", row.get("issued_on"));
        text(node, "supersedes", row.get("supersedes"));
        text(node, "changeNote", row.get("change_note"));
        if (row.get("page_count") == null) node.putNull("pageCount");
        else node.put("pageCount", ((Number) row.get("page_count")).intValue());
        if (row.get("issuer_key") == null) node.putNull("issuer");
        else node.putObject("issuer").put("key", row.get("issuer_key").toString())
                .put("name", String.valueOf(row.get("issuer_name")));
        node.set("codes", tree(row.get("codes")));
        node.set("unreadPages", tree(row.get("unread_pages")));
        node.set("products", tree(row.get("report_products")));
        node.set("productMatch", tree(row.get("product_match")));
        node.set("warnings", tree(row.get("warnings")));
        return node;
    }

    private JsonNode tree(Object jsonText) {
        if (jsonText == null) return json.nullNode();
        try {
            return json.readTree(jsonText.toString());
        } catch (IOException invalid) {
            return json.nullNode();
        }
    }

    private static void text(ObjectNode node, String key, Object value) {
        if (value == null) node.putNull(key);
        else node.put(key, value.toString());
    }

    private static String instant(Object value) {
        if (value instanceof Timestamp timestamp) return timestamp.toInstant().toString();
        if (value instanceof OffsetDateTime time) return time.toInstant().toString();
        return value == null ? null : value.toString();
    }

    private static String fileName(MultipartFile file) {
        String name = java.util.Objects.toString(file.getOriginalFilename(), "upload").replaceAll("[\\r\\n\\\\/]", "_");
        return name.length() > 200 ? name.substring(name.length() - 200) : name;
    }

    private static String uuid(String id) {
        try {
            return UUID.fromString(id).toString();
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw notFound();
        }
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "EVIDENCE_NOT_FOUND");
    }

    private static ResponseStatusException bad(String code) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, code);
    }

    public record Upload(ObjectNode evidence, boolean reused) {}

    public record StoredFile(byte[] bytes, String mimeType) {}
}
