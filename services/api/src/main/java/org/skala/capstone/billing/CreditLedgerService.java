package org.skala.capstone.billing;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CreditLedgerService {
    private final CreditRepository credits;
    private final CreditCatalog catalog;

    public CreditLedgerService(CreditRepository credits, CreditCatalog catalog) {
        this.credits = credits;
        this.catalog = catalog;
    }

    @Transactional
    public void reserve(String tenant, String user, String review, int version) {
        int cost = catalog.reviewCost();
        if (!credits.reserveBalance(tenant, user, cost)) {
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "INSUFFICIENT_CREDITS");
        }
        credits.recordReservation(tenant, user, review, version, cost);
    }

    @Transactional
    public void charge(String review, int version) {
        if (!credits.charge(review, version)) {
            throw new IllegalStateException("REVIEW_CREDIT_RESERVATION_MISSING");
        }
    }

    @Transactional
    public void settle(String review, int version, boolean analysisComplete) {
        if (analysisComplete) {
            charge(review, version);
        } else {
            refundReservation(review, version);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(String review, int version) {
        refundReservation(review, version);
    }

    private void refundReservation(String review, int version) {
        var reservations = credits.releaseReservation(review, version);
        if (!reservations.isEmpty()) {
            credits.refundBalance(reservations.getFirst());
        }
    }
}
