package org.skala.capstone;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReviewCreditServiceTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final ReviewCreditService credits=new ReviewCreditService(jdbc);
    private static final String TENANT="00000000-0000-0000-0000-000000000001";
    private static final String USER="00000000-0000-0000-0000-000000000002";
    private static final String REVIEW="00000000-0000-0000-0000-000000000003";

    @Test void insufficientBalanceNeverCreatesChargeRecord() {
        when(jdbc.update(startsWith("UPDATE app.users SET credit_balance=credit_balance-"),any(Object[].class))).thenReturn(0);
        var error=assertThrows(ResponseStatusException.class,()->credits.reserve(TENANT,USER,REVIEW,1));
        assertEquals(HttpStatus.PAYMENT_REQUIRED,error.getStatusCode());
        verify(jdbc).update(startsWith("UPDATE app.users SET credit_balance=credit_balance-"),
            eq(100),eq(USER),eq(TENANT),eq(100));
        verify(jdbc,never()).update(startsWith("INSERT INTO app.review_credit_charges"),any(Object[].class));
    }

    @Test void successfulReservationRecordsOneReviewVersion() {
        when(jdbc.update(startsWith("UPDATE app.users SET credit_balance=credit_balance-"),any(Object[].class))).thenReturn(1);
        credits.reserve(TENANT,USER,REVIEW,2);
        verify(jdbc).update(startsWith("INSERT INTO app.review_credit_charges"),eq(REVIEW),eq(2),eq(TENANT),eq(USER),eq(100));
    }

    @Test void releaseRefundsOnlyAReservedCharge() {
        when(jdbc.queryForList(startsWith("UPDATE app.review_credit_charges SET status='released'"),eq(REVIEW),eq(1)))
            .thenReturn(List.of(Map.of("tenant_id",TENANT,"user_id",USER,"credits",100)),List.of());
        credits.release(REVIEW,1);
        credits.release(REVIEW,1);
        verify(jdbc,times(1)).update(startsWith("UPDATE app.users SET credit_balance=credit_balance+"),eq(100),eq(USER),eq(TENANT));
    }
}
