package org.skala.capstone;

import org.skala.capstone.auth.TokenService;
import org.skala.capstone.common.AgentClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 회원가입, 로그인, Refresh 회전과 재사용, 로그아웃, 팀원 추가의 현재 동작을 고정한다.
 * 백엔드 구조 정리 전에 만든 안전망이다. 실제 JWT와 Refresh 쿠키를 발급받아 쓰고,
 * 시간이 지난 상황(재사용 유예 경과, 만료)은 DB 시각을 과거로 옮겨 재현한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {
    "capstone.internal-token=unit-test-only-token-not-for-production-12345",
    "capstone.agent-base-url=http://127.0.0.1:1",
    "capstone.jwt-secret=unit-test-only-jwt-secret-not-for-production-12345"
})
class AuthFlowTest {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @MockitoBean AgentClient agent;
    @MockitoBean StringRedisTemplate redis;

    static final String PASSWORD = "correct-horse-1";

    /** 로그인 응답 하나: 본문, Access Token, 쿠키로 받은 Refresh Token 원문. */
    record Session(JsonNode body, String access, String refresh) {}

    static String email() { return "user-" + UUID.randomUUID() + "@example.test"; }

    ResultActions postJson(String path, Map<String, ?> body, Cookie... cookies) throws Exception {
        var request = post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        if (cookies.length > 0) request.cookie(cookies);
        return mvc.perform(request);
    }

    Session session(ResultActions result) throws Exception {
        MockHttpServletResponse response = result.andReturn().getResponse();
        JsonNode body = json.readTree(response.getContentAsString());
        return new Session(body, body.path("accessToken").asText(), refreshFrom(response));
    }

    static String setCookie(MockHttpServletResponse response) {
        return response.getHeaders(HttpHeaders.SET_COOKIE).stream()
            .filter(h -> h.startsWith(TokenService.COOKIE + "=")).findFirst().orElse(null);
    }

    static String refreshFrom(MockHttpServletResponse response) {
        String header = setCookie(response);
        if (header == null) return null;
        return header.substring(TokenService.COOKIE.length() + 1, header.indexOf(';'));
    }

    static Cookie cookie(String refresh) { return new Cookie(TokenService.COOKIE, refresh); }

    Session signup(String email) throws Exception {
        return session(postJson("/api/v1/users/signup",
            Map.of("email", email, "password", PASSWORD, "name", "관리자", "tenantName", "테스트 기업"))
            .andExpect(status().isOk()));
    }

    ResultActions me(String access) throws Exception {
        return mvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + access));
    }

    ResultActions refresh(String refresh) throws Exception {
        var request = post("/api/v1/users/refresh");
        if (refresh != null) request.cookie(cookie(refresh));
        return mvc.perform(request);
    }

    int sessions(String userId) {
        return jdbc.queryForObject("SELECT count(*) FROM app.sessions WHERE user_id=CAST(? AS uuid)", Integer.class, userId);
    }

    @Test
    void signupCreatesAnAdminWorkspaceAndIssuesBothTokens() throws Exception {
        String email = email();
        var result = postJson("/api/v1/users/signup",
            Map.of("email", email.toUpperCase(Locale.ROOT), "password", PASSWORD, "name", "관리자", "tenantName", "테스트 기업"))
            .andExpect(status().isOk());
        var response = result.andReturn().getResponse();
        JsonNode body = json.readTree(response.getContentAsString());

        assertThat(body.path("user").path("email").asText()).isEqualTo(email);
        assertThat(body.path("user").path("role").asText()).isEqualTo("admin");
        assertThat(body.path("user").path("tenantId").asText()).isEqualTo(body.path("tenant").path("id").asText());
        assertThat(body.path("tenant").path("name").asText()).isEqualTo("테스트 기업");
        assertThat(body.path("expiresIn").asLong()).isEqualTo(900);
        assertThat(body.path("accessToken").asText()).isNotBlank();
        assertThat(fieldNames(body)).containsExactlyInAnyOrder("user", "tenant", "accessToken", "expiresIn");
        assertThat(fieldNames(body.path("user"))).containsExactlyInAnyOrder("id", "tenantId", "name", "email", "role");

        String header = setCookie(response);
        assertThat(header).contains("HttpOnly").contains("Secure").contains("SameSite=Strict")
            .contains("Path=/api/v1/users").contains("Max-Age=" + 14 * 24 * 3600);

        // DB에는 Refresh Token 원문이 아니라 SHA-256 해시만 남는다
        String raw = refreshFrom(response);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.refresh_tokens WHERE token_hash=?", Integer.class, raw)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.refresh_tokens WHERE token_hash=?", Integer.class, TokenService.hash(raw))).isEqualTo(1);
    }

    @Test
    void signupRejectsInvalidAndDuplicateInput() throws Exception {
        String email = email();
        signup(email);
        postJson("/api/v1/users/signup", Map.of("email", email, "password", PASSWORD, "name", "n", "tenantName", "t"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("EMAIL_EXISTS"));
        postJson("/api/v1/users/signup", Map.of("email", "not-an-email", "password", PASSWORD, "name", "n", "tenantName", "t"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("INVALID_EMAIL"));
        postJson("/api/v1/users/signup", Map.of("email", email(), "password", "short", "name", "n", "tenantName", "t"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("INVALID_PASSWORD"));
        postJson("/api/v1/users/signup", Map.of("email", email(), "password", PASSWORD, "name", "", "tenantName", "t"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("INVALID_NAME"));
        postJson("/api/v1/users/signup", Map.of("email", email(), "password", PASSWORD, "name", "n"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("INVALID_TENANTNAME"));
    }

    @Test
    void loginChecksThePasswordAndIgnoresEmailCase() throws Exception {
        String email = email();
        signup(email);
        postJson("/api/v1/users/login", Map.of("email", email, "password", "wrong-password"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("INVALID_LOGIN"));
        postJson("/api/v1/users/login", Map.of("email", "nobody-" + email, "password", PASSWORD))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("INVALID_LOGIN"));
        Session login = session(postJson("/api/v1/users/login", Map.of("email", email.toUpperCase(Locale.ROOT), "password", PASSWORD))
            .andExpect(status().isOk()));
        me(login.access()).andExpect(status().isOk()).andExpect(jsonPath("$.user.email").value(email));
    }

    @Test
    void loginInTheSameBrowserEndsThePreviousSession() throws Exception {
        String email = email();
        Session first = signup(email);
        String user = first.body().path("user").path("id").asText();
        Session second = session(postJson("/api/v1/users/login", Map.of("email", email, "password", PASSWORD), cookie(first.refresh()))
            .andExpect(status().isOk()));

        assertThat(sessions(user)).isEqualTo(1);
        me(first.access()).andExpect(status().isUnauthorized());
        me(second.access()).andExpect(status().isOk());
    }

    @Test
    void meNeedsAValidAccessToken() throws Exception {
        mvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized());
        me("not-a-jwt").andExpect(status().isUnauthorized());
        Session session = signup(email());
        JsonNode body = json.readTree(me(session.access()).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(fieldNames(body)).containsExactlyInAnyOrder("user", "tenant");
    }

    @Test
    void refreshRotatesTheTokenAndKeepsTheSession() throws Exception {
        Session start = signup(email());
        Session next = session(refresh(start.refresh()).andExpect(status().isOk()));

        assertThat(next.refresh()).isNotBlank().isNotEqualTo(start.refresh());
        assertThat(next.access()).isNotEqualTo(start.access());
        me(next.access()).andExpect(status().isOk());
        // 회전해도 세션은 같아서 이전 Access Token도 만료 전까지 쓸 수 있다
        me(start.access()).andExpect(status().isOk());
        session(refresh(next.refresh()).andExpect(status().isOk()));
    }

    @Test
    void reusingARotatedTokenWithinTheGraceWindowIsRetryableAndKeepsTheSession() throws Exception {
        Session start = signup(email());
        Session next = session(refresh(start.refresh()).andExpect(status().isOk()));

        var response = refresh(start.refresh())
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.message").value("REFRESH_TOKEN_ROTATED"))
            .andReturn().getResponse();
        // 다른 탭이 이미 받은 새 쿠키를 지우지 않는다
        assertThat(setCookie(response)).isNull();
        me(next.access()).andExpect(status().isOk());
        refresh(next.refresh()).andExpect(status().isOk());
    }

    @Test
    void reusingARotatedTokenAfterTheGraceWindowRevokesTheWholeSession() throws Exception {
        Session start = signup(email());
        String user = start.body().path("user").path("id").asText();
        Session next = session(refresh(start.refresh()).andExpect(status().isOk()));
        jdbc.update("UPDATE app.refresh_tokens SET used_at=now()-interval '31 seconds' WHERE token_hash=?", TokenService.hash(start.refresh()));

        var response = refresh(start.refresh())
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.message").value("REFRESH_TOKEN_REUSED"))
            .andReturn().getResponse();
        assertThat(setCookie(response)).contains("Max-Age=0");
        assertThat(sessions(user)).isZero();
        me(next.access()).andExpect(status().isUnauthorized());
        refresh(next.refresh()).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void expiredMissingAndUnknownRefreshTokensAreRejected() throws Exception {
        refresh(null).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("REFRESH_TOKEN_MISSING"));
        refresh("unknown-token").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("INVALID_REFRESH_TOKEN"));

        Session start = signup(email());
        String user = start.body().path("user").path("id").asText();
        jdbc.update("UPDATE app.refresh_tokens SET expires_at=now()-interval '1 second' WHERE token_hash=?", TokenService.hash(start.refresh()));
        var response = refresh(start.refresh())
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.message").value("REFRESH_TOKEN_EXPIRED"))
            .andReturn().getResponse();
        assertThat(setCookie(response)).contains("Max-Age=0");
        assertThat(sessions(user)).isZero();
    }

    @Test
    void logoutRevokesTheSessionFromTheCookieOrTheBearerToken() throws Exception {
        Session byCookie = signup(email());
        var response = mvc.perform(delete("/api/v1/users/logout").cookie(cookie(byCookie.refresh())))
            .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true))
            .andReturn().getResponse();
        assertThat(setCookie(response)).contains("Max-Age=0");
        me(byCookie.access()).andExpect(status().isUnauthorized());

        Session byBearer = signup(email());
        mvc.perform(delete("/api/v1/users/logout").header(HttpHeaders.AUTHORIZATION, "Bearer " + byBearer.access()))
            .andExpect(status().isOk());
        me(byBearer.access()).andExpect(status().isUnauthorized());
        refresh(byBearer.refresh()).andExpect(status().isUnauthorized());

        // 아무 자격 증명이 없어도 실패하지 않는다
        mvc.perform(delete("/api/v1/users/logout")).andExpect(status().isOk());
        mvc.perform(delete("/api/v1/users/logout").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt")).andExpect(status().isOk());
    }

    @Test
    void onlyAnAdminCanAddTeamMembersWhoCanThenLogIn() throws Exception {
        Session admin = signup(email());
        String tenant = admin.body().path("tenant").path("id").asText();
        String member = email();
        mvc.perform(post("/api/v1/users/team").header(HttpHeaders.AUTHORIZATION, "Bearer " + admin.access())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", member, "password", PASSWORD, "name", "작성자", "role", "author"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));

        Session author = session(postJson("/api/v1/users/login", Map.of("email", member, "password", PASSWORD)).andExpect(status().isOk()));
        assertThat(author.body().path("user").path("role").asText()).isEqualTo("author");
        assertThat(author.body().path("tenant").path("id").asText()).isEqualTo(tenant);

        mvc.perform(post("/api/v1/users/team").header(HttpHeaders.AUTHORIZATION, "Bearer " + author.access())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email(), "password", PASSWORD, "name", "n", "role", "author"))))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("ADMIN_REQUIRED"));
        mvc.perform(post("/api/v1/users/team").header(HttpHeaders.AUTHORIZATION, "Bearer " + admin.access())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email(), "password", PASSWORD, "name", "n", "role", "admin"))))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("INVALID_ROLE"));
    }

    // 인증 입력 회귀: 가입과 팀원 추가의 규칙을 동일하게 적용한다.

    @Test
    void teamDuplicateEmailUsesTheSignupErrorCode() throws Exception {
        Session admin = signup(email());
        String taken = email();
        signup(taken);
        mvc.perform(post("/api/v1/users/team").header(HttpHeaders.AUTHORIZATION, "Bearer " + admin.access())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", taken, "password", PASSWORD, "name", "n", "role", "author"))))
            // 회원가입과 팀원 추가가 같은 중복 이메일 코드를 반환한다
            .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("EMAIL_EXISTS"));
    }

    @Test
    void teamRejectsAnInvalidEmail() throws Exception {
        Session admin = signup(email());
        mvc.perform(post("/api/v1/users/team").header(HttpHeaders.AUTHORIZATION, "Bearer " + admin.access())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", "no-at-sign-" + UUID.randomUUID(), "password", PASSWORD, "name", "n", "role", "author"))))
            // 형식 오류는 사용자를 생성하기 전에 거절한다
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("INVALID_EMAIL"));
    }

    @Test
    void passwordWithOuterSpacesIsPreservedAtSignupAndLogin() throws Exception {
        String email = email();
        String spaced = "  " + PASSWORD + "  ";
        postJson("/api/v1/users/signup", Map.of("email", email, "password", spaced, "name", "n", "tenantName", "t"))
            .andExpect(status().isOk());
        // 같은 원문으로 로그인하고 공백을 제거한 다른 비밀번호는 거절한다
        postJson("/api/v1/users/login", Map.of("email", email, "password", spaced))
            .andExpect(status().isOk());
        postJson("/api/v1/users/login", Map.of("email", email, "password", PASSWORD))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("INVALID_LOGIN"));
    }

    @Test
    void teamMemberPasswordAlsoPreservesOuterSpaces() throws Exception {
        Session admin = signup(email());
        String member = email();
        String spaced = "  " + PASSWORD + "  ";
        mvc.perform(post("/api/v1/users/team").header(HttpHeaders.AUTHORIZATION, "Bearer " + admin.access())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", member.toUpperCase(Locale.ROOT), "password", spaced,
                    "name", "팀원", "role", "reviewer"))))
            .andExpect(status().isOk());
        postJson("/api/v1/users/login", Map.of("email", member, "password", spaced))
            .andExpect(status().isOk());
        postJson("/api/v1/users/login", Map.of("email", member, "password", PASSWORD))
            .andExpect(status().isUnauthorized());
    }

    static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
