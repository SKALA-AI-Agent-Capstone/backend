package org.skala.capstone;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.web.server.ResponseStatusException;

/** Reserves credit before analysis and settles it once per review version. */
@Service
public class ReviewCreditService {
    public static final int CREDITS_PER_REVIEW = 100;
    private final JdbcTemplate jdbc;

    public ReviewCreditService(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    @Transactional
    public void reserve(String tenant,String user,String reviewId,int versionNo) {
        int updated=jdbc.update("UPDATE app.users SET credit_balance=credit_balance-? "+
            "WHERE id=CAST(? AS uuid) AND tenant_id=CAST(? AS uuid) AND credit_balance>=?",
            CREDITS_PER_REVIEW,user,tenant,CREDITS_PER_REVIEW);
        if(updated!=1) throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,"INSUFFICIENT_CREDITS");
        jdbc.update("INSERT INTO app.review_credit_charges(review_id,version_no,tenant_id,user_id,credits,status) "+
            "VALUES (CAST(? AS uuid),?,CAST(? AS uuid),CAST(? AS uuid),?,'reserved')",
            reviewId,versionNo,tenant,user,CREDITS_PER_REVIEW);
    }

    @Transactional
    public void charge(String reviewId,int versionNo) {
        int updated=jdbc.update("UPDATE app.review_credit_charges SET status='charged',settled_at=now() "+
            "WHERE review_id=CAST(? AS uuid) AND version_no=? AND status='reserved'",reviewId,versionNo);
        if(updated!=1) throw new IllegalStateException("REVIEW_CREDIT_RESERVATION_MISSING");
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void release(String reviewId,int versionNo) {
        var rows=jdbc.queryForList("UPDATE app.review_credit_charges SET status='released',settled_at=now() "+
            "WHERE review_id=CAST(? AS uuid) AND version_no=? AND status='reserved' "+
            "RETURNING tenant_id,user_id,credits",reviewId,versionNo);
        if(rows.isEmpty()) return;
        Map<String,Object> row=rows.getFirst();
        jdbc.update("UPDATE app.users SET credit_balance=credit_balance+? "+
            "WHERE id=? AND tenant_id=?",row.get("credits"),row.get("user_id"),row.get("tenant_id"));
    }
}
