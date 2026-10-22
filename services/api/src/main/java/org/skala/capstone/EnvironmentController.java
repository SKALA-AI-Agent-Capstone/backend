package org.skala.capstone;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class EnvironmentController {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final String agentBaseUrl;
    private final String token;
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2)).build();

    public EnvironmentController(
        JdbcTemplate jdbc, ObjectMapper mapper,
        @Value("${capstone.agent-base-url}") String agentBaseUrl,
        @Value("${capstone.internal-token}") String token
    ) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.agentBaseUrl = agentBaseUrl;
        this.token = token;
    }

    @GetMapping("/internal/bootstrap")
    public Map<String, Object> bootstrap() {
        jdbc.queryForObject("SELECT 1", Integer.class);
        return Map.of(
            "service", "api", "status", "ok",
            "contract_version", "0.1.0", "mode", "development-scaffold"
        );
    }

    @GetMapping("/api/dev/status")
    public Map<String, Object> status() {
        Map<String, Object> dependencies = new LinkedHashMap<>();
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            dependencies.put("application_database", Map.of("status", "ok"));
        } catch (Exception ignored) {
            dependencies.put("application_database", Map.of("status", "down"));
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(
                URI.create(agentBaseUrl + "/internal/environment")
            ).timeout(Duration.ofSeconds(8))
                .header("Authorization", "Bearer " + token).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Agent unavailable");
            }
            Map<String, Object> agent = mapper.readValue(response.body(), new TypeReference<>() {});
            dependencies.put("agent", Map.of("status", "ok"));
            dependencies.put("agent_environment", agent);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            dependencies.put("agent", Map.of("status", "down"));
        } catch (Exception ignored) {
            dependencies.put("agent", Map.of("status", "down"));
        }
        return Map.of(
            "service", "api", "contract_version", "0.1.0",
            "mode", "development-scaffold", "dependencies", dependencies
        );
    }
}
