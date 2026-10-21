package org.skala.capstone;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

class ReviewJobQueueTest {
    static final String ID="00000000-0000-0000-0000-000000000001";
    JdbcTemplate jdbc=mock(JdbcTemplate.class);
    RabbitTemplate rabbit=mock(RabbitTemplate.class);
    ReviewService reviews=mock(ReviewService.class);
    Channel channel=mock(Channel.class);
    ReviewJobQueue queue=new ReviewJobQueue(jdbc,rabbit,new ObjectMapper(),reviews);

    Message message(){
        MessageProperties props=new MessageProperties();
        props.setDeliveryTag(7);
        return new Message((ID+":1").getBytes(StandardCharsets.UTF_8),props);
    }
    @BeforeEach void setup(){
        when(jdbc.queryForList(startsWith("UPDATE app.review_jobs SET status='running'"),eq(ID),eq(1)))
            .thenReturn(List.of(Map.of("request","{\"reviewId\":\""+ID+"\",\"tenantId\":\"tenant\",\"versionNo\":1}",
                "attempts",1)));
    }

    @Test void successAcknowledgesAfterRecordingCompletion()throws Exception{
        queue.consume(message(),channel);
        var order=inOrder(reviews,jdbc,channel);
        order.verify(reviews).runQueued(any());
        order.verify(jdbc).update(startsWith("UPDATE app.review_jobs SET status='done'"),eq(ID),eq(1));
        order.verify(channel).basicAck(7,false);
    }

    @Test void transientFailureSchedulesRetryAndAcknowledgesOriginal()throws Exception{
        doThrow(new IOException("temporary")).when(reviews).runQueued(any());
        queue.consume(message(),channel);
        verify(jdbc).queryForList(contains("AND next_attempt_at<=now()"),eq(ID),eq(1));
        verify(jdbc).update(contains("status='retry'"),eq(5),eq("IOException"),isNull(),eq(ID),eq(1));
        verify(channel).basicAck(7,false);
        verify(reviews,never()).failQueued(anyString(),anyString(),anyInt());
    }

    @Test void exhaustedFailureReleasesCreditAndDeadLetters()throws Exception{
        when(jdbc.queryForList(startsWith("UPDATE app.review_jobs SET status='running'"),eq(ID),eq(1)))
            .thenReturn(List.of(Map.of("request","{\"reviewId\":\""+ID+"\",\"tenantId\":\"tenant\",\"versionNo\":1}",
                "attempts",3)));
        doThrow(new IOException("temporary")).when(reviews).runQueued(any());
        queue.consume(message(),channel);
        var order=inOrder(reviews,jdbc,channel);
        order.verify(reviews).failQueued("tenant",ID,1);
        order.verify(jdbc).update(contains("status='failed'"),eq("IOException"),isNull(),eq(ID),eq(1));
        order.verify(channel).basicReject(7,false);
    }

    @Test void agentFailureRecordsItsTraceIdForRetryAndFailure()throws Exception{
        String trace="0b6f1f3c-7a5e-4d0c-9a51-2b8f0d6c1e11";
        doThrow(new AgentClient.AgentFailure(500,"RUN_FAILED",trace)).when(reviews).runQueued(any());
        queue.consume(message(),channel);
        verify(jdbc).update(contains("last_trace_id=?"),eq(5),eq("AgentFailure"),eq(trace),eq(ID),eq(1));

        when(jdbc.queryForList(startsWith("UPDATE app.review_jobs SET status='running'"),eq(ID),eq(1)))
            .thenReturn(List.of(Map.of("request","{\"reviewId\":\""+ID+"\",\"tenantId\":\"tenant\",\"versionNo\":1}",
                "attempts",3)));
        queue.consume(message(),channel);
        verify(jdbc).update(contains("status='failed'"),eq("AgentFailure"),eq(trace),eq(ID),eq(1));
    }

    @Test void duplicateDeliveryDoesNotRerunOrCharge()throws Exception{
        when(jdbc.queryForList(startsWith("UPDATE app.review_jobs SET status='running'"),eq(ID),eq(1)))
            .thenReturn(List.of());
        queue.consume(message(),channel);
        verify(channel).basicAck(7,false);
        verify(reviews,never()).runQueued(any());
    }

    @Test void completionWriteFailureNeverTurnsCompletedReviewIntoFailure()throws Exception{
        when(jdbc.update(startsWith("UPDATE app.review_jobs SET status='done'"),eq(ID),eq(1)))
            .thenThrow(new IllegalStateException("database temporarily unavailable"));
        queue.consume(message(),channel);
        verify(channel).basicNack(7,false,true);
        verify(reviews,never()).failQueued(anyString(),anyString(),anyInt());
        verify(jdbc,never()).update(contains("status='retry'"),anyInt(),anyString(),any(),anyString(),anyInt());
    }
}
