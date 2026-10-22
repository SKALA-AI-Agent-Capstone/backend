package org.skala.capstone.billing;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class CreditCatalog {
    private final CreditRepository credits;

    public CreditCatalog(CreditRepository credits) {
        this.credits = credits;
    }

    public List<Map<String, Object>> packages() {
        return credits.packages();
    }

    public Integer price(int amount) {
        return credits.price(amount);
    }

    public int reviewCost() {
        return credits.reviewCost();
    }
}
