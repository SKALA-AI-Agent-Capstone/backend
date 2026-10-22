package org.skala.capstone.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class TossClient {
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String clientKey;
    private final String secretKey;

    public TossClient(ObjectMapper json, @Value("${payments.toss-client-key:}") String clientKey,
            @Value("${payments.toss-secret-key:}") String secretKey) {
        this.json = json;
        this.clientKey = clientKey;
        this.secretKey = secretKey;
    }

    public boolean ready() {
        return (clientKey.startsWith("test_ck_") && secretKey.startsWith("test_sk_"))
            || (clientKey.startsWith("test_gck_") && secretKey.startsWith("test_gsk_"));
    }

    public String clientKey() {
        return clientKey;
    }

    public String mode() {
        return clientKey.startsWith("test_gck_") ? "window" : "legacy";
    }

    public JsonNode confirm(UUID orderId, String paymentKey, int amount) {
        String authorization = "Basic " + Base64.getEncoder().encodeToString((secretKey + ":").getBytes(StandardCharsets.UTF_8));
        try {
            var request = HttpRequest.newBuilder(URI.create("https://api.tosspayments.com/v1/payments/confirm"))
                .timeout(Duration.ofSeconds(15)).header("Authorization", authorization).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(
                    Map.of("paymentKey", paymentKey, "orderId", orderId.toString(), "amount", amount)))).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "PAYMENT_PROVIDER_REJECTED");
            }
            return json.readTree(response.body());
        } catch (ResponseStatusException error) {
            throw error;
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "PAYMENT_PROVIDER_UNAVAILABLE");
        }
    }
}
