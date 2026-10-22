package org.skala.capstone.review.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.skala.capstone.common.AgentClient;
import org.skala.capstone.review.ReviewRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "capstone.review.queue-enabled", havingValue = "true")
public class ReviewJobQueue {
    private static final Logger log = LoggerFactory.getLogger(ReviewJobQueue.class);

    /** Analysis is paid and slow, so it gets few attempts. */
    static final int MAX_ATTEMPTS = 3;

    /** 승인 뒤 Agent 재개(kind=resume)는 2026-10-22 승인을 없애면서 지웠다. 남은 종류는 분석(run)뿐이다. */
    static final String RUN = "run";

    private final JobRepository jobs;
    private final RabbitTemplate rabbit;
    private final ObjectMapper json;
    private final ReviewRunner reviews;

    public ReviewJobQueue(
            JobRepository jobs, RabbitTemplate rabbit, ObjectMapper json, ReviewRunner reviews) {
        this.jobs = jobs;
        this.rabbit = rabbit;
        this.json = json;
        this.reviews = reviews;
        rabbit.setMandatory(true);
    }

    static int retryDelay(int attempt) {
        return attempt == 1 ? 5 : 30;
    }

    /**
     * Outbox publisher: an API restart cannot lose a committed job. Duplicate publishes are
     * harmless.
     */
    @Scheduled(fixedDelay = 3000, initialDelay = 3000)
    public void dispatch() {
        var rows = jobs.ready();
        for (var row : rows) {
            String id = row.get("review_id").toString(), kind = row.get("kind").toString();
            int no = ((Number) row.get("version_no")).intValue();
            try {
                CorrelationData confirmation = new CorrelationData(UUID.randomUUID().toString());
                rabbit.convertAndSend(
                        "", ReviewQueueTopology.MAIN, id + ":" + no + ":" + kind, confirmation);
                var result = confirmation.getFuture().get(10, TimeUnit.SECONDS);
                if (!result.isAck() || confirmation.getReturned() != null)
                    throw new IllegalStateException("REVIEW_QUEUE_PUBLISH_NOT_CONFIRMED");
                jobs.published(id, no, kind);
            } catch (Exception error) {
                log.warn("Review {} job publish failed for {} v{}", kind, id, no, error);
            }
        }
    }

    /**
     * A task is ACKed only after its outcome or next retry has been recorded in PostgreSQL. The
     * body is reviewId:versionNo:kind. Messages published before V14 have no kind and are analysis
     * runs. A leftover resume message is rejected to the dead-letter queue; V15 closed its job row.
     */
    @RabbitListener(queues = ReviewQueueTopology.MAIN, ackMode = "MANUAL", concurrency = "2")
    public void consume(org.springframework.amqp.core.Message message, Channel channel)
            throws Exception {
        long tag = message.getMessageProperties().getDeliveryTag();
        String[] key = new String(message.getBody(), StandardCharsets.UTF_8).split(":", 3);
        if (key.length < 2) {
            channel.basicReject(tag, false);
            return;
        }
        String id = key[0], kind = key.length == 3 ? key[2] : RUN;
        int no;
        try {
            no = Integer.parseInt(key[1]);
            UUID.fromString(id);
        } catch (IllegalArgumentException invalid) {
            channel.basicReject(tag, false);
            return;
        }
        if (!RUN.equals(kind)) {
            channel.basicReject(tag, false);
            return;
        }
        var claimed = jobs.claim(id, no, kind);
        if (claimed.isEmpty()) {
            channel.basicAck(tag, false);
            return;
        }
        Map<String, Object> row = claimed.getFirst();
        int attempt = ((Number) row.get("attempts")).intValue();
        var request = json.readTree(row.get("request").toString());
        String tenant = request.path("tenantId").asText();
        try {
            if (!reviews.completed(tenant, id, no)) reviews.runQueued(request);
        } catch (Exception failure) {
            try {
                var agentFailure = failure instanceof AgentClient.AgentFailure f ? f : null;
                boolean permanent =
                        agentFailure != null
                                && agentFailure.status >= 400
                                && agentFailure.status < 500
                                && agentFailure.status != 429;
                String trace = agentFailure == null ? null : agentFailure.traceId;
                if (!permanent && attempt < MAX_ATTEMPTS) {
                    int delay = retryDelay(attempt);
                    jobs.retry(id, no, kind, delay, failure.getClass().getSimpleName(), trace);
                    channel.basicAck(tag, false);
                    reviews.retryScheduled(id, no, attempt, delay);
                } else {
                    reviews.failQueued(tenant, id, no);
                    jobs.failed(id, no, kind, failure.getClass().getSimpleName(), trace);
                    channel.basicReject(tag, false);
                }
            } catch (Exception settlementError) {
                log.error(
                        "Review {} job failure could not be recorded for {} v{}",
                        kind,
                        id,
                        no,
                        settlementError);
                channel.basicNack(tag, false, true);
            }
            return;
        }
        try {
            jobs.done(id, no, kind);
            channel.basicAck(tag, false);
        } catch (Exception settlementError) {
            log.error(
                    "Review {} job completion could not be recorded for {} v{}",
                    kind,
                    id,
                    no,
                    settlementError);
            channel.basicNack(tag, false, true);
        }
    }

    /**
     * Recover deliveries left running by a crashed consumer after the Agent's HTTP timeout
     * (analysis 300 seconds).
     */
    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    public void recoverStale() {
        var rows = jobs.stale();
        for (var row : rows) {
            String id = row.get("review_id").toString(), kind = row.get("kind").toString();
            int no = ((Number) row.get("version_no")).intValue(),
                    attempts = ((Number) row.get("attempts")).intValue();
            String tenant = row.get("tenant").toString();
            try {
                boolean done = reviews.completed(tenant, id, no);
                boolean failed = reviews.terminalFailure(tenant, id, no);
                if (!done && !failed && attempts >= MAX_ATTEMPTS) {
                    reviews.failQueued(tenant, id, no);
                    failed = true;
                }
                String status = done ? "done" : failed ? "failed" : "retry";
                jobs.recover(id, no, kind, status);
            } catch (Exception error) {
                log.warn("Stale review {} job recovery failed for {} v{}", kind, id, no, error);
            }
        }
    }
}
