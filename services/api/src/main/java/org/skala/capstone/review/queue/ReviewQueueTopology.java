package org.skala.capstone.review.queue;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Durable task notification; the database remains the source of job state and input. */
@Configuration
@EnableRabbit
@ConditionalOnProperty(name="capstone.review.queue-enabled",havingValue="true")
public class ReviewQueueTopology {
    public static final String MAIN="review.run";
    public static final String DEAD="review.run.dead";
    public static final String DEAD_EXCHANGE="review.dead";

    @Bean public Queue reviewRunQueue(){
        return QueueBuilder.durable(MAIN).quorum().deadLetterExchange(DEAD_EXCHANGE).build();
    }
    @Bean public Queue reviewDeadQueue(){return QueueBuilder.durable(DEAD).build();}
    @Bean public DirectExchange reviewDeadExchange(){return new DirectExchange(DEAD_EXCHANGE);}
    @Bean public Binding reviewDeadBinding(Queue reviewDeadQueue,DirectExchange reviewDeadExchange){
        return BindingBuilder.bind(reviewDeadQueue).to(reviewDeadExchange).with(MAIN);
    }
}

