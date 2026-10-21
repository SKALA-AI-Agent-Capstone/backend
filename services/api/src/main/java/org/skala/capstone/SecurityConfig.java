package org.skala.capstone;

import jakarta.servlet.DispatcherType;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    /** 토큰 없이 호출하는 인증 경로. 만료된 Bearer가 함께 와도 401로 막지 않도록 헤더를 읽지 않는다. */
    static final Set<String> PUBLIC_AUTH = Set.of(
        "/api/v1/users/signup", "/api/v1/users/login", "/api/v1/users/refresh", "/api/v1/users/logout");

    @Bean PasswordEncoder passwords() { return new BCryptPasswordEncoder(); }

    @Bean SecretKeySpec jwtKey(@Value("${capstone.jwt-secret}") String secret) {
        if (secret.length() < 32 || secret.contains("__GENERATE_"))
            throw new IllegalArgumentException("Configure JWT_SECRET with at least 32 characters");
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean JwtEncoder jwtEncoder(SecretKeySpec key) {
        return new NimbusJwtEncoder(new com.nimbusds.jose.jwk.source.ImmutableSecret<>(key));
    }

    @Bean JwtDecoder jwtDecoder(SecretKeySpec key, JdbcTemplate jdbc) {
        var decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
            // 발급과 검증을 같은 서버가 하므로 기본 60초 clock skew 없이 만료 시각을 그대로 적용한다.
            new JwtTimestampValidator(Duration.ZERO),
            new JwtIssuerValidator("beauty-capstone"),
            jwt -> {
                try {
                    Integer count = jdbc.queryForObject(
                        "SELECT count(*) FROM app.sessions WHERE id=CAST(? AS uuid) AND expires_at>now()",
                        Integer.class, jwt.getClaimAsString("sid"));
                    if (count != null && count == 1) return OAuth2TokenValidatorResult.success();
                } catch (Exception ignored) { }
                return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
            }
        ));
        return decoder;
    }

    @Bean @Order(1)
    SecurityFilterChain internal(HttpSecurity http) throws Exception {
        return http.securityMatcher("/internal/**", "/actuator/**", "/api/dev/**", "/api/reviews")
            .csrf(c -> c.disable()).authorizeHttpRequests(a -> a.anyRequest().permitAll())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS)).build();
    }

    @Bean @Order(2)
    SecurityFilterChain application(HttpSecurity http) throws Exception {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> List.of(
            new SimpleGrantedAuthority("ROLE_" + jwt.getClaimAsString("role").toUpperCase())));
        return http.csrf(c -> c.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                // SSE streams finish on an async dispatch. The request was already authorized when it started.
                .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                .requestMatchers(PUBLIC_AUTH.toArray(String[]::new)).permitAll()
                .anyRequest().authenticated())
            .oauth2ResourceServer(o -> o.bearerTokenResolver(bearerTokens())
                .jwt(j -> j.jwtAuthenticationConverter(converter))).build();
    }

    private static BearerTokenResolver bearerTokens() {
        var resolver = new DefaultBearerTokenResolver();
        return request -> PUBLIC_AUTH.contains(request.getRequestURI()) ? null : resolver.resolve(request);
    }
}
