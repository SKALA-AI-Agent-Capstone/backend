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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.skala.capstone.common.AgentClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 제품 증빙: 읽기 시작·결과 저장·같은 파일 재사용·개정본 판단·읽는 중 검토 차단·기업 격리를 실제 PostgreSQL로 확인한다. Agent는 가짜다. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {
    "capstone.internal-token=unit-test-only-token-not-for-production-12345",
    "capstone.agent-base-url=http://127.0.0.1:1",
    "capstone.jwt-secret=unit-test-only-jwt-secret-not-for-production-12345",
    "capstone.evidence.reader-cache-seconds=0"
})
class EvidenceFlowTest {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired org.skala.capstone.evidence.EvidenceJobRepository jobs;
    @MockitoBean AgentClient agent;
    @MockitoBean StringRedisTemplate redis;

    static final String TENANT = "00000000-0000-0000-0000-000000000301";
    static final String OTHER_TENANT = "00000000-0000-0000-0000-000000000302";
    static final String AUTHOR = "00000000-0000-0000-0000-000000000311";
    static final String OUTSIDER = "00000000-0000-0000-0000-000000000312";
    static final String TOKEN = "Bearer unit-test-only-token-not-for-production-12345";

    String readerVersion = "r1";

    @BeforeEach
    @SuppressWarnings("unchecked")
    void seed() throws Exception {
        jdbc.update("INSERT INTO app.tenants VALUES (CAST(? AS uuid),'evidence') ON CONFLICT DO NOTHING", TENANT);
        jdbc.update("INSERT INTO app.tenants VALUES (CAST(? AS uuid),'other-evidence') ON CONFLICT DO NOTHING", OTHER_TENANT);
        jdbc.update("INSERT INTO app.users (id,tenant_id,email,name,password_hash,role,credit_balance) "
                + "VALUES (CAST(? AS uuid),CAST(? AS uuid),'evidence-author@test','작성자','x','author',1000) "
                + "ON CONFLICT (id) DO UPDATE SET credit_balance=1000", AUTHOR, TENANT);
        jdbc.update("INSERT INTO app.users (id,tenant_id,email,name,password_hash,role,credit_balance) "
                + "VALUES (CAST(? AS uuid),CAST(? AS uuid),'evidence-outsider@test','외부','x','author',1000) "
                + "ON CONFLICT (id) DO UPDATE SET credit_balance=1000", OUTSIDER, OTHER_TENANT);
        doReturn(mock(ValueOperations.class)).when(redis).opsForValue();
        doAnswer(call -> json.createObjectNode().put("readerVersion", readerVersion)).when(agent).get("/internal/evidence/reader");
        ObjectNode review = json.createObjectNode();
        ObjectNode result = review.putObject("result");
        result.putArray("findings");
        result.putArray("visualFindings");
        result.putObject("pipelineStatus").put("text", "complete").put("visual", "complete");
        doReturn(review).when(agent).post(eq("/internal/runs"), any());
    }

    /** 앞 테스트가 남긴 검토 실행과 증빙 읽기가 끝날 때까지 기다린다. 다음 테스트가 목을 바꾸는 사이에 그 스레드가 끼어들지 않게 한다. */
    @org.junit.jupiter.api.AfterEach
    void drain() throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (Instant.now().isBefore(deadline)) {
            Integer busy = jdbc.queryForObject(
                    "SELECT (SELECT count(*) FROM app.reviews WHERE tenant_id = CAST(? AS uuid)"
                            + " AND document->'versions'->-1->>'status' IN ('queued', 'analyzing'))"
                            + " + (SELECT count(*) FROM app.product_evidence WHERE tenant_id = CAST(? AS uuid) AND status = 'reading')",
                    Integer.class, TENANT, TENANT);
            if (busy == 0) return;
            Thread.sleep(50);
        }
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

    String product() throws Exception {
        String name = "증빙 제품 " + UUID.randomUUID().toString().substring(0, 8);
        return read(mvc.perform(multipart("/api/v1/products").param("metadata", "{\"name\":\"" + name + "\"}").with(author()))
                .andExpect(status().isCreated())).path("id").asText();
    }

    /** %PDF- 로 시작하는 가짜 파일. 내용을 읽는 것은 Agent이고 여기서는 가짜다. */
    static MockMultipartFile pdf(String body) {
        return new MockMultipartFile("file", "report.pdf", "application/pdf",
                ("%PDF-1.4 " + body).getBytes(StandardCharsets.UTF_8));
    }

    ResultActions upload(String product, MockMultipartFile file) throws Exception {
        return mvc.perform(multipart("/api/v1/products/" + product + "/evidence").file(file).param("docType", "clinical").with(author()));
    }

    ObjectNode parsed(String issuer, String code, String version) {
        ObjectNode out = json.createObjectNode().put("readerVersion", readerVersion).put("pageCount", 3)
                .put("version", version).put("issuedOn", "2024-04");
        out.putObject("issuer").put("key", issuer).put("name", "KSRC 한국피부임상연구센터");
        out.putArray("codes").addObject().put("label", "연구 코드").put("value", code).put("pages", 3).put("status", "confirmed");
        out.putArray("pages").addObject().put("n", 1).put("kind", "text").put("method", "text_layer").put("chars", 400).put("text", "본문");
        out.putArray("unreadPages");
        var product = out.putArray("products").addObject().put("name", "알파 크림");
        product.putArray("ranges").addArray().add(1).add(2);
        out.putObject("productMatch").put("name", "알파 크림").put("score", 1.0).putArray("pages").add(1).add(2);
        out.putArray("warnings");
        return out;
    }

    void answer(ObjectNode result) throws Exception {
        doReturn(result).when(agent).post(eq("/internal/evidence/parse"), any(), any(Duration.class));
    }

    String waitFor(String product, String id, String... wanted) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        String status = "";
        while (Instant.now().isBefore(deadline)) {
            status = read(mvc.perform(get("/api/v1/products/" + product + "/evidence/" + id).with(author()))).path("status").asText();
            if (java.util.List.of(wanted).contains(status)) break;
            Thread.sleep(50);
        }
        return status;
    }

    @Test
    void uploadStartsReadingThenStoresIssuerCodeVersionAndProductPages() throws Exception {
        answer(parsed("ksrc", "HBSE-MGE-24032", "Version 1.0 · 2024-04"));
        String product = product();
        JsonNode started = read(upload(product, pdf("a")).andExpect(status().isAccepted()));
        assertThat(started.path("status").asText()).isIn("reading", "done");
        assertThat(started.path("reused").asBoolean()).isFalse();
        String id = started.path("id").asText();
        assertThat(waitFor(product, id, "done")).isEqualTo("done");

        JsonNode done = read(mvc.perform(get("/api/v1/products/" + product + "/evidence/" + id).with(author())).andExpect(status().isOk()));
        assertThat(done.path("issuer").path("key").asText()).isEqualTo("ksrc");
        assertThat(done.path("codes").path(0).path("value").asText()).isEqualTo("HBSE-MGE-24032");
        assertThat(done.path("version").asText()).isEqualTo("Version 1.0 · 2024-04");
        assertThat(done.path("readerVersion").asText()).isEqualTo("r1");
        assertThat(done.path("fileSha256").asText()).hasSize(64);
        assertThat(done.path("productMatch").path("pages").toString()).isEqualTo("[1,2]");
        assertThat(done.path("isCurrent").asBoolean()).isTrue();
        // 목록과 상세는 파일 본문과 쪽별 글자를 싣지 않는다
        assertThat(done.has("pages")).isFalse();
        assertThat(done.toString()).doesNotContain("본문");
        verify(agent, times(1)).post(eq("/internal/evidence/parse"), any(), any(Duration.class));
    }

    @Test
    void sameFileIsNotReadAgainUntilTheReaderChanges() throws Exception {
        answer(parsed("ksrc", "HBSE-MGE-24032", "Version 1.0 · 2024-04"));
        String product = product();
        String id = read(upload(product, pdf("same")).andExpect(status().isAccepted())).path("id").asText();
        waitFor(product, id, "done");

        JsonNode again = read(upload(product, pdf("same")).andExpect(status().isOk()));
        assertThat(again.path("id").asText()).isEqualTo(id);
        assertThat(again.path("reused").asBoolean()).isTrue();
        verify(agent, times(1)).post(eq("/internal/evidence/parse"), any(), any(Duration.class));

        // 판독기가 새 버전이 되면 같은 파일도 다시 읽는다
        readerVersion = "r2";
        answer(parsed("ksrc", "HBSE-MGE-24032", "Version 1.0 · 2024-04"));
        JsonNode reread = read(upload(product, pdf("same")).andExpect(status().isAccepted()));
        assertThat(reread.path("id").asText()).isEqualTo(id);
        assertThat(waitFor(product, id, "done")).isEqualTo("done");
        assertThat(read(mvc.perform(get("/api/v1/products/" + product + "/evidence/" + id).with(author()))).path("readerVersion").asText())
                .isEqualTo("r2");
        verify(agent, times(2)).post(eq("/internal/evidence/parse"), any(), any(Duration.class));
    }

    @Test
    void sameCodeWithDifferentFileIsARevisionAndTheOldOneStaysAsHistory() throws Exception {
        String product = product();
        answer(parsed("ksrc", "HBSE-MGE-24032", "Version 1.0 · 2024-04"));
        String first = read(upload(product, pdf("old")).andExpect(status().isAccepted())).path("id").asText();
        waitFor(product, first, "done");
        answer(parsed("ksrc", "HBSE-MGE-24032", "Version 1.1 · 2024-09"));
        String second = read(upload(product, pdf("new")).andExpect(status().isAccepted())).path("id").asText();
        assertThat(waitFor(product, second, "done")).isEqualTo("done");

        // 개정본 판단은 결과를 저장한 직후에 이어서 일어난다
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        JsonNode newer;
        do {
            newer = read(mvc.perform(get("/api/v1/products/" + product + "/evidence/" + second).with(author())));
            if (!newer.path("supersedes").isNull()) break;
            Thread.sleep(50);
        } while (Instant.now().isBefore(deadline));
        JsonNode older = read(mvc.perform(get("/api/v1/products/" + product + "/evidence/" + first).with(author())));
        assertThat(older.path("isCurrent").asBoolean()).isFalse();
        assertThat(newer.path("isCurrent").asBoolean()).isTrue();
        assertThat(newer.path("supersedes").asText()).isEqualTo(first);
        assertThat(newer.path("changeNote").asText()).contains("HBSE-MGE-24032").contains("Version 1.0 · 2024-04").contains("Version 1.1 · 2024-09");

        // 같은 코드라도 다른 기관의 파일은 개정본이 아니다
        answer(parsed("probem", "HBSE-MGE-24032", "개정 이력 1건 · 2025-01-17"));
        String third = read(upload(product, pdf("other issuer")).andExpect(status().isAccepted())).path("id").asText();
        waitFor(product, third, "done");
        assertThat(read(mvc.perform(get("/api/v1/products/" + product + "/evidence/" + third).with(author()))).path("supersedes").isNull()).isTrue();
        assertThat(read(mvc.perform(get("/api/v1/products/" + product + "/evidence/" + second).with(author()))).path("isCurrent").asBoolean()).isTrue();
    }

    /** 프로브엠 보고서: 연구번호(없으면 null)와 시료번호, 표지의 시험 제목. */
    ObjectNode probem(String study, String sample, String test, String version) {
        ObjectNode out = parsed("probem", "unused", version);
        out.putObject("issuer").put("key", "probem").put("name", "프로브엠 피부임상연구센터");
        var codes = out.putArray("codes");
        if (study != null) codes.addObject().put("label", "연구번호").put("value", study).put("pages", 30).put("status", "confirmed");
        codes.addObject().put("label", "시료번호").put("value", sample).put("pages", 3).put("status", "confirmed");
        out.putArray("pages").addObject().put("n", 1).put("kind", "text").put("method", "text_layer").put("chars", 120)
                .put("text", study + "\n인체적용시험 결과보고서\n\"투집 포어본드\"의\n" + test + "에 대한\n인체적용시험 결과보고서");
        return out;
    }

    String readAs(String product, ObjectNode result, String body) throws Exception {
        answer(result);
        String id = read(upload(product, pdf(body)).andExpect(status().isAccepted())).path("id").asText();
        assertThat(waitFor(product, id, "done")).isEqualTo("done");
        return id;
    }

    JsonNode evidence(String product, String id) throws Exception {
        return read(mvc.perform(get("/api/v1/products/" + product + "/evidence/" + id).with(author())));
    }

    @Test
    void reportsOnTheSameSampleButDifferentStudiesAreBothKept() throws Exception {
        String product = product();
        String irritation = readAs(product,
                probem("PM-25J17-SA017", "PM-25SA-018", "피부첩포에 의한 피부 일차자극 평가", "개정 이력 1건 · 2025-01-17"), "irritation");
        String lifting = readAs(product,
                probem("PM-25J22-EF031", "PM-25SA-018", "모공부위 리프팅 개선 평가", "2025-02-11"), "lifting");
        // 연구번호를 읽지 못한 보고서도 시료번호만으로 다른 보고서를 내리지 않는다
        String unnumbered = readAs(product, probem(null, "PM-25SA-018", "모공 면적 개선 평가", "2025-03"), "unnumbered");

        for (String id : java.util.List.of(irritation, lifting, unnumbered)) {
            JsonNode row = evidence(product, id);
            assertThat(row.path("isCurrent").asBoolean()).as(id).isTrue();
            assertThat(row.path("supersedes").isNull()).as(id).isTrue();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.product_evidence WHERE product_id = CAST(? AS uuid) AND is_current",
                Integer.class, product)).isEqualTo(3);
    }

    @Test
    void theSameStudyCodeOnADifferentTestIsNotARevisionButTheSameTestIs() throws Exception {
        String product = product();
        String first = readAs(product,
                probem("PM-25J17-SA017", "PM-25SA-018", "피부첩포에 의한 피부 일차자극 평가", "개정 이력 1건 · 2025-01-17"), "v1");
        // 같은 연구번호라도 표지의 시험 제목이 다르면 다른 시험으로 보고 둘 다 남긴다
        String other = readAs(product,
                probem("PM-25J17-SA017", "PM-25SA-018", "모공부위 리프팅 개선 평가", "2025-02-11"), "other test");
        assertThat(evidence(product, first).path("isCurrent").asBoolean()).isTrue();
        assertThat(evidence(product, other).path("supersedes").isNull()).isTrue();

        // 같은 연구번호, 같은 시험 제목의 다른 파일은 개정본이다
        String revised = readAs(product,
                probem("PM-25J17-SA017", "PM-25SA-018", "피부첩포에 의한 피부 일차자극 평가", "개정 이력 2건 · 2025-02-03"), "v2");
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (evidence(product, revised).path("supersedes").isNull() && Instant.now().isBefore(deadline)) Thread.sleep(50);
        assertThat(evidence(product, revised).path("supersedes").asText()).isEqualTo(first);
        assertThat(evidence(product, first).path("isCurrent").asBoolean()).isFalse();
        assertThat(evidence(product, other).path("isCurrent").asBoolean()).isTrue();
    }

    @Test
    void reviewsOfAProductAreBlockedWhileItsEvidenceIsBeingRead() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        ObjectNode result = parsed("ksrc", "HBSE-MGE-24032", "Version 1.0 · 2024-04");
        doAnswer(call -> {
            release.await(20, TimeUnit.SECONDS);
            return result;
        }).when(agent).post(eq("/internal/evidence/parse"), any(), any(Duration.class));
        String product = product();
        String id = read(upload(product, pdf("slow")).andExpect(status().isAccepted())).path("id").asText();
        try {
            assertThat(read(mvc.perform(get("/api/v1/products/" + product).with(author()))).path("evidenceReading").asBoolean()).isTrue();
            byte[] png = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4};
            var review = multipart("/api/v1/reviews").file(new MockMultipartFile("files", "ad.png", "image/png", png))
                    .param("metadata", "{\"productId\":\"" + product + "\",\"product\":{\"name\":\"x\"}}").with(author());
            mvc.perform(review).andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("PRODUCT_EVIDENCE_READING"));
            // 막힌 접수는 크레딧도 검토도 남기지 않는다
            assertThat(jdbc.queryForObject("SELECT credit_balance FROM app.users WHERE id=CAST(? AS uuid)", Integer.class, AUTHOR)).isEqualTo(1000);
            assertThat(read(mvc.perform(get("/api/v1/products/" + product + "/reviews").with(author())))).isEmpty();
        } finally {
            release.countDown();
        }
        assertThat(waitFor(product, id, "done")).isEqualTo("done");
        assertThat(read(mvc.perform(get("/api/v1/products/" + product).with(author()))).path("evidenceReading").asBoolean()).isFalse();
        byte[] png = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4};
        mvc.perform(multipart("/api/v1/reviews").file(new MockMultipartFile("files", "ad.png", "image/png", png))
                .param("metadata", "{\"productId\":\"" + product + "\",\"product\":{\"name\":\"x\"}}").with(author()))
                .andExpect(status().is2xxSuccessful());
    }

    @Test
    void failedReadingShowsItsCodeDoesNotBlockReviewsAndCanBeReread() throws Exception {
        doThrow(new AgentClient.AgentFailure(422, "EVIDENCE_PAGE_LIMIT"))
                .when(agent).post(eq("/internal/evidence/parse"), any(), any(Duration.class));
        String product = product();
        String id = read(upload(product, pdf("big")).andExpect(status().isAccepted())).path("id").asText();
        assertThat(waitFor(product, id, "failed")).isEqualTo("failed");
        JsonNode failed = read(mvc.perform(get("/api/v1/products/" + product + "/evidence/" + id).with(author())));
        assertThat(failed.path("failureCode").asText()).isEqualTo("EVIDENCE_PAGE_LIMIT");
        assertThat(read(mvc.perform(get("/api/v1/products/" + product).with(author()))).path("evidenceReading").asBoolean()).isFalse();

        answer(parsed("ksrc", "HBSE-MGE-24032", "Version 1.0 · 2024-04"));
        mvc.perform(post("/api/v1/products/" + product + "/evidence/" + id + "/reread").with(author())).andExpect(status().isAccepted());
        assertThat(waitFor(product, id, "done")).isEqualTo("done");
        assertThat(read(mvc.perform(get("/api/v1/products/" + product + "/evidence/" + id).with(author()))).path("failureCode").isNull()).isTrue();
    }

    @Test
    void evidenceIsScopedToTheTenantAndTheFileIsOnlyForTheAgent() throws Exception {
        answer(parsed("ksrc", "HBSE-MGE-24032", "Version 1.0 · 2024-04"));
        String product = product();
        String id = read(upload(product, pdf("mine")).andExpect(status().isAccepted())).path("id").asText();
        waitFor(product, id, "done");

        mvc.perform(get("/api/v1/products/" + product + "/evidence").with(outsider())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/products/" + product + "/evidence/" + id).with(outsider())).andExpect(status().isNotFound());
        mvc.perform(multipart("/api/v1/products/" + product + "/evidence").file(pdf("x")).with(outsider())).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/products/" + product + "/evidence/" + id).with(outsider())).andExpect(status().isNotFound());

        mvc.perform(get("/internal/evidence/" + id + "/file")).andExpect(status().isUnauthorized());
        byte[] bytes = mvc.perform(get("/internal/evidence/" + id + "/file").header("Authorization", TOKEN))
                .andExpect(status().isOk()).andExpect(content().contentType("application/pdf"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(bytes, StandardCharsets.UTF_8)).startsWith("%PDF-1.4 mine");

        mvc.perform(delete("/api/v1/products/" + product + "/evidence/" + id).with(author())).andExpect(status().isOk());
        assertThat(read(mvc.perform(get("/api/v1/products/" + product + "/evidence").with(author())))).isEmpty();
    }

    @Test
    void unsupportedAndOversizedFilesAreRejectedBeforeAnythingIsStored() throws Exception {
        String product = product();
        mvc.perform(multipart("/api/v1/products/" + product + "/evidence")
                .file(new MockMultipartFile("file", "a.txt", "text/plain", "hello world".getBytes())).with(author()))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(multipart("/api/v1/products/" + product + "/evidence")
                .file(new MockMultipartFile("file", "a.pdf", "application/pdf", new byte[0])).with(author()))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.product_evidence WHERE product_id=CAST(? AS uuid)", Integer.class, product)).isZero();
    }

    ObjectNode threePages(boolean matched) {
        ObjectNode out = parsed("ksrc", "HBSE-MGE-24032", "Version 1.0 · 2024-04");
        var pages = out.putArray("pages");
        pages.addObject().put("n", 1).put("text", "공통 표지");
        pages.addObject().put("n", 2).put("text", "알파 크림 본문");
        pages.addObject().put("n", 3).put("text", "베타 마스크 본문");
        if (!matched) out.putNull("productMatch");
        return out;
    }

    /** 검토를 접수해 Agent 호출을 붙잡아 두고, 그 호출이 받은 요청(run 토큰 포함)을 돌려준다. */
    JsonNode holdReview(String product, CountDownLatch release) throws Exception {
        AtomicReference<JsonNode> sent = new AtomicReference<>();
        doAnswer(call -> {
            sent.set(call.getArgument(1));
            release.await(20, TimeUnit.SECONDS);
            ObjectNode review = json.createObjectNode();
            review.putObject("result").putArray("findings");
            return review;
        }).when(agent).post(eq("/internal/runs"), any());
        byte[] png = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4};
        mvc.perform(multipart("/api/v1/reviews").file(new MockMultipartFile("files", "ad.png", "image/png", png))
                .param("metadata", "{\"productId\":\"" + product + "\",\"product\":{\"name\":\"x\"}}").with(author()))
                .andExpect(status().is2xxSuccessful());
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (sent.get() == null && Instant.now().isBefore(deadline)) Thread.sleep(20);
        assertThat(sent.get()).isNotNull();
        return sent.get();
    }

    JsonNode storedEvidence(JsonNode run) throws Exception {
        return read(mvc.perform(get("/internal/reviews/" + run.path("reviewId").asText() + "/product-evidence")
                        .param("versionNo", "1").header("Authorization", TOKEN).header("X-Run-Token", run.path("runToken").asText()))
                .andExpect(status().isOk()));
    }

    @Test
    void reviewUsesTheStoredReadResultOfItsProductAndOnlyThatProductsPages() throws Exception {
        String product = product(), empty = product();
        answer(threePages(true));
        String id = read(upload(product, pdf("stored")).andExpect(status().isAccepted())).path("id").asText();
        assertThat(waitFor(product, id, "done")).isEqualTo("done");
        CountDownLatch release = new CountDownLatch(1);
        try {
            JsonNode run = holdReview(product, release);
            assertThat(run.path("productEvidenceCount").asInt()).isEqualTo(1);
            JsonNode doc = storedEvidence(run).path("documents").path(0);
            assertThat(doc.path("codes").path(0).path("value").asText()).isEqualTo("HBSE-MGE-24032");
            assertThat(doc.path("productPages").toString()).isEqualTo("[1,2]");
            assertThat(doc.path("text").asText()).contains("공통 표지").contains("알파 크림 본문").doesNotContain("베타 마스크 본문");
            assertThat(doc.path("productNotInReport").asBoolean()).isFalse();
            // 다른 검토의 run 토큰으로는 열리지 않는다
            mvc.perform(get("/internal/reviews/" + run.path("reviewId").asText() + "/product-evidence").param("versionNo", "1")
                    .header("Authorization", TOKEN).header("X-Run-Token", "wrong")).andExpect(status().isForbidden());
            mvc.perform(get("/internal/reviews/" + run.path("reviewId").asText() + "/product-evidence").param("versionNo", "1")
                    .header("X-Run-Token", run.path("runToken").asText())).andExpect(status().isUnauthorized());
        } finally {
            release.countDown();
        }
        // 증빙이 없는 제품은 0건이라 Agent가 증빙 도구를 쓰지 않는다
        CountDownLatch second = new CountDownLatch(1);
        try {
            JsonNode run = holdReview(empty, second);
            assertThat(run.path("productEvidenceCount").asInt()).isZero();
            assertThat(storedEvidence(run).path("documents")).isEmpty();
        } finally {
            second.countDown();
        }
    }

    @Test
    void aReportThatDoesNotCoverTheProductGivesNoTextAndSaysSo() throws Exception {
        String product = product();
        answer(threePages(false));
        String id = read(upload(product, pdf("other product")).andExpect(status().isAccepted())).path("id").asText();
        waitFor(product, id, "done");
        CountDownLatch release = new CountDownLatch(1);
        try {
            JsonNode doc = storedEvidence(holdReview(product, release)).path("documents").path(0);
            assertThat(doc.path("productNotInReport").asBoolean()).isTrue();
            assertThat(doc.path("text").asText()).isEmpty();
        } finally {
            release.countDown();
        }
    }

    @Test
    void evidenceJobRowsFollowTheQueueLifecycleAndDisappearWithTheEvidence() throws Exception {
        answer(parsed("ksrc", "HBSE-MGE-24032", "Version 1.0 · 2024-04"));
        String product = product();
        String id = read(upload(product, pdf("job")).andExpect(status().isAccepted())).path("id").asText();
        waitFor(product, id, "done");
        jobs.create(id);
        assertThat(jobs.ready()).contains(id);
        jobs.published(id);
        assertThat(jobs.ready()).doesNotContain(id);
        assertThat(jobs.claim(id)).hasSize(1).first().satisfies(row -> assertThat(((Number) row.get("attempts")).intValue()).isEqualTo(1));
        assertThat(jobs.claim(id)).isEmpty();
        // 다음 재시도 시각 전에는 나가지 않는다
        jobs.retry(id, 3600, "IOException");
        assertThat(jobs.ready()).doesNotContain(id);
        jdbc.update("UPDATE app.evidence_jobs SET next_attempt_at = now() WHERE evidence_id = CAST(? AS uuid)", id);
        assertThat(jobs.ready()).contains(id);
        assertThat(((Number) jobs.claim(id).getFirst().get("attempts")).intValue()).isEqualTo(2);
        // 소비자가 죽어 running으로 남으면 일정 시간 뒤 되살릴 수 있다
        assertThat(jobs.stale()).isEmpty();
        jdbc.update("UPDATE app.evidence_jobs SET updated_at = now() - interval '8 minutes' WHERE evidence_id = CAST(? AS uuid)", id);
        assertThat(jobs.stale()).extracting(row -> row.get("id")).contains(id);
        jobs.recover(id, "retry");
        assertThat(jobs.ready()).contains(id);
        // 다시 읽기는 같은 행을 처음 상태로 돌린다
        jobs.done(id);
        jobs.create(id);
        assertThat(jdbc.queryForObject("SELECT attempts FROM app.evidence_jobs WHERE evidence_id = CAST(? AS uuid)", Integer.class, id)).isZero();
        mvc.perform(delete("/api/v1/products/" + product + "/evidence/" + id).with(author())).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.evidence_jobs WHERE evidence_id = CAST(? AS uuid)", Integer.class, id)).isZero();
    }

    @Test
    void pagesWhereTheTwoReadersDisagreeAreStoredShownAndPassedToReviews() throws Exception {
        ObjectNode result = threePages(true);
        result.putArray("checkPages").add(2).add(3);
        ((ObjectNode) result.path("productMatch")).putArray("checkPages").add(2);
        answer(result);
        String product = product();
        String id = read(upload(product, pdf("check")).andExpect(status().isAccepted())).path("id").asText();
        assertThat(waitFor(product, id, "done")).isEqualTo("done");
        JsonNode view = read(mvc.perform(get("/api/v1/products/" + product + "/evidence/" + id).with(author())));
        assertThat(view.path("checkPages").toString()).isEqualTo("[2,3]");
        assertThat(view.path("productMatch").path("checkPages").toString()).isEqualTo("[2]");
        CountDownLatch release = new CountDownLatch(1);
        try {
            JsonNode doc = storedEvidence(holdReview(product, release)).path("documents").path(0);
            // 이 제품의 쪽 중 숫자가 의심되는 쪽만 검토에 알린다(3쪽은 이 제품 쪽이 아니다)
            assertThat(doc.path("checkPages").toString()).isEqualTo("[2]");
        } finally {
            release.countDown();
        }
        // 이 필드가 없는 예전 Agent 응답도 빈 목록으로 저장된다
        answer(threePages(true));
        String older = read(upload(product, pdf("no check field")).andExpect(status().isAccepted())).path("id").asText();
        waitFor(product, older, "done");
        assertThat(read(mvc.perform(get("/api/v1/products/" + product + "/evidence/" + older).with(author()))).path("checkPages")).isEmpty();
    }
}
