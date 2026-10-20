package org.skala.capstone;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Access JWT(짧음)와 Refresh Token(길고, HttpOnly 쿠키, DB 해시 저장, 회전)을 발급하고 폐기한다. */
@Component
public class AuthTokens {
    public static final String COOKIE = "refresh_token";
    static final String COOKIE_PATH = "/api/v1/users";
    private static final SecureRandom RANDOM = new SecureRandom();

    public record Issued(String userId, String accessToken, long accessExpiresIn, ResponseCookie cookie) {}

    private final JdbcTemplate jdbc;
    private final JwtEncoder encoder;
    private final Duration accessTtl, refreshTtl, reuseGrace;
    private final boolean secureCookie;

    public AuthTokens(JdbcTemplate jdbc, JwtEncoder encoder,
                      @Value("${capstone.jwt.access-ttl:PT30M}") Duration accessTtl,
                      @Value("${capstone.jwt.refresh-ttl:P14D}") Duration refreshTtl,
                      @Value("${capstone.jwt.refresh-reuse-grace:PT30S}") Duration reuseGrace,
                      @Value("${capstone.jwt.refresh-cookie-secure:true}") boolean secureCookie) {
        if (accessTtl.compareTo(refreshTtl) >= 0)
            throw new IllegalArgumentException("JWT_ACCESS_TTL must be shorter than JWT_REFRESH_TTL");
        this.jdbc=jdbc; this.encoder=encoder;
        this.accessTtl=accessTtl; this.refreshTtl=refreshTtl; this.reuseGrace=reuseGrace; this.secureCookie=secureCookie;
    }

    /** 로그인과 회원가입: 새 세션(Refresh Token 계열)을 연다. */
    @Transactional
    public Issued open(String userId) {
        jdbc.update("DELETE FROM app.sessions WHERE expires_at<=now()");
        UUID session=UUID.randomUUID(); Instant expiry=Instant.now().plus(refreshTtl);
        jdbc.update("INSERT INTO app.sessions (id,user_id,expires_at) VALUES (?,CAST(? AS uuid),?)",
            session,userId,Timestamp.from(expiry));
        return issue(session,userId,expiry);
    }

    /** 재발급: 제시된 Refresh Token을 사용 처리하고 같은 세션에 새 토큰을 발급한다. */
    @Transactional(noRollbackFor=ResponseStatusException.class)
    public Issued rotate(String raw) {
        if (raw==null || raw.isBlank()) throw unauthorized("REFRESH_TOKEN_MISSING");
        var rows=jdbc.queryForList("""
            SELECT r.session_id, r.expires_at, r.used_at, s.user_id FROM app.refresh_tokens r
            JOIN app.sessions s ON s.id=r.session_id WHERE r.token_hash=? FOR UPDATE OF r, s""", hash(raw));
        if (rows.size()!=1) throw unauthorized("INVALID_REFRESH_TOKEN");
        var row=rows.getFirst(); UUID session=(UUID)row.get("session_id");
        if (row.get("used_at") instanceof Timestamp used) {
            // 다른 탭이 방금 회전시킨 경우: 쿠키는 이미 새 토큰으로 바뀌었으니 재시도하면 된다.
            if (used.toInstant().plus(reuseGrace).isAfter(Instant.now())) throw unauthorized("REFRESH_TOKEN_ROTATED");
            // 오래전에 회전된 토큰의 재사용은 탈취로 보고 세션 전체를 폐기한다.
            jdbc.update("DELETE FROM app.sessions WHERE id=?",session);
            throw unauthorized("REFRESH_TOKEN_REUSED");
        }
        if (!((Timestamp)row.get("expires_at")).toInstant().isAfter(Instant.now())) {
            jdbc.update("DELETE FROM app.sessions WHERE id=?",session);
            throw unauthorized("REFRESH_TOKEN_EXPIRED");
        }
        jdbc.update("UPDATE app.refresh_tokens SET used_at=now() WHERE token_hash=?",hash(raw));
        Instant expiry=Instant.now().plus(refreshTtl);
        jdbc.update("UPDATE app.sessions SET expires_at=? WHERE id=?",Timestamp.from(expiry),session);
        return issue(session,row.get("user_id").toString(),expiry);
    }

    /** 로그아웃: Refresh Token 또는 Access JWT의 sid가 가리키는 세션을 지운다. 둘 다 없어도 실패하지 않는다. */
    public void revoke(String rawRefresh, String sessionId) {
        if (rawRefresh!=null && !rawRefresh.isBlank())
            jdbc.update("DELETE FROM app.sessions WHERE id=(SELECT session_id FROM app.refresh_tokens WHERE token_hash=?)",hash(rawRefresh));
        if (sessionId!=null)
            jdbc.update("DELETE FROM app.sessions WHERE id=CAST(? AS uuid)",sessionId);
    }

    public ResponseCookie clearedCookie() { return cookie("",Duration.ZERO); }

    private Issued issue(UUID session, String userId, Instant refreshExpiry) {
        byte[] bytes=new byte[32]; RANDOM.nextBytes(bytes);
        String refresh=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.update("INSERT INTO app.refresh_tokens (token_hash,session_id,expires_at) VALUES (?,?,?)",
            hash(refresh),session,Timestamp.from(refreshExpiry));
        var user=jdbc.queryForMap("SELECT tenant_id,role FROM app.users WHERE id=CAST(? AS uuid)",userId);
        Instant now=Instant.now();
        var claims=JwtClaimsSet.builder().issuer("beauty-capstone").subject(userId).id(UUID.randomUUID().toString())
            .issuedAt(now).expiresAt(now.plus(accessTtl)).claim("sid",session.toString())
            .claim("tenantId",user.get("tenant_id").toString()).claim("role",user.get("role")).build();
        String access=encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims)).getTokenValue();
        return new Issued(userId,access,accessTtl.toSeconds(),cookie(refresh,refreshTtl));
    }

    private ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(COOKIE,value).httpOnly(true).secure(secureCookie).sameSite("Strict")
            .path(COOKIE_PATH).maxAge(maxAge).build();
    }

    private static ResponseStatusException unauthorized(String code) {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED,code);
    }

    static String hash(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
