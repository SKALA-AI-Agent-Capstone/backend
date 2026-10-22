package org.skala.capstone;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.skala.capstone.review.queue.ReviewQueueTopology;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Confirms the declared quorum queue really routes exhausted work into its dead-letter queue. */
@Testcontainers(disabledWithoutDocker=true)
class ReviewQueueBrokerTest {
    @Container static RabbitMQContainer broker=new RabbitMQContainer("rabbitmq:4.3.6-management-alpine");

    @Test void publishConfirmAndDeadLetterRouting()throws Exception{
        var connection=new CachingConnectionFactory(broker.getHost(),broker.getAmqpPort());
        connection.setUsername(broker.getAdminUsername());
        connection.setPassword(broker.getAdminPassword());
        connection.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        connection.setPublisherReturns(true);
        try{
            var topology=new ReviewQueueTopology();
            var admin=new RabbitAdmin(connection);
            admin.declareExchange(topology.reviewDeadExchange());
            admin.declareQueue(topology.reviewDeadQueue());
            admin.declareBinding(topology.reviewDeadBinding(topology.reviewDeadQueue(),topology.reviewDeadExchange()));
            admin.declareQueue(topology.reviewRunQueue());
            var template=new RabbitTemplate(connection);
            template.setMandatory(true);
            String key=UUID.randomUUID()+":1";
            var confirmation=new CorrelationData(key);
            template.convertAndSend("",ReviewQueueTopology.MAIN,key,confirmation);
            assertThat(confirmation.getFuture().get(10,TimeUnit.SECONDS).isAck()).isTrue();
            assertThat(confirmation.getReturned()).isNull();
            var channel=connection.createConnection().createChannel(false);
            try{
                var delivery=channel.basicGet(ReviewQueueTopology.MAIN,false);
                assertThat(delivery).isNotNull();
                assertThat(new String(delivery.getBody(),StandardCharsets.UTF_8)).isEqualTo(key);
                channel.basicReject(delivery.getEnvelope().getDeliveryTag(),false);
            }finally{channel.close();}
            var dead=template.receive(ReviewQueueTopology.DEAD,5000);
            assertThat(dead).isNotNull();
            assertThat(new String(dead.getBody(),StandardCharsets.UTF_8)).isEqualTo(key);
        }finally{connection.destroy();}
    }
}
