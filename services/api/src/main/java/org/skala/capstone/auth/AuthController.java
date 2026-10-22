package org.skala.capstone.auth;

import java.util.Map;
import org.skala.capstone.auth.dto.LoginRequest;
import org.skala.capstone.auth.dto.SignupRequest;
import org.skala.capstone.auth.dto.TeamMemberRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/users")
public class AuthController {
    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/signup")
    public ResponseEntity<Map<String, Object>> signup(@RequestBody SignupRequest input,
            @CookieValue(name = TokenService.COOKIE, required = false) String previous) {
        return issued(auth.signup(input, previous));
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody LoginRequest input,
            @CookieValue(name = TokenService.COOKIE, required = false) String previous) {
        return issued(auth.login(input, previous));
    }

    @PostMapping("/refresh")
    public ResponseEntity<Map<String, Object>> refresh(
            @CookieValue(name = TokenService.COOKIE, required = false) String refreshToken) {
        try {
            return issued(auth.refresh(refreshToken));
        } catch (ResponseStatusException error) {
            var response = ResponseEntity.status(error.getStatusCode());
            if (!"REFRESH_TOKEN_ROTATED".equals(error.getReason())) {
                response.header(HttpHeaders.SET_COOKIE, auth.clearedCookie().toString());
            }
            return response.body(Map.of("message", error.getReason()));
        }
    }

    private ResponseEntity<Map<String, Object>> issued(AuthService.Session session) {
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, session.cookie().toString()).body(session.body());
    }

    @GetMapping("/me")
    public Map<String, Object> me(@AuthenticationPrincipal Jwt jwt) {
        return auth.identity(jwt.getSubject());
    }

    @DeleteMapping("/logout")
    public ResponseEntity<Map<String, Boolean>> logout(
            @CookieValue(name = TokenService.COOKIE, required = false) String refreshToken,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        auth.logout(refreshToken, authorization);
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, auth.clearedCookie().toString()).body(Map.of("ok", true));
    }

    @PostMapping("/team")
    public Map<String, Boolean> team(@AuthenticationPrincipal Jwt jwt, @RequestBody TeamMemberRequest input) {
        auth.addTeamMember(jwt, input);
        return Map.of("ok", true);
    }
}
