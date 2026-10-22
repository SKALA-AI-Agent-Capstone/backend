package org.skala.capstone.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import org.skala.capstone.billing.CreditLedgerService;
import org.skala.capstone.review.ReviewFailureRepository.Failure;
import org.skala.capstone.review.events.ReviewEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ReviewFailureService {
    private static final Logger log = LoggerFactory.getLogger(ReviewFailureService.class);
    private final ReviewRepository reviews;
    private final ReviewFailureRepository failures;
    private final CreditLedgerService credits;
    private final ReviewEvents events;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public ReviewFailureService(ReviewRepository reviews, ReviewFailureRepository failures, CreditLedgerService credits,
            ReviewEvents events, ObjectMapper json, PlatformTransactionManager transactions) {
        this.reviews = reviews;
        this.failures = failures;
        this.credits = credits;
        this.events = events;
        this.json = json;
        tx = new TransactionTemplate(transactions);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void fail(String tenant, String id, int version, String error) {
        Failure failure = new Failure(tenant, id, version, error);
        // 정산 트랜잭션이 실패해도 요청 기록은 다음 복구 주기에 남는다.
        tx.executeWithoutResult(status -> failures.enqueue(failure));
        settle(failure);
    }

    private void settle(Failure failure) {
        Boolean failed = tx.execute(status -> {
            boolean[] changed = {false};
            reviews.mutate(failure.tenant(), failure.reviewId(), doc -> {
                var version = ReviewRepository.version(doc, failure.version());
                // 먼저 정상 완료된 결과와 과금은 늦게 도착한 실패 요청이 덮어쓰지 않는다.
                if ("done".equals(version.path("status").asText())) return;
                version.put("status", "failed").put("error", failure.error());
                changed[0] = true;
            });
            if (changed[0]) credits.settle(failure.reviewId(), failure.version(), false);
            failures.remove(failure);
            return changed[0];
        });
        if (Boolean.TRUE.equals(failed)) {
            events.publish(failure.reviewId(), failure.version(), "failed", json.createObjectNode().put("error", failure.error()));
        }
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    public void recover() {
        // 미정산 건마다 독립 트랜잭션을 사용해 한 건의 오류가 다른 건의 복구를 막지 않게 한다.
        var pending = new ArrayList<>(failures.pending());
        for (Failure abandoned : failures.abandonedLocalRuns()) {
            try {
                tx.executeWithoutResult(status -> failures.enqueue(abandoned));
                pending.add(abandoned);
            } catch (Exception unavailable) {
                log.warn("Review failure recovery could not be queued for {} v{}", abandoned.reviewId(), abandoned.version());
            }
        }
        for (Failure failure : pending) {
            try {
                settle(failure);
            } catch (Exception unavailable) {
                log.warn("Review failure settlement remains pending for {} v{}", failure.reviewId(), failure.version());
            }
        }
    }
}
