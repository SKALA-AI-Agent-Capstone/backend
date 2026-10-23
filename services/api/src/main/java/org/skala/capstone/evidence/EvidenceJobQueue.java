package org.skala.capstone.evidence;

import com.rabbitmq.client.Channel;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 증빙 읽기 작업을 RabbitMQ로 나른다. 검토 큐(ReviewJobQueue)와 같은 약속이다: 큐에 넣는 것은 outbox가 하고(서버가 재시작돼도
 * 커밋된 작업은 사라지지 않는다), 메시지는 결과나 다음 재시도가 DB에 적힌 뒤에만 ack한다. 소비자는 하나다(PaddleOCR가 한 번에 한 쪽씩만 돈다).
 */
@Component
@ConditionalOnProperty(name = "capstone.review.queue-enabled", havingValue = "true")
public class EvidenceJobQueue {
    private static final Logger log = LoggerFactory.getLogger(EvidenceJobQueue.class);
    /** 읽기는 유료가 아니지만 느리다. 일시 오류만 재시도한다. */
    static final int MAX_ATTEMPTS = 3;

    private final EvidenceJobRepository jobs;
    private final RabbitTemplate rabbit;
    private final EvidenceReader reader;
    private final EvidenceRepository evidence;

    public EvidenceJobQueue(EvidenceJobRepository jobs, RabbitTemplate rabbit, EvidenceReader reader, EvidenceRepository evidence) {
        this.jobs = jobs;
        this.rabbit = rabbit;
        this.reader = reader;
        this.evidence = evidence;
        rabbit.setMandatory(true);
    }

    static int retryDelay(int attempt) {
        return attempt == 1 ? 10 : 60;
    }

    @Scheduled(fixedDelay = 3000, initialDelay = 3000)
    public void dispatch() {
        for (String id : jobs.ready()) {
            try {
                CorrelationData confirmation = new CorrelationData(UUID.randomUUID().toString());
                rabbit.convertAndSend("", EvidenceQueueTopology.MAIN, id, confirmation);
                var result = confirmation.getFuture().get(10, TimeUnit.SECONDS);
                if (!result.isAck() || confirmation.getReturned() != null) {
                    throw new IllegalStateException("EVIDENCE_QUEUE_PUBLISH_NOT_CONFIRMED");
                }
                jobs.published(id);
            } catch (Exception error) {
                log.warn("Evidence job publish failed for {}", id, error);
            }
        }
    }

    @RabbitListener(queues = EvidenceQueueTopology.MAIN, ackMode = "MANUAL", concurrency = "1")
    public void consume(Message message, Channel channel) throws Exception {
        long tag = message.getMessageProperties().getDeliveryTag();
        String id = new String(message.getBody(), StandardCharsets.UTF_8);
        try {
            UUID.fromString(id);
        } catch (IllegalArgumentException invalid) {
            channel.basicReject(tag, false);
            return;
        }
        var claimed = jobs.claim(id);
        if (claimed.isEmpty()) {
            channel.basicAck(tag, false);
            return;
        }
        int attempt = ((Number) claimed.getFirst().get("attempts")).intValue();
        try {
            reader.readOnce(id);
        } catch (Exception failure) {
            settleFailure(id, tag, channel, attempt, failure);
            return;
        }
        try {
            jobs.done(id);
            channel.basicAck(tag, false);
        } catch (Exception settlementError) {
            log.error("Evidence job completion could not be recorded for {}", id, settlementError);
            channel.basicNack(tag, false, true);
        }
    }

    private void settleFailure(String id, long tag, Channel channel, int attempt, Exception failure) throws Exception {
        try {
            if (!EvidenceReader.permanent(failure) && attempt < MAX_ATTEMPTS) {
                jobs.retry(id, retryDelay(attempt), failure.getClass().getSimpleName());
                channel.basicAck(tag, false);
            } else {
                evidence.fail(id, EvidenceReader.failureCode(failure));
                jobs.failed(id, failure.getClass().getSimpleName());
                channel.basicReject(tag, false);
            }
        } catch (Exception settlementError) {
            log.error("Evidence job failure could not be recorded for {}", id, settlementError);
            channel.basicNack(tag, false, true);
        }
    }

    /** 소비자가 읽는 도중 죽어 running으로 남은 작업을 되살리거나(재시도), 시도를 다 썼으면 증빙을 실패로 닫는다. */
    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    public void recoverStale() {
        for (var row : jobs.stale()) {
            String id = row.get("id").toString();
            int attempts = ((Number) row.get("attempts")).intValue();
            try {
                boolean finished = evidence.owner(id).map(owner -> !"reading".equals(owner.get("status"))).orElse(true);
                String status = finished ? "done" : attempts >= MAX_ATTEMPTS ? "failed" : "retry";
                if (status.equals("failed")) evidence.fail(id, "READ_INTERRUPTED");
                jobs.recover(id, status);
            } catch (Exception error) {
                log.warn("Stale evidence job recovery failed for {}", id, error);
            }
        }
    }
}
