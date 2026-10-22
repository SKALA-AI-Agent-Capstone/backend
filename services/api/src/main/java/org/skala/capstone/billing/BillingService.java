package org.skala.capstone.billing;

import java.util.Map;
import java.util.UUID;
import org.skala.capstone.billing.dto.OrderRequest;
import org.skala.capstone.billing.dto.TossConfirmRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BillingService {
    private final CreditRepository credits;
    private final CreditCatalog catalog;
    private final TossClient toss;
    private final String returnUrl;

    public BillingService(CreditRepository credits, CreditCatalog catalog, TossClient toss,
            @Value("${payments.return-url}") String returnUrl) {
        this.credits = credits;
        this.catalog = catalog;
        this.toss = toss;
        this.returnUrl = returnUrl;
    }

    public Map<String, Object> config() {
        return Map.of("tossEnabled", toss.ready(), "tossClientKey", toss.ready() ? toss.clientKey() : "", "tossMode", toss.mode(),
            "packages", catalog.packages(), "reviewCost", catalog.reviewCost());
    }

    public Map<String, Integer> balance(Jwt jwt) {
        Integer balance = credits.balance(jwt.getSubject(), jwt.getClaimAsString("tenantId"));
        return Map.of("balance", balance == null ? 0 : balance);
    }

    public Map<String, Object> reviewCharge(Jwt jwt, UUID review, int version) {
        var rows = credits.reviewCharge(review, version, jwt.getClaimAsString("tenantId"), jwt.getSubject());
        return rows.isEmpty() ? Map.of("charged", false)
            : Map.of("charged", true, "credits", rows.getFirst().get("credits"), "status", rows.getFirst().get("status"));
    }

    public Map<String, Object> order(Jwt jwt, OrderRequest input) {
        if (!"toss".equals(input.method())) {
            throw error(HttpStatus.BAD_REQUEST, "INVALID_PACKAGE_OR_METHOD");
        }
        Integer amount = catalog.price(input.credits());
        if (amount == null) {
            throw error(HttpStatus.BAD_REQUEST, "INVALID_PACKAGE_OR_METHOD");
        }
        if (!toss.ready()) {
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENT_TEST_KEYS_NOT_CONFIGURED");
        }
        UUID id = UUID.randomUUID();
        credits.createOrder(id, jwt.getClaimAsString("tenantId"), jwt.getSubject(), input.credits(), amount);
        return Map.of("orderId", id.toString(), "amount", amount, "credits", input.credits(), "clientKey", toss.clientKey(),
            "returnUrl", returnUrl, "tossMode", toss.mode());
    }

    @Transactional
    public Map<String, Integer> confirmToss(Jwt jwt, UUID id, TossConfirmRequest input) {
        String tenant = jwt.getClaimAsString("tenantId");
        var rows = credits.lockOrder(id, tenant, jwt.getSubject());
        if (rows.isEmpty()) {
            throw error(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND");
        }
        var row = rows.getFirst();
        if ("paid".equals(row.get("status"))) {
            return balance(jwt);
        }
        int amount = ((Number) row.get("amount_krw")).intValue();
        if (input.amount() != amount || input.paymentKey() == null || input.paymentKey().isBlank()) {
            throw error(HttpStatus.BAD_REQUEST, "PAYMENT_AMOUNT_MISMATCH");
        }
        var result = toss.confirm(id, input.paymentKey(), amount);
        if (!"DONE".equals(result.path("status").asText()) || !id.toString().equals(result.path("orderId").asText())
                || result.path("totalAmount").asInt(-1) != amount) {
            throw error(HttpStatus.BAD_GATEWAY, "TOSS_CONFIRM_INVALID_RESPONSE");
        }
        int amountToCredit = ((Number) row.get("credits")).intValue();
        credits.recordTopup(id, tenant, jwt.getSubject(), amountToCredit);
        credits.creditBalance(jwt.getSubject(), tenant, amountToCredit);
        credits.markOrderPaid(id, input.paymentKey());
        return balance(jwt);
    }

    private static ResponseStatusException error(HttpStatus status, String message) {
        return new ResponseStatusException(status, message);
    }
}
