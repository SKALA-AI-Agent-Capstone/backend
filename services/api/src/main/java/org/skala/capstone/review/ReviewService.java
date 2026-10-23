package org.skala.capstone.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.skala.capstone.auth.UserRepository;
import org.skala.capstone.billing.CreditLedgerService;
import org.skala.capstone.product.ProductRepository;
import org.skala.capstone.evidence.EvidenceService;
import org.skala.capstone.product.ProductService;
import org.skala.capstone.review.model.ReviewDocument;
import org.skala.capstone.review.model.ReviewStatus;
import org.skala.capstone.review.model.ReviewSubmission;
import org.skala.capstone.review.queue.JobRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReviewService {
    private final ReviewRepository store;
    private final ReviewAssetService assets;
    private final RunTokenRepository tokens;
    private final UserRepository users;
    private final JobRepository jobs;
    private final ReviewRunner runner;
    private final CreditLedgerService credits;
    private final ProductService productService;
    private final ProductRepository products;
    private final EvidenceService productEvidence;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final boolean queueEnabled;

    public ReviewService(ReviewRepository store, ReviewAssetService assets, RunTokenRepository tokens,
            UserRepository users, JobRepository jobs, ReviewRunner runner, CreditLedgerService credits,
            ProductService productService, ProductRepository products, EvidenceService productEvidence, ObjectMapper json,
            TransactionTemplate tx, @Value("${capstone.review.queue-enabled:false}") boolean queueEnabled) {
        this.store = store;
        this.assets = assets;
        this.tokens = tokens;
        this.users = users;
        this.jobs = jobs;
        this.runner = runner;
        this.credits = credits;
        this.productService = productService;
        this.products = products;
        this.productEvidence = productEvidence;
        this.json = json;
        this.tx = tx;
        this.queueEnabled = queueEnabled;
    }

    public ObjectNode submit(Jwt jwt, String metadata, List<MultipartFile> files, List<MultipartFile> evidence) {
        ReviewSubmission submission = prepare(metadata, files, evidence);
        return tx.execute(status -> persist(jwt, submission));
    }

    private ReviewSubmission prepare(String metadata, List<MultipartFile> files, List<MultipartFile> evidence) {
        if (files == null || files.isEmpty() || files.size() > 5 || evidence.size() > 10) {
            throw error(HttpStatus.BAD_REQUEST, "AD_FILES_REQUIRED_MAX_5");
        }
        ObjectNode meta;
        try {
            JsonNode parsed = json.readTree(metadata);
            if (!(parsed instanceof ObjectNode node)) throw new IllegalArgumentException();
            meta = node;
        } catch (Exception invalid) {
            throw error(HttpStatus.BAD_REQUEST, "INVALID_METADATA");
        }
        // 제품 화면에서 접수하면 productId를, 예전 화면은 제품 이름만 보낸다
        if (meta.path("productId").asText("").isBlank() && meta.path("product").path("name").asText("").isBlank()) {
            throw error(HttpStatus.BAD_REQUEST, "PRODUCT_NAME_REQUIRED");
        }
        List<MultipartFile> all = new ArrayList<>(files);
        all.addAll(evidence);
        if (all.stream().mapToLong(MultipartFile::getSize).sum() > 75L * 1024 * 1024) {
            throw error(HttpStatus.PAYLOAD_TOO_LARGE, "TOTAL_LIMIT_75_MIB");
        }
        List<ReviewSubmission.Asset> prepared = new ArrayList<>();
        for (int index = 0; index < all.size(); index++) {
            MultipartFile file = all.get(index);
            if (file.isEmpty() || file.getSize() > 25L * 1024 * 1024) {
                throw error(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_LIMIT_25_MIB");
            }
            byte[] bytes;
            try {
                bytes = file.getBytes();
            } catch (java.io.IOException unreadable) {
                throw error(HttpStatus.BAD_REQUEST, "UPLOAD_READ_FAILED");
            }
            String name = Objects.toString(file.getOriginalFilename(), "upload").replaceAll("[\\r\\n\\\\/]", "_");
            if (name.length() > 200) name = name.substring(name.length() - 200);
            boolean advertisement = index < files.size();
            prepared.add(new ReviewSubmission.Asset(UUID.randomUUID().toString(), name, assets.mime(bytes),
                advertisement ? "advertisement" : "evidence",
                advertisement ? "" : meta.path("evidence").path(index - files.size()).path("docType").asText("other"), bytes));
        }
        return new ReviewSubmission(meta, prepared);
    }

    private ObjectNode persist(Jwt jwt, ReviewSubmission submission) {
        String tenant = jwt.getClaimAsString("tenantId"), id = UUID.randomUUID().toString();
        int no = 1;
        ObjectNode meta = submission.metadata();
        String productId = product(tenant, jwt.getSubject(), meta);
        // 증빙을 읽는 중이면 검토를 받지 않는다. 크레딧을 잡기 전에 막아 아무것도 남기지 않는다.
        productEvidence.requireNotReading(tenant, productId);
        String now = Instant.now().toString();
        ObjectNode doc = json.createObjectNode().put("id", id).put("tenantId", tenant).put("authorId", jwt.getSubject())
            .put("createdAt", now).put("updatedAt", now).put("authorName", users.name(jwt.getSubject()))
            .put("productId", productId)
            .put("productName", meta.path("product").path("name").asText()).putNull("campaign");
        doc.putArray("versions");
        store.create(id, tenant, jwt.getSubject(), productId, doc);
        credits.reserve(tenant, jwt.getSubject(), id, no);
        try {
            ArrayNode advertisements = json.createArrayNode(), evidence = json.createArrayNode(), descriptors = json.createArrayNode();
            for (var file : submission.assets()) {
                assets.store(file.id(), id, no, file.name(), file.mime(), file.role(), file.docType(), file.bytes());
                ObjectNode descriptor = json.createObjectNode().put("id", file.id()).put("name", file.name())
                    .put("size", file.bytes().length).put("mime", file.mime()).put("role", file.role()).put("docType", file.docType())
                    .put("kind", file.mime().equals("application/pdf") ? "detail_page" : "image");
                descriptors.add(descriptor);
                if (file.role().equals("advertisement")) advertisements.add(descriptor.deepCopy());
                else evidence.add(descriptor.deepCopy());
            }
            ObjectNode input = json.createObjectNode();
            input.set("product", meta.path("product"));
            input.set("media", meta.path("media").isArray() ? meta.path("media") : json.createArrayNode());
            input.put("copy", "");
            input.set("files", advertisements);
            input.set("evidence", evidence);
            ObjectNode version = json.createObjectNode().put("no", no).put("authorId", jwt.getSubject())
                .put("authorName", doc.path("authorName").asText()).put("createdAt", Instant.now().toString())
                .put("status", ReviewStatus.QUEUED.value());
            version.set("input", input);
            version.set("steps", json.valueToTree(List.of(0, 0, 0, 0, 0)));
            version.putNull("result").putNull("error").putNull("originalsDiscardedAt");
            String runToken = UUID.randomUUID().toString() + UUID.randomUUID();
            tokens.create(id, no, ReviewAssetService.hash(runToken.getBytes(StandardCharsets.UTF_8)));
            store.mutate(tenant, id, document -> document.withArray("versions").add(version));
            ObjectNode request = json.createObjectNode().put("reviewId", id).put("tenantId", tenant)
                .put("versionNo", no).put("runToken", runToken)
                .put("productEvidenceCount", productEvidence.readyCount(tenant, productId));
            request.set("assets", descriptors);
            request.set("product", input.path("product"));
            if (queueEnabled) jobs.create(id, no, request.toString());
            else runner.afterCommit(request);
            return store.get(tenant, id);
        } catch (ResponseStatusException error) {
            throw error;
        } catch (Exception unavailable) {
            throw error(HttpStatus.BAD_GATEWAY, "UPLOAD_STORAGE_FAILED");
        }
    }

    /**
     * 검토가 속할 제품을 정하고 id를 돌려준다. productId가 오면 이 기업의 제품인지 확인하고, 없으면 제품 이름으로 찾거나 만든다.
     * 어느 쪽이든 metadata.product.name을 제품의 현재 이름으로 맞춘다(AI에 넘기고 productName으로 남는 값).
     */
    private String product(String tenant, String user, ObjectNode meta) {
        String requested = meta.path("productId").asText("");
        String name, id;
        if (!requested.isBlank()) {
            name = productService.requireName(tenant, requested);
            id = UUID.fromString(requested).toString();
        } else {
            name = ProductService.name(meta.path("product").path("name").asText());
            id = products.findOrCreate(tenant, name, user);
        }
        ObjectNode product = meta.path("product") instanceof ObjectNode node ? node : meta.putObject("product");
        product.put("name", name);
        return id;
    }

    public Map<String, Boolean> delete(Jwt jwt, String id) {
        String tenant = jwt.getClaimAsString("tenantId");
        ObjectNode doc = store.get(tenant, id);
        if (!doc.path("authorId").asText().equals(jwt.getSubject()) && !"admin".equals(jwt.getClaimAsString("role"))) {
            throw error(HttpStatus.FORBIDDEN, "DELETE_FORBIDDEN");
        }
        var latest = ReviewDocument.from(doc).latestVersion();
        if (latest.status().running()) throw error(HttpStatus.CONFLICT, "WAIT_FOR_ANALYSIS_BEFORE_DELETE");
        try {
            runner.callAgent("/internal/runs/delete", json.createObjectNode().put("reviewId", id).put("tenantId", tenant)
                .put("versionNo", latest.number()).put("state", "rejected"));
        } catch (Exception unavailable) {
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "CHECKPOINT_DELETE_PENDING_RETRY");
        }
        for (JsonNode version : doc.path("versions")) assets.purge(id, version.path("no").asInt());
        store.delete(tenant, id);
        return Map.of("ok", true);
    }

    public Map<String, Integer> deleteAll(Jwt jwt) {
        int count = 0;
        for (var doc : store.list(jwt.getClaimAsString("tenantId"))) {
            if ("admin".equals(jwt.getClaimAsString("role")) || doc.path("authorId").asText().equals(jwt.getSubject())) {
                delete(jwt, doc.path("id").asText());
                count++;
            }
        }
        return Map.of("deleted", count);
    }

    private static ResponseStatusException error(HttpStatus status, String code) {
        return new ResponseStatusException(status, code);
    }
}
