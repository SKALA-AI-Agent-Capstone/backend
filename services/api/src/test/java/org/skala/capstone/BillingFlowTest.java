package org.skala.capstone;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.skala.capstone.billing.BillingService;
import org.skala.capstone.billing.CreditRepository;
import org.skala.capstone.billing.TossClient;
import org.skala.capstone.billing.dto.OrderRequest;
import org.skala.capstone.billing.dto.TossConfirmRequest;
import org.skala.capstone.common.AgentClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers(disabledWithoutDocker=true)
@TestPropertySource(properties={
    "capstone.internal-token=unit-test-only-token-not-for-production-12345",
    "capstone.agent-base-url=http://127.0.0.1:1",
    "capstone.jwt-secret=unit-test-only-jwt-secret-not-for-production-12345"
})
class BillingFlowTest {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:17-alpine");
    @Autowired BillingService billing;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @MockitoBean TossClient toss;
    @MockitoBean AgentClient agent;
    @MockitoBean StringRedisTemplate redis;
    @MockitoSpyBean CreditRepository credits;
    private Jwt user;

    @BeforeEach void seed() {
        UUID tenant=UUID.randomUUID(), id=UUID.randomUUID();
        jdbc.update("INSERT INTO app.tenants VALUES (?,?)",tenant,"billing test");
        jdbc.update("INSERT INTO app.users(id,tenant_id,email,name,password_hash,role,credit_balance) VALUES (?,?,?,'test','hash','admin',500)",
            id,tenant,id+"@example.test");
        user=Jwt.withTokenValue("test").header("alg","none").subject(id.toString()).claim("tenantId",tenant.toString()).build();
        when(toss.ready()).thenReturn(true);
        when(toss.clientKey()).thenReturn("test_ck_example");
        when(toss.mode()).thenReturn("legacy");
    }

    private UUID order() {
        return UUID.fromString(billing.order(user,new OrderRequest(100,"toss")).get("orderId").toString());
    }

    private void approved(UUID order) {
        when(toss.confirm(order,"test-payment-key",5000)).thenReturn(json.createObjectNode()
            .put("status","DONE").put("orderId",order.toString()).put("totalAmount",5000));
    }

    private int topups(UUID order) {
        return jdbc.queryForObject("SELECT count(*) FROM app.credit_topups WHERE order_id=?",Integer.class,order);
    }

    @Test void concurrentConfirmationsCreditTheOrderExactlyOnce() throws Exception {
        UUID order=order();
        approved(order);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var first=executor.submit(()->billing.confirmToss(user,order,new TossConfirmRequest("test-payment-key",5000)));
            var second=executor.submit(()->billing.confirmToss(user,order,new TossConfirmRequest("test-payment-key",5000)));
            assertEquals(600,first.get(10,TimeUnit.SECONDS).get("balance"));
            assertEquals(600,second.get(10,TimeUnit.SECONDS).get("balance"));
        }
        assertEquals(1,topups(order));
        verify(toss,times(1)).confirm(order,"test-payment-key",5000);
    }

    @Test void anotherTenantCannotConfirmTheOrder() {
        UUID order=order();
        Jwt outsider=Jwt.withTokenValue("test").header("alg","none").subject(user.getSubject())
            .claim("tenantId",UUID.randomUUID().toString()).build();
        assertEquals(404,assertThrows(ResponseStatusException.class,
            ()->billing.confirmToss(outsider,order,new TossConfirmRequest("test-payment-key",5000))).getStatusCode().value());
        verify(toss,never()).confirm(any(),anyString(),anyInt());
        assertEquals(0,topups(order));
    }

    @Test void invalidProviderResponseNeverCreatesCredit() {
        UUID order=order();
        when(toss.confirm(order,"test-payment-key",5000)).thenReturn(json.createObjectNode().put("status","WAITING_FOR_DEPOSIT"));
        assertEquals(502,assertThrows(ResponseStatusException.class,
            ()->billing.confirmToss(user,order,new TossConfirmRequest("test-payment-key",5000))).getStatusCode().value());
        assertEquals(0,topups(order));
        assertEquals(500,billing.balance(user).get("balance"));
    }

    @Test void localWriteFailureRollsBackTopupAndBalanceTogether() {
        UUID order=order();
        approved(order);
        doThrow(new IllegalStateException("synthetic database outage")).when(credits).markOrderPaid(eq(order),anyString());
        assertThrows(IllegalStateException.class,()->billing.confirmToss(user,order,new TossConfirmRequest("test-payment-key",5000)));
        assertEquals(0,topups(order));
        assertEquals(500,billing.balance(user).get("balance"));
        assertEquals("pending",jdbc.queryForObject("SELECT status FROM app.credit_orders WHERE id=?",String.class,order));
    }
}
