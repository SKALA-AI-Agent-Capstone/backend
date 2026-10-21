package org.skala.capstone;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class CreditCatalog {
    private final JdbcTemplate jdbc;

    public CreditCatalog(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public List<Map<String,Object>> packages() {
        return jdbc.queryForList("SELECT credits, amount_krw AS price, discount_percent AS \"discountPercent\" " +
            "FROM app.credit_packages WHERE active=true ORDER BY display_order");
    }

    public Integer price(int credits) {
        var prices=jdbc.queryForList("SELECT amount_krw FROM app.credit_packages WHERE credits=? AND active=true",Integer.class,credits);
        return prices.isEmpty()?null:prices.getFirst();
    }

    public int reviewCost() {
        return jdbc.queryForObject("SELECT review_cost FROM app.credit_policy WHERE id=true",Integer.class);
    }
}
