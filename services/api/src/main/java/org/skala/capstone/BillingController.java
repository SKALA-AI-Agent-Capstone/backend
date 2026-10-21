package org.skala.capstone;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController @RequestMapping("/api/v1/billing")
public class BillingController {
    private final JdbcTemplate jdbc;
    private final CreditCatalog catalog;
    private final ObjectMapper json;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String returnUrl,tossClientKey,tossSecretKey;
    public BillingController(JdbcTemplate jdbc,CreditCatalog catalog,ObjectMapper json,@Value("${payments.return-url}") String returnUrl,
        @Value("${payments.toss-client-key:}") String tossClientKey,@Value("${payments.toss-secret-key:}") String tossSecretKey) {
        this.jdbc=jdbc;this.catalog=catalog;this.json=json;this.returnUrl=returnUrl;this.tossClientKey=tossClientKey;
        this.tossSecretKey=tossSecretKey;
    }
    @GetMapping("/config") public Map<String,Object> config() {
        return Map.of("tossEnabled",tossReady(),"tossClientKey",tossReady()?tossClientKey:"",
            "tossMode",tossClientKey.startsWith("test_gck_")?"window":"legacy",
            "packages",catalog.packages(),"reviewCost",catalog.reviewCost());
    }
    @GetMapping("/balance") public Map<String,Integer> balance(@AuthenticationPrincipal Jwt jwt) {
        Integer amount=jdbc.queryForObject("SELECT credit_balance FROM app.users WHERE id=CAST(? AS uuid) AND tenant_id=CAST(? AS uuid)",Integer.class,jwt.getSubject(),jwt.getClaimAsString("tenantId"));
        return Map.of("balance",amount==null?0:amount);
    }
    @GetMapping("/reviews/{reviewId}/charge") public Map<String,Object> reviewCharge(@AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID reviewId,@RequestParam int versionNo) {
        var rows=jdbc.queryForList("SELECT credits,status FROM app.review_credit_charges " +
            "WHERE review_id=? AND version_no=? AND tenant_id=CAST(? AS uuid) AND user_id=CAST(? AS uuid)",
            reviewId,versionNo,jwt.getClaimAsString("tenantId"),jwt.getSubject());
        return rows.isEmpty()?Map.of("charged",false):Map.of("charged",true,"credits",rows.getFirst().get("credits"),"status",rows.getFirst().get("status"));
    }
    @PostMapping("/orders") public Map<String,Object> order(@AuthenticationPrincipal Jwt jwt,@RequestBody OrderInput input) {
        if(!"toss".equals(input.method())) throw error(HttpStatus.BAD_REQUEST,"INVALID_PACKAGE_OR_METHOD");
        Integer amount=catalog.price(input.credits());
        if(amount==null) throw error(HttpStatus.BAD_REQUEST,"INVALID_PACKAGE_OR_METHOD");
        if(!tossReady()) throw error(HttpStatus.SERVICE_UNAVAILABLE,"PAYMENT_TEST_KEYS_NOT_CONFIGURED");
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO app.credit_orders(id,tenant_id,user_id,provider,credits,amount_krw,status) VALUES (?,CAST(? AS uuid),CAST(? AS uuid),?,?,?,'pending')",
            id,jwt.getClaimAsString("tenantId"),jwt.getSubject(),"toss",input.credits(),amount);
        return Map.of("orderId",id.toString(),"amount",amount,"credits",input.credits(),"clientKey",tossClientKey,"returnUrl",returnUrl,"tossMode",tossClientKey.startsWith("test_gck_")?"window":"legacy");
    }
    @PostMapping("/orders/{id}/confirm-toss") @Transactional
    public Map<String,Integer> confirmToss(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@RequestBody TossConfirmation input) {
        var row=locked(jwt,id,"toss");
        if("paid".equals(row.get("status"))) return balance(jwt);
        int amount=((Number)row.get("amount_krw")).intValue();
        if(input.amount()!=amount||input.paymentKey()==null||input.paymentKey().isBlank()) throw error(HttpStatus.BAD_REQUEST,"PAYMENT_AMOUNT_MISMATCH");
        String authorization="Basic "+Base64.getEncoder().encodeToString((tossSecretKey+":").getBytes(StandardCharsets.UTF_8));
        JsonNode result=post("https://api.tosspayments.com/v1/payments/confirm",authorization,
            Map.of("paymentKey",input.paymentKey(),"orderId",id.toString(),"amount",amount));
        if(!"DONE".equals(result.path("status").asText())||!id.toString().equals(result.path("orderId").asText())||result.path("totalAmount").asInt(-1)!=amount)
            throw error(HttpStatus.BAD_GATEWAY,"TOSS_CONFIRM_INVALID_RESPONSE");
        credit(jwt,id,((Number)row.get("credits")).intValue(),input.paymentKey());
        return balance(jwt);
    }
    private Map<String,Object> locked(Jwt jwt,UUID id,String provider) {
        var rows=jdbc.queryForList("SELECT status,amount_krw,credits,provider_ref FROM app.credit_orders WHERE id=? AND tenant_id=CAST(? AS uuid) AND user_id=CAST(? AS uuid) AND provider=? FOR UPDATE",
            id,jwt.getClaimAsString("tenantId"),jwt.getSubject(),provider);
        if(rows.isEmpty()) throw error(HttpStatus.NOT_FOUND,"ORDER_NOT_FOUND");
        return rows.getFirst();
    }
    private void credit(Jwt jwt,UUID id,int credits,String providerRef) {
        jdbc.update("INSERT INTO app.credit_topups(order_id,tenant_id,user_id,credits) VALUES (?,CAST(? AS uuid),CAST(? AS uuid),?)",id,jwt.getClaimAsString("tenantId"),jwt.getSubject(),credits);
        jdbc.update("UPDATE app.users SET credit_balance=credit_balance+? WHERE id=CAST(? AS uuid) AND tenant_id=CAST(? AS uuid)",credits,jwt.getSubject(),jwt.getClaimAsString("tenantId"));
        jdbc.update("UPDATE app.credit_orders SET status='paid',provider_ref=?,paid_at=now() WHERE id=?",providerRef,id);
    }
    private JsonNode post(String url,String authorization,Object body) {
        try {
            var request=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).header("Authorization",authorization)
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()<200||response.statusCode()>=300) throw error(HttpStatus.BAD_GATEWAY,"PAYMENT_PROVIDER_REJECTED");
            return json.readTree(response.body());
        } catch(ResponseStatusException e) {throw e;} catch(Exception e) {throw error(HttpStatus.BAD_GATEWAY,"PAYMENT_PROVIDER_UNAVAILABLE");}
    }
    private boolean tossReady(){return (tossClientKey.startsWith("test_ck_")&&tossSecretKey.startsWith("test_sk_"))||(tossClientKey.startsWith("test_gck_")&&tossSecretKey.startsWith("test_gsk_"));}
    private static ResponseStatusException error(HttpStatus status,String message){return new ResponseStatusException(status,message);}
    public record OrderInput(int credits,String method) {}
    public record TossConfirmation(String paymentKey,int amount) {}
}
