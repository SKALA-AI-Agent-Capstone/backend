package org.skala.capstone.billing;

import java.util.Map;
import java.util.UUID;
import org.skala.capstone.billing.dto.OrderRequest;
import org.skala.capstone.billing.dto.TossConfirmRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/billing")
public class BillingController {
    private final BillingService billing;

    public BillingController(BillingService billing) {
        this.billing = billing;
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        return billing.config();
    }

    @GetMapping("/balance")
    public Map<String, Integer> balance(@AuthenticationPrincipal Jwt jwt) {
        return billing.balance(jwt);
    }

    @GetMapping("/reviews/{reviewId}/charge")
    public Map<String, Object> reviewCharge(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID reviewId,
            @RequestParam int versionNo) {
        return billing.reviewCharge(jwt, reviewId, versionNo);
    }

    @PostMapping("/orders")
    public Map<String, Object> order(@AuthenticationPrincipal Jwt jwt, @RequestBody OrderRequest input) {
        return billing.order(jwt, input);
    }

    @PostMapping("/orders/{id}/confirm-toss")
    public Map<String, Integer> confirmToss(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestBody TossConfirmRequest input) {
        return billing.confirmToss(jwt, id, input);
    }
}
