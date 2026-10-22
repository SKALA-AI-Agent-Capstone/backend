package org.skala.capstone.auth;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import org.skala.capstone.auth.dto.LoginRequest;
import org.skala.capstone.auth.dto.SignupRequest;
import org.skala.capstone.auth.dto.TeamMemberRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthService {
    public record Session(Map<String, Object> body, ResponseCookie cookie) {}

    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final TokenService tokens;
    private final JwtDecoder decoder;

    public AuthService(UserRepository users, PasswordEncoder passwords, TokenService tokens, JwtDecoder decoder) {
        this.users = users;
        this.passwords = passwords;
        this.tokens = tokens;
        this.decoder = decoder;
    }

    static String value(String input, String key, int min, int max) {
        String value = input == null ? "" : input.trim();
        if (value.length() < min || value.length() > max) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_" + key.toUpperCase());
        }
        return value;
    }

    private static String email(String input) {
        String email = value(input, "email", 3, 200).toLowerCase(Locale.ROOT);
        if (!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_EMAIL");
        }
        return email;
    }

    private static String password(String input) {
        // 길이 검사는 유지하되 실제 비밀번호의 공백은 저장과 비교에서 그대로 보존한다.
        value(input, "password", 10, 72);
        if (input.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_PASSWORD");
        }
        return input;
    }

    @Transactional
    public Session signup(SignupRequest input, String previous) {
        String email = email(input.email());
        String password = password(input.password());
        String name = value(input.name(), "name", 1, 80);
        String company = value(input.tenantName(), "tenantName", 1, 120);
        if (users.emailExists(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "EMAIL_EXISTS");
        }
        UUID tenant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        users.createTenant(tenant, company);
        users.createUser(user, tenant.toString(), email, name, passwords.encode(password), "admin");
        tokens.revoke(previous, null);
        return issued(tokens.open(user.toString()));
    }

    public Session login(LoginRequest input, String previous) {
        String email = value(input.email(), "email", 3, 200).toLowerCase(Locale.ROOT);
        var credentials = users.findCredentials(email);
        if (credentials.size() != 1 || !passwords.matches(input.password() == null ? "" : input.password(),
                credentials.getFirst().get("password_hash").toString())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "INVALID_LOGIN");
        }
        tokens.revoke(previous, null);
        return issued(tokens.open(credentials.getFirst().get("id").toString()));
    }

    public Session refresh(String refreshToken) {
        return issued(tokens.rotate(refreshToken));
    }

    private Session issued(TokenService.Issued issued) {
        var body = new LinkedHashMap<>(identity(issued.userId()));
        body.put("accessToken", issued.accessToken());
        body.put("expiresIn", issued.accessExpiresIn());
        return new Session(body, issued.cookie());
    }

    public Map<String, Object> identity(String id) {
        var row = users.identity(id);
        return Map.of("user", Map.of("id", id, "tenantId", row.get("tenant_id").toString(), "name", row.get("name"),
                "email", row.get("email"), "role", row.get("role")),
            "tenant", Map.of("id", row.get("tenant_id").toString(), "name", row.get("tenant_name")));
    }

    public void logout(String refreshToken, String authorization) {
        String session = null;
        if (authorization != null && authorization.startsWith("Bearer ")) {
            try {
                session = decoder.decode(authorization.substring(7)).getClaimAsString("sid");
            } catch (JwtException ignored) { }
        }
        tokens.revoke(refreshToken, session);
    }

    public ResponseCookie clearedCookie() {
        return tokens.clearedCookie();
    }

    @Transactional
    public void addTeamMember(Jwt jwt, TeamMemberRequest input) {
        if (!"admin".equals(jwt.getClaimAsString("role"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ADMIN_REQUIRED");
        }
        String role = value(input.role(), "role", 1, 20);
        if (!Set.of("author", "reviewer").contains(role)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_ROLE");
        }
        String email = email(input.email());
        if (users.emailExists(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "EMAIL_EXISTS");
        }
        try {
            users.createUser(UUID.randomUUID(), jwt.getClaimAsString("tenantId"), email,
                value(input.name(), "name", 1, 80), passwords.encode(password(input.password())), role);
        } catch (DuplicateKeyException duplicate) {
            // 사전 확인 뒤 다른 요청이 같은 이메일을 먼저 만든 경우도 같은 코드로 응답한다.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "EMAIL_EXISTS");
        }
    }
}
