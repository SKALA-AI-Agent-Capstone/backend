package org.skala.capstone;

import org.skala.capstone.common.AgentClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 프론트가 읽는 검토 목록과 상세 응답의 필드 이름을 고정한다. 백엔드 구조 정리 전에 만든 안전망이다.
 * 검토는 실제 접수 API로 만들고, Agent 응답만 고정된 결과(문구 1개, 문구와 사진 분석 완료)로 대신한다.
 * Agent가 보내는 결과 안의 필드는 Agent 계약이므로 여기서는 서버가 만들거나 덧붙이는 필드만 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {
    "capstone.internal-token=unit-test-only-token-not-for-production-12345",
    "capstone.agent-base-url=http://127.0.0.1:1",
    "capstone.jwt-secret=unit-test-only-jwt-secret-not-for-production-12345"
})
class ReviewResponseShapeTest {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @MockitoBean AgentClient agent;
    @MockitoBean StringRedisTemplate redis;

    static final String TENANT = "00000000-0000-0000-0000-000000000101";
    static final String AUTHOR = "00000000-0000-0000-0000-000000000111";

    @BeforeEach
    @SuppressWarnings("unchecked")
    void seed() throws Exception {
        jdbc.update("INSERT INTO app.tenants VALUES (CAST(? AS uuid),'shape') ON CONFLICT DO NOTHING", TENANT);
        jdbc.update("INSERT INTO app.users (id,tenant_id,email,name,password_hash,role,credit_balance) " +
            "VALUES (CAST(? AS uuid),CAST(? AS uuid),'shape-author@test','작성자','x','author',1000) " +
            "ON CONFLICT (id) DO UPDATE SET credit_balance=1000", AUTHOR, TENANT);
        when(redis.opsForValue()).thenReturn(mock(ValueOperations.class));
        when(agent.post(anyString(), any())).thenReturn(agentResult());
    }

    ObjectNode agentResult() {
        ObjectNode response = json.createObjectNode();
        ObjectNode result = response.putObject("result");
        ObjectNode finding = result.putArray("findings").addObject();
        finding.put("id", "text-0").put("phrase", "7일 만에 주름 개선").put("severity", "high").put("reason", "r").put("claimType", "wrinkle");
        finding.putArray("rewrites").add("주름 개선에 도움");
        result.putArray("visualFindings");
        result.putObject("pipelineStatus").put("text", "complete").put("visual", "complete");
        return response;
    }

    RequestPostProcessor author() {
        return jwt().jwt(j -> j.subject(AUTHOR).claim("tenantId", TENANT).claim("role", "author"));
    }

    JsonNode read(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    /** 접수하고 백그라운드 실행이 끝날 때까지 기다린 뒤 검토 id를 돌려준다. */
    String submitAndWait() throws Exception {
        byte[] png = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4};
        var file = new MockMultipartFile("files", "ad.png", "image/png", png);
        JsonNode created = read(mvc.perform(multipart("/api/v1/reviews").file(file)
                .param("metadata", "{\"product\":{\"name\":\"주름 크림\"},\"media\":[\"online\"]}")
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

    static List<String> keys(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        Collections.sort(names);
        return names;
    }

    // 2026-10-22 검토 전체 승인을 없애면서 version.approval과 hitl의 승인 필드(status, comment, decided*, autoApproved, canApprove)를 뺐다.
    static final List<String> VERSION_FIELDS = List.of(
        "authorId", "authorName", "createdAt", "error", "input", "no", "originalsDiscardedAt", "result", "status", "steps");
    static final List<String> HITL_FIELDS = List.of("undecidedCount", "violationCount");

    @Test
    void listItemsKeepTheirCurrentFields() throws Exception {
        String id = submitAndWait();
        JsonNode list = read(mvc.perform(get("/api/v1/reviews").with(author())).andExpect(status().isOk()));
        JsonNode item = null;
        for (JsonNode candidate : list) if (candidate.path("id").asText().equals(id)) item = candidate;
        assertThat(item).isNotNull();

        assertThat(keys(item)).containsExactly(
            "authorId", "authorName", "campaign", "createdAt", "hitl", "id", "productId", "productName", "tenantId", "updatedAt", "versions");
        assertThat(keys(item.path("versions").get(0))).containsExactlyElementsOf(VERSION_FIELDS);
        // 목록은 결정 진행률(hitl)만 싣고 violations는 싣지 않는다
        assertThat(keys(item.path("hitl"))).containsExactlyElementsOf(HITL_FIELDS);
        // 기본 목록은 하위 호환을 위해 결과 전체를 그대로 싣는다. 목록 화면은 view=summary를 쓴다.
        assertThat(item.path("versions").get(0).path("result").path("findings")).hasSize(1);
    }

    @Test
    void summaryListReplacesResultsWithCountsOnly() throws Exception {
        String id = submitAndWait();
        String full = mvc.perform(get("/api/v1/reviews").with(author())).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        String compact = mvc.perform(get("/api/v1/reviews").param("view", "summary").with(author())).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        JsonNode item = null;
        for (JsonNode candidate : json.readTree(compact)) if (candidate.path("id").asText().equals(id)) item = candidate;
        assertThat(item).isNotNull();

        // 전체 목록과 같은 필드에 summary 표시만 더한다
        assertThat(keys(item)).containsExactly(
            "authorId", "authorName", "campaign", "createdAt", "hitl", "id", "productId", "productName", "summary", "tenantId", "updatedAt", "versions");
        assertThat(item.path("summary").asBoolean()).isTrue();
        JsonNode version = item.path("versions").get(0);
        assertThat(keys(version)).containsExactlyElementsOf(VERSION_FIELDS);
        assertThat(keys(version.path("input"))).containsExactly("copy", "evidence", "files", "media", "product");
        assertThat(keys(version.path("result"))).containsExactly("analyzedAt", "findingCount", "severityCounts");
        assertThat(version.path("result").path("findingCount").asInt()).isEqualTo(1);
        assertThat(version.path("result").path("severityCounts").path("high").asInt()).isEqualTo(1);
        assertThat(version.path("result").path("severityCounts").path("medium").asInt()).isZero();
        assertThat(keys(item.path("hitl"))).containsExactlyElementsOf(HITL_FIELDS);
        // 요약은 전체 목록보다 작다(실제 결과에서는 문구별 근거 원문과 OCR 문구가 빠져 차이가 훨씬 크다)
        assertThat(compact.length()).isLessThan(full.length());
        // 다른 view 값은 예전 전체 목록이다
        JsonNode other = json.readTree(mvc.perform(get("/api/v1/reviews").param("view", "unknown").with(author()))
            .andReturn().getResponse().getContentAsString());
        assertThat(other.get(0).has("summary")).isFalse();
    }

    @Test
    void detailKeepsItsCurrentFields() throws Exception {
        String id = submitAndWait();
        JsonNode detail = read(mvc.perform(get("/api/v1/reviews/" + id).with(author())).andExpect(status().isOk()));
        JsonNode version = detail.path("versions").get(0);

        assertThat(keys(detail)).containsExactly(
            "authorId", "authorName", "campaign", "createdAt", "hitl", "id", "productId", "productName", "tenantId", "updatedAt", "versions", "violations");
        assertThat(keys(version)).containsExactlyElementsOf(VERSION_FIELDS);
        assertThat(version.path("status").asText()).isEqualTo("done");
        assertThat(keys(version.path("input"))).containsExactly("copy", "evidence", "files", "media", "product");
        assertThat(keys(version.path("input").path("files").get(0))).containsExactly("docType", "id", "kind", "mime", "name", "role", "size");
        assertThat(keys(version.path("result"))).containsExactly("findings", "pipelineStatus", "visualFindings");
        // 서버는 Agent의 문구에 violationId를 덧붙인다
        assertThat(keys(version.path("result").path("findings").get(0)))
            .containsExactly("claimType", "id", "phrase", "reason", "rewrites", "severity", "violationId");

        assertThat(keys(detail.path("hitl"))).containsExactlyElementsOf(HITL_FIELDS);
        assertThat(keys(detail.path("violations").get(0))).containsExactly(
            "decidedAt", "decidedBy", "decidedByName", "finalResolution", "finalText", "finalTextValidated", "findingId",
            "humanRetryCount", "humanRetryLimit", "id", "rewriteAttemptCount", "rewriteSuggestion", "rewrites", "severity", "textExcerpt");
        assertThat(keys(detail.path("violations").get(0).path("rewrites").get(0)))
            .containsExactly("attemptNo", "createdAt", "requestedByHuman", "retryNote", "text");
    }
}
