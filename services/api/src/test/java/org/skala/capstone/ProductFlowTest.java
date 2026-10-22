package org.skala.capstone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.skala.capstone.common.AgentClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 제품 등록, 대표 이미지, 제품별 검토 목록, 검토 접수의 제품 연결과 V19 기존 검토 이관을 실제 PostgreSQL로 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {
    "capstone.internal-token=unit-test-only-token-not-for-production-12345",
    "capstone.agent-base-url=http://127.0.0.1:1",
    "capstone.jwt-secret=unit-test-only-jwt-secret-not-for-production-12345"
})
class ProductFlowTest {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired ObjectMapper json;
    @MockitoBean AgentClient agent;
    @MockitoBean StringRedisTemplate redis;

    static final String TENANT = "00000000-0000-0000-0000-000000000201";
    static final String OTHER_TENANT = "00000000-0000-0000-0000-000000000202";
    static final String AUTHOR = "00000000-0000-0000-0000-000000000211";
    static final String OUTSIDER = "00000000-0000-0000-0000-000000000212";

    @BeforeEach
    @SuppressWarnings("unchecked")
    void seed() throws Exception {
        jdbc.update("INSERT INTO app.tenants VALUES (CAST(? AS uuid),'product') ON CONFLICT DO NOTHING", TENANT);
        jdbc.update("INSERT INTO app.tenants VALUES (CAST(? AS uuid),'other') ON CONFLICT DO NOTHING", OTHER_TENANT);
        jdbc.update("INSERT INTO app.users (id,tenant_id,email,name,password_hash,role,credit_balance) "
                + "VALUES (CAST(? AS uuid),CAST(? AS uuid),'product-author@test','작성자','x','author',1000) "
                + "ON CONFLICT (id) DO UPDATE SET credit_balance=1000", AUTHOR, TENANT);
        jdbc.update("INSERT INTO app.users (id,tenant_id,email,name,password_hash,role,credit_balance) "
                + "VALUES (CAST(? AS uuid),CAST(? AS uuid),'product-outsider@test','외부','x','author',1000) "
                + "ON CONFLICT (id) DO UPDATE SET credit_balance=1000", OUTSIDER, OTHER_TENANT);
        when(redis.opsForValue()).thenReturn(mock(ValueOperations.class));
        ObjectNode response = json.createObjectNode();
        ObjectNode result = response.putObject("result");
        result.putArray("findings");
        result.putArray("visualFindings");
        result.putObject("pipelineStatus").put("text", "complete").put("visual", "complete");
        when(agent.post(anyString(), any())).thenReturn(response);
    }

    RequestPostProcessor author() {
        return jwt().jwt(j -> j.subject(AUTHOR).claim("tenantId", TENANT).claim("role", "author"));
    }

    RequestPostProcessor outsider() {
        return jwt().jwt(j -> j.subject(OUTSIDER).claim("tenantId", OTHER_TENANT).claim("role", "author"));
    }

    JsonNode read(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    static String unique(String name) {
        return name + " " + UUID.randomUUID().toString().substring(0, 8);
    }

    static byte[] image(String format, int width, int height) throws Exception {
        BufferedImage picture = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var out = new ByteArrayOutputStream();
        ImageIO.write(picture, format, out);
        return out.toByteArray();
    }

    ResultActions createProduct(String metadata, byte[] image, RequestPostProcessor user) throws Exception {
        var request = multipart("/api/v1/products");
        if (image != null) request.file(new MockMultipartFile("image", "product.bin", "application/octet-stream", image));
        return mvc.perform(request.param("metadata", metadata).with(user));
    }

    String product(String name) throws Exception {
        return read(createProduct("{\"name\":\"" + name + "\"}", null, author()).andExpect(status().isCreated()))
                .path("id").asText();
    }

    /** 접수하고 백그라운드 실행이 끝날 때까지 기다린 뒤 검토 id를 돌려준다. */
    String submit(String metadata) throws Exception {
        byte[] png = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4};
        JsonNode created = read(mvc.perform(multipart("/api/v1/reviews")
                        .file(new MockMultipartFile("files", "ad.png", "image/png", png))
                        .param("metadata", metadata)
                        .with(author()))
                .andExpect(status().is2xxSuccessful()));
        String id = created.path("id").asText();
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (Instant.now().isBefore(deadline)) {
            String state = jdbc.queryForObject(
                    "SELECT document->'versions'->0->>'status' FROM app.reviews WHERE id=CAST(? AS uuid)", String.class, id);
            if ("done".equals(state) || "failed".equals(state)) break;
            Thread.sleep(100);
        }
        return id;
    }

    @Test
    void productIsCreatedWithImageListedAndUpdated() throws Exception {
        String name = unique("미백 크림");
        byte[] png = image("png", 600, 900);
        JsonNode created = read(createProduct("{\"name\":\"  " + name + "  \",\"type\":\"functional\"}", png, author())
                .andExpect(status().isCreated()));
        String id = created.path("id").asText();
        assertThat(created.path("name").asText()).isEqualTo(name);
        assertThat(created.path("type").asText()).isEqualTo("functional");
        assertThat(created.path("reviewCount").asInt()).isZero();
        assertThat(created.path("latestReviewAt").isNull()).isTrue();
        assertThat(created.path("image").path("mime").asText()).isEqualTo("image/png");
        assertThat(created.path("image").path("thumbnail").asBoolean()).isTrue();
        String version = created.path("image").path("version").asText();
        assertThat(version).hasSize(16);

        JsonNode list = read(mvc.perform(get("/api/v1/products").with(author())).andExpect(status().isOk()));
        assertThat(list.findValuesAsText("id")).contains(id);

        var original = mvc.perform(get("/api/v1/products/" + id + "/image?v=" + version).with(author()))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("max-age=86400"),
                        org.hamcrest.Matchers.containsString("private"))))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(original).isEqualTo(png);
        byte[] thumbnail = mvc.perform(get("/api/v1/products/" + id + "/image/thumbnail").with(author()))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(ImageIO.read(new ByteArrayInputStream(thumbnail)).getWidth()).isEqualTo(160);

        // 보낸 필드만 바뀐다
        JsonNode renamed = read(mvc.perform(patch("/api/v1/products/" + id)
                        .contentType("application/json").content("{\"name\":\"" + name + " 2\"}").with(author()))
                .andExpect(status().isOk()));
        assertThat(renamed.path("name").asText()).isEqualTo(name + " 2");
        assertThat(renamed.path("type").asText()).isEqualTo("functional");

        // 이미지를 바꾸면 version이 달라져 프론트가 새 URL로 받는다
        JsonNode replaced = read(mvc.perform(multipart(HttpMethod.PUT, "/api/v1/products/" + id + "/image")
                        .file(new MockMultipartFile("image", "new.jpg", "image/jpeg", image("jpg", 300, 300)))
                        .with(author()))
                .andExpect(status().isOk()));
        assertThat(replaced.path("image").path("mime").asText()).isEqualTo("image/jpeg");
        assertThat(replaced.path("image").path("version").asText()).isNotEqualTo(version);
    }

    @Test
    void childrenAndCustomizedTypesAreCreatedReadAndUpdated() throws Exception {
        for (String type : new String[] {"children", "customized"}) {
            String metadata = json.createObjectNode()
                    .put("name", unique("추가 유형 " + type)).put("type", type).toString();
            JsonNode created = read(createProduct(metadata, null, author()).andExpect(status().isCreated()));
            String id = created.path("id").asText();
            assertThat(created.path("type").asText()).isEqualTo(type);
            mvc.perform(get("/api/v1/products/" + id).with(author()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.type").value(type));
            mvc.perform(patch("/api/v1/products/" + id).with(author())
                            .contentType("application/json").content("{\"type\":\"general\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.type").value("general"));
            mvc.perform(patch("/api/v1/products/" + id).with(author())
                            .contentType("application/json").content("{\"type\":\"" + type + "\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.type").value(type));
        }
    }

    @Test
    void invalidProductsAreRejected() throws Exception {
        String name = unique("중복 제품");
        product(name);
        assertThat(read(createProduct("{\"name\":\"" + name + "\"}", null, author()).andExpect(status().isConflict()))
                .path("message").asText()).isEqualTo("PRODUCT_NAME_TAKEN");
        assertThat(read(createProduct("{\"name\":\"   \"}", null, author()).andExpect(status().isBadRequest()))
                .path("message").asText()).isEqualTo("PRODUCT_NAME_REQUIRED");
        assertThat(read(createProduct("{\"name\":\"" + "가".repeat(201) + "\"}", null, author())
                .andExpect(status().isBadRequest())).path("message").asText()).isEqualTo("PRODUCT_NAME_TOO_LONG");
        assertThat(read(createProduct("{\"name\":\"" + unique("유형") + "\",\"type\":\"cosmetic\"}", null, author())
                .andExpect(status().isBadRequest())).path("message").asText()).isEqualTo("INVALID_PRODUCT_TYPE");
        assertThat(read(createProduct("not json", null, author()).andExpect(status().isBadRequest()))
                .path("message").asText()).isEqualTo("INVALID_METADATA");
        byte[] pdf = "%PDF-1.4 sample".getBytes();
        assertThat(read(createProduct("{\"name\":\"" + unique("PDF") + "\"}", pdf, author())
                .andExpect(status().isUnsupportedMediaType())).path("message").asText()).isEqualTo("IMAGE_TYPE_UNSUPPORTED");

        // 다른 제품의 이름으로 바꿀 수 없다
        String other = product(unique("다른 제품"));
        assertThat(read(mvc.perform(patch("/api/v1/products/" + other)
                        .contentType("application/json").content("{\"name\":\"" + name + "\"}").with(author()))
                .andExpect(status().isConflict())).path("message").asText()).isEqualTo("PRODUCT_NAME_TAKEN");
    }

    @Test
    void missingImagesAndUnrenderableThumbnailsAreNotFound() throws Exception {
        String bare = product(unique("이미지 없음"));
        assertThat(read(mvc.perform(get("/api/v1/products/" + bare + "/image").with(author()))
                .andExpect(status().isNotFound())).path("message").asText()).isEqualTo("PRODUCT_IMAGE_NOT_FOUND");

        // 진짜 WebP는 썸네일을 만든다(배경을 지운 이미지는 브라우저가 WebP로 바꿔 올린다)
        JsonNode real = read(createProduct("{\"name\":\"" + unique("진짜 웹피") + "\"}", ThumbnailRendererTest.TINY_WEBP, author())
                .andExpect(status().isCreated()));
        assertThat(real.path("image").path("thumbnail").asBoolean()).isTrue();
        mvc.perform(get("/api/v1/products/" + real.path("id").asText() + "/image/thumbnail").with(author()))
                .andExpect(status().isOk()).andExpect(content().contentType("image/png"));

        // 머리만 WebP인 손상 파일은 대표 이미지로 받지만 썸네일은 만들 수 없다
        byte[] webp = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P', 'V', 'P', '8', ' '};
        JsonNode created = read(createProduct("{\"name\":\"" + unique("웹피") + "\"}", webp, author())
                .andExpect(status().isCreated()));
        assertThat(created.path("image").path("thumbnail").asBoolean()).isFalse();
        String id = created.path("id").asText();
        mvc.perform(get("/api/v1/products/" + id + "/image").with(author()))
                .andExpect(status().isOk()).andExpect(content().contentType("image/webp"));
        assertThat(read(mvc.perform(get("/api/v1/products/" + id + "/image/thumbnail").with(author()))
                .andExpect(status().isNotFound())).path("message").asText()).isEqualTo("THUMBNAIL_UNAVAILABLE");
    }

    @Test
    void productsAreScopedToTheirTenant() throws Exception {
        String id = read(createProduct("{\"name\":\"" + unique("범위") + "\"}", image("png", 50, 50), author())
                .andExpect(status().isCreated())).path("id").asText();
        assertThat(read(mvc.perform(get("/api/v1/products").with(outsider()))).findValuesAsText("id"))
                .doesNotContain(id);
        for (String path : new String[] {"", "/image", "/image/thumbnail", "/reviews"}) {
            assertThat(read(mvc.perform(get("/api/v1/products/" + id + path).with(outsider()))
                    .andExpect(status().isNotFound())).path("message").asText()).isEqualTo("PRODUCT_NOT_FOUND");
        }
        mvc.perform(patch("/api/v1/products/" + id).contentType("application/json").content("{\"name\":\"x\"}")
                .with(outsider())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/products/not-a-uuid").with(author())).andExpect(status().isNotFound());

        // 다른 기업의 제품으로는 검토를 접수할 수 없다
        byte[] png = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4};
        assertThat(read(mvc.perform(multipart("/api/v1/reviews")
                        .file(new MockMultipartFile("files", "ad.png", "image/png", png))
                        .param("metadata", "{\"productId\":\"" + id + "\"}")
                        .with(outsider()))
                .andExpect(status().isNotFound())).path("message").asText()).isEqualTo("PRODUCT_NOT_FOUND");
    }

    @Test
    void reviewsSubmittedWithAProductIdBelongToThatProduct() throws Exception {
        String name = unique("수분 앰플");
        String productId = product(name);
        // productId가 오면 제품의 현재 이름을 쓴다. 다른 이름을 같이 보내도 무시한다.
        String reviewId = submit("{\"productId\":\"" + productId + "\",\"product\":{\"name\":\"다른 이름\"},\"media\":[]}");

        JsonNode doc = read(mvc.perform(get("/api/v1/reviews/" + reviewId).with(author())).andExpect(status().isOk()));
        assertThat(doc.path("productId").asText()).isEqualTo(productId);
        assertThat(doc.path("productName").asText()).isEqualTo(name);
        assertThat(doc.path("versions").get(0).path("input").path("product").path("name").asText()).isEqualTo(name);
        assertThat(jdbc.queryForObject("SELECT product_id::text FROM app.reviews WHERE id=CAST(? AS uuid)",
                String.class, reviewId)).isEqualTo(productId);

        JsonNode reviews = read(mvc.perform(get("/api/v1/products/" + productId + "/reviews").with(author()))
                .andExpect(status().isOk()));
        // findValuesAsText는 파일 id까지 모으므로 맨 위 항목의 id만 본다
        assertThat(reviews).hasSize(1);
        assertThat(reviews.get(0).path("id").asText()).isEqualTo(reviewId);
        JsonNode summaries = read(mvc.perform(get("/api/v1/products/" + productId + "/reviews?view=summary")
                .with(author())).andExpect(status().isOk()));
        assertThat(summaries.get(0).path("summary").asBoolean()).isTrue();
        assertThat(summaries.get(0).path("versions").get(0).path("result").has("findingCount")).isTrue();

        JsonNode product = read(mvc.perform(get("/api/v1/products/" + productId).with(author())));
        assertThat(product.path("reviewCount").asInt()).isEqualTo(1);
        assertThat(product.path("latestReviewAt").isTextual()).isTrue();
        // 다른 제품의 목록에는 나오지 않는다
        String other = product(unique("빈 제품"));
        assertThat(read(mvc.perform(get("/api/v1/products/" + other + "/reviews").with(author())))).isEmpty();
    }

    @Test
    void productsCanBeDeletedOnlyAfterTheirReviewsAreGone() throws Exception {
        String empty = product(unique("빈 제품 삭제"));
        mvc.perform(delete("/api/v1/products/" + empty).with(author())).andExpect(status().isOk());
        mvc.perform(get("/api/v1/products/" + empty).with(author())).andExpect(status().isNotFound());

        String used = product(unique("검토 있는 제품"));
        String reviewId = submit("{\"productId\":\"" + used + "\"}");
        assertThat(read(mvc.perform(delete("/api/v1/products/" + used).with(author()))
                .andExpect(status().isConflict())).path("message").asText()).isEqualTo("PRODUCT_HAS_REVIEWS");
        // 다른 기업은 있는지조차 알 수 없다
        assertThat(read(mvc.perform(delete("/api/v1/products/" + used).with(outsider()))
                .andExpect(status().isNotFound())).path("message").asText()).isEqualTo("PRODUCT_NOT_FOUND");

        // 검토를 모두 지우면 삭제할 수 있다
        mvc.perform(delete("/api/v1/reviews/" + reviewId).with(author())).andExpect(status().isOk());
        mvc.perform(delete("/api/v1/products/" + used).with(author())).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.products WHERE id=CAST(? AS uuid)",
                Integer.class, used)).isZero();
        mvc.perform(delete("/api/v1/products/" + used).with(author())).andExpect(status().isNotFound());
    }

    @Test
    void legacySubmissionsFindOrCreateTheProductByName() throws Exception {
        String name = unique("예전 접수");
        String first = submit("{\"product\":{\"name\":\"" + name + "\"},\"media\":[]}");
        String second = submit("{\"product\":{\"name\":\"  " + name + " \"},\"media\":[]}");
        String productId = read(mvc.perform(get("/api/v1/reviews/" + first).with(author()))).path("productId").asText();
        assertThat(read(mvc.perform(get("/api/v1/reviews/" + second).with(author()))).path("productId").asText())
                .isEqualTo(productId);
        JsonNode product = read(mvc.perform(get("/api/v1/products/" + productId).with(author())));
        assertThat(product.path("name").asText()).isEqualTo(name);
        assertThat(product.path("type").asText()).isEqualTo("general");
        assertThat(product.path("reviewCount").asInt()).isEqualTo(2);

        byte[] png = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4};
        assertThat(read(mvc.perform(multipart("/api/v1/reviews")
                        .file(new MockMultipartFile("files", "ad.png", "image/png", png))
                        .param("metadata", "{\"product\":{\"name\":\" \"}}")
                        .with(author()))
                .andExpect(status().isBadRequest())).path("message").asText()).isEqualTo("PRODUCT_NAME_REQUIRED");
    }

    @Test
    void v19LinksExistingReviewsToProductsByName() {
        String name = unique("이관 제품");
        Timestamp before = Timestamp.from(Instant.parse("2026-10-01T00:00:00Z"));
        jdbc.execute("ALTER TABLE app.reviews ALTER COLUMN product_id DROP NOT NULL");
        try {
            String a = legacyReview(TENANT, name, before);
            String b = legacyReview(TENANT, "  " + name + " ", before);
            String blank = legacyReview(TENANT, "", before);
            String elsewhere = legacyReview(OTHER_TENANT, name, before);

            new ResourceDatabasePopulator(new ClassPathResource("db/migration/V19__link_reviews_to_products.sql"))
                    .execute(dataSource);

            String productA = productOf(a);
            assertThat(productOf(b)).isEqualTo(productA);
            assertThat(productOf(elsewhere)).isNotEqualTo(productA);
            assertThat(jdbc.queryForObject("SELECT name FROM app.products WHERE id=CAST(? AS uuid)", String.class,
                    productA)).isEqualTo(name);
            assertThat(jdbc.queryForObject("SELECT name FROM app.products WHERE id=CAST(? AS uuid)", String.class,
                    productOf(blank))).isEqualTo("이름 없는 제품");
            assertThat(jdbc.queryForObject("SELECT document->>'productId' FROM app.reviews WHERE id=CAST(? AS uuid)",
                    String.class, a)).isEqualTo(productA);
            // 목록 순서를 지키도록 updated_at은 그대로다
            assertThat(jdbc.queryForObject("SELECT updated_at FROM app.reviews WHERE id=CAST(? AS uuid)",
                    Timestamp.class, a)).isEqualTo(before);
        } finally {
            jdbc.update("UPDATE app.reviews r SET product_id = (SELECT p.id FROM app.products p WHERE p.tenant_id = r.tenant_id LIMIT 1) WHERE product_id IS NULL");
            jdbc.execute("ALTER TABLE app.reviews ALTER COLUMN product_id SET NOT NULL");
        }
        assertThat(jdbc.queryForObject("SELECT is_nullable FROM information_schema.columns "
                + "WHERE table_schema='app' AND table_name='reviews' AND column_name='product_id'", String.class))
                .isEqualTo("NO");
    }

    String legacyReview(String tenant, String productName, Timestamp updatedAt) {
        String id = UUID.randomUUID().toString();
        String author = tenant.equals(TENANT) ? AUTHOR : OUTSIDER;
        ObjectNode doc = json.createObjectNode().put("id", id).put("tenantId", tenant).put("productName", productName);
        doc.putArray("versions");
        jdbc.update("INSERT INTO app.reviews (id, tenant_id, author_id, document, updated_at) "
                + "VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS jsonb), ?)",
                id, tenant, author, doc.toString(), updatedAt);
        return id;
    }

    String productOf(String reviewId) {
        return jdbc.queryForObject("SELECT product_id::text FROM app.reviews WHERE id=CAST(? AS uuid)",
                String.class, reviewId);
    }
}
