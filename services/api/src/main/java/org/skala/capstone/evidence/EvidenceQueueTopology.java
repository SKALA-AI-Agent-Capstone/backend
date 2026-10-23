package org.skala.capstone.evidence;

import org.springframework.amqp.core.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 증빙 읽기 전용 내구성 큐. 작업 상태와 입력은 DB(app.evidence_jobs, product_evidence)가 기준이고 큐는 알림이다. */
@Configuration
@ConditionalOnProperty(name = "capstone.review.queue-enabled", havingValue = "true")
public class EvidenceQueueTopology {
    public static final String MAIN = "evidence.read";
    public static final String DEAD = "evidence.read.dead";
    public static final String DEAD_EXCHANGE = "evidence.dead";

    @Bean
    public Queue evidenceReadQueue() {
        return QueueBuilder.durable(MAIN).quorum().deadLetterExchange(DEAD_EXCHANGE).build();
    }

    @Bean
    public Queue evidenceDeadQueue() {
        return QueueBuilder.durable(DEAD).build();
    }

    @Bean
    public DirectExchange evidenceDeadExchange() {
        return new DirectExchange(DEAD_EXCHANGE);
    }

    @Bean
    public Binding evidenceDeadBinding(Queue evidenceDeadQueue, DirectExchange evidenceDeadExchange) {
        return BindingBuilder.bind(evidenceDeadQueue).to(evidenceDeadExchange).with(MAIN);
    }
}
