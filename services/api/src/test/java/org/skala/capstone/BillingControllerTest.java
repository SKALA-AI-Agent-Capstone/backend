package org.skala.capstone;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BillingControllerTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final CreditCatalog catalog=mock(CreditCatalog.class);
    private final Jwt jwt=mock(Jwt.class);

    @Test void balanceReadsTheAuthenticatedMember() {
        when(jwt.getSubject()).thenReturn("00000000-0000-0000-0000-000000000002");
        when(jwt.getClaimAsString("tenantId")).thenReturn("00000000-0000-0000-0000-000000000001");
        when(jdbc.queryForObject(startsWith("SELECT credit_balance FROM app.users"), eq(Integer.class), any(Object[].class)))
            .thenReturn(100);
        var billing=new BillingController(jdbc,catalog,new ObjectMapper(),"http://127.0.0.1:5173/credits",
            "test_gck_example","test_gsk_example");
        assertEquals(100,billing.balance(jwt).get("balance"));
        verify(jdbc).queryForObject(startsWith("SELECT credit_balance FROM app.users"),eq(Integer.class),
            eq("00000000-0000-0000-0000-000000000002"),eq("00000000-0000-0000-0000-000000000001"));
    }

    @Test void orderPriceComesFromServerCatalog() {
        when(jwt.getClaimAsString("tenantId")).thenReturn("00000000-0000-0000-0000-000000000001");
        when(jwt.getSubject()).thenReturn("00000000-0000-0000-0000-000000000002");
        when(catalog.price(2000)).thenReturn(80000);
        when(catalog.price(100)).thenReturn(5000);
        when(catalog.price(200)).thenReturn(9500);
        var billing=new BillingController(jdbc,catalog,new ObjectMapper(),"http://127.0.0.1:5173/credits",
            "test_ck_example","test_sk_example");
        var result=billing.order(jwt,new BillingController.OrderInput(2000,"toss"));
        assertEquals(80000,result.get("amount"));
        assertEquals(2000,result.get("credits"));
        assertEquals(5000,billing.order(jwt,new BillingController.OrderInput(100,"toss")).get("amount"));
        assertEquals(9500,billing.order(jwt,new BillingController.OrderInput(200,"toss")).get("amount"));
        verify(jdbc,times(3)).update(startsWith("INSERT INTO app.credit_orders"),any(Object[].class));
    }

    @Test void widgetKeysUsePaymentWindowAndMixedKeysAreRejected() {
        when(catalog.packages()).thenReturn(java.util.List.of());
        when(catalog.reviewCost()).thenReturn(100);
        when(jwt.getClaimAsString("tenantId")).thenReturn("00000000-0000-0000-0000-000000000001");
        when(jwt.getSubject()).thenReturn("00000000-0000-0000-0000-000000000002");
        when(catalog.price(100)).thenReturn(5000);
        var widget=new BillingController(jdbc,catalog,new ObjectMapper(),"http://127.0.0.1:5173/credits",
            "test_gck_example","test_gsk_example");
        assertEquals(true,widget.config().get("tossEnabled"));
        assertEquals("window",widget.order(jwt,new BillingController.OrderInput(100,"toss")).get("tossMode"));
        var mixed=new BillingController(jdbc,catalog,new ObjectMapper(),"http://127.0.0.1:5173/credits",
            "test_gck_example","test_sk_example");
        assertEquals(false,mixed.config().get("tossEnabled"));
    }

    @Test void missingKeysCannotCreateChargeOrder() {
        when(catalog.price(100)).thenReturn(5000);
        var billing=new BillingController(jdbc,catalog,new ObjectMapper(),"http://127.0.0.1:5173/credits","","");
        var error=assertThrows(ResponseStatusException.class,
            () -> billing.order(jwt,new BillingController.OrderInput(100,"toss")));
        assertEquals(503,error.getStatusCode().value());
        verifyNoInteractions(jdbc);
    }

    @Test void cardAndKakaoOrdersAreRejected() {
        var billing=new BillingController(jdbc,catalog,new ObjectMapper(),"http://127.0.0.1:5173/credits",
            "test_gck_example","test_gsk_example");
        for (String method : new String[] {"card", "kakao"}) {
            var error=assertThrows(ResponseStatusException.class,
                () -> billing.order(jwt,new BillingController.OrderInput(100,method)));
            assertEquals(400,error.getStatusCode().value());
        }
        verifyNoInteractions(jdbc);
    }

    @Test void unsupportedPackageIsRejectedBeforeDatabaseWrite() {
        when(catalog.price(500)).thenReturn(null);
        var billing=new BillingController(jdbc,catalog,new ObjectMapper(),"http://127.0.0.1:5173/credits",
            "test_ck_example","test_sk_example");
        var error=assertThrows(ResponseStatusException.class,
            () -> billing.order(jwt,new BillingController.OrderInput(500,"toss")));
        assertEquals(400,error.getStatusCode().value());
        verifyNoInteractions(jdbc);
    }
}
