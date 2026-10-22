package org.skala.capstone.billing;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CreditRepository {
    private final JdbcTemplate jdbc;

    public CreditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Integer balance(String user, String tenant) {
        return jdbc.queryForObject("SELECT credit_balance FROM app.users WHERE id=CAST(? AS uuid) AND tenant_id=CAST(? AS uuid)",
            Integer.class, user, tenant);
    }

    public List<Map<String, Object>> reviewCharge(UUID review, int version, String tenant, String user) {
        return jdbc.queryForList("SELECT credits,status FROM app.review_credit_charges "
            + "WHERE review_id=? AND version_no=? AND tenant_id=CAST(? AS uuid) AND user_id=CAST(? AS uuid)",
            review, version, tenant, user);
    }

    public List<Map<String, Object>> packages() {
        return jdbc.queryForList("SELECT credits, amount_krw AS price, discount_percent AS \"discountPercent\" "
            + "FROM app.credit_packages WHERE active=true ORDER BY display_order");
    }

    public Integer price(int credits) {
        var rows = jdbc.queryForList("SELECT amount_krw FROM app.credit_packages WHERE credits=? AND active=true", Integer.class, credits);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public int reviewCost() {
        return jdbc.queryForObject("SELECT review_cost FROM app.credit_policy WHERE id=true", Integer.class);
    }

    public void createOrder(UUID id, String tenant, String user, int credits, int amount) {
        jdbc.update("INSERT INTO app.credit_orders(id,tenant_id,user_id,provider,credits,amount_krw,status) "
            + "VALUES (?,CAST(? AS uuid),CAST(? AS uuid),?,?,?,'pending')", id, tenant, user, "toss", credits, amount);
    }

    public List<Map<String, Object>> lockOrder(UUID id, String tenant, String user) {
        return jdbc.queryForList("SELECT status,amount_krw,credits,provider_ref FROM app.credit_orders "
            + "WHERE id=? AND tenant_id=CAST(? AS uuid) AND user_id=CAST(? AS uuid) AND provider=? FOR UPDATE", id, tenant, user, "toss");
    }

    public void recordTopup(UUID order, String tenant, String user, int credits) {
        jdbc.update("INSERT INTO app.credit_topups(order_id,tenant_id,user_id,credits) VALUES (?,CAST(? AS uuid),CAST(? AS uuid),?)",
            order, tenant, user, credits);
    }

    public void creditBalance(String user, String tenant, int credits) {
        jdbc.update("UPDATE app.users SET credit_balance=credit_balance+? WHERE id=CAST(? AS uuid) AND tenant_id=CAST(? AS uuid)",
            credits, user, tenant);
    }

    public void markOrderPaid(UUID id, String providerRef) {
        jdbc.update("UPDATE app.credit_orders SET status='paid',provider_ref=?,paid_at=now() WHERE id=?", providerRef, id);
    }

    public boolean reserveBalance(String tenant, String user, int cost) {
        return jdbc.update("UPDATE app.users SET credit_balance=credit_balance-? "
            + "WHERE id=CAST(? AS uuid) AND tenant_id=CAST(? AS uuid) AND credit_balance>=?", cost, user, tenant, cost) == 1;
    }

    public void recordReservation(String tenant, String user, String review, int version, int cost) {
        jdbc.update("INSERT INTO app.review_credit_charges(review_id,version_no,tenant_id,user_id,credits,status) "
            + "VALUES (CAST(? AS uuid),?,CAST(? AS uuid),CAST(? AS uuid),?,'reserved')", review, version, tenant, user, cost);
    }

    public boolean charge(String review, int version) {
        return jdbc.update("UPDATE app.review_credit_charges SET status='charged',settled_at=now() "
            + "WHERE review_id=CAST(? AS uuid) AND version_no=? AND status='reserved'", review, version) == 1;
    }

    public List<Map<String, Object>> releaseReservation(String review, int version) {
        return jdbc.queryForList("UPDATE app.review_credit_charges SET status='released',settled_at=now() "
            + "WHERE review_id=CAST(? AS uuid) AND version_no=? AND status='reserved' RETURNING tenant_id,user_id,credits", review, version);
    }

    public void refundBalance(Map<String, Object> reservation) {
        jdbc.update("UPDATE app.users SET credit_balance=credit_balance+? WHERE id=? AND tenant_id=?",
            reservation.get("credits"), reservation.get("user_id"), reservation.get("tenant_id"));
    }
    public String status(String reviewId,int version) {
        return jdbc.queryForObject("SELECT status FROM app.review_credit_charges WHERE review_id=CAST(? AS uuid) AND version_no=?",
            String.class,reviewId,version);
    }
}
