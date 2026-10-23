package org.skala.capstone;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.skala.capstone.common.AgentClient;
import org.skala.capstone.review.ReviewRunner;
import org.skala.capstone.review.queue.JobRepository;
import org.skala.capstone.review.queue.ReviewJobQueue;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReviewJobQueueTest {
    static final String ID="00000000-0000-0000-0000-000000000001";
    JdbcTemplate jdbc=mock(JdbcTemplate.class);
    RabbitTemplate rabbit=mock(RabbitTemplate.class);
    ReviewRunner reviews=mock(ReviewRunner.class);
    Channel channel=mock(Channel.class);
    ReviewJobQueue queue=new ReviewJobQueue(new JobRepository(jdbc),rabbit,new ObjectMapper(),reviews);

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
        verify(jdbc,never()).update(contains("status='retry'"),anyInt(),anyString(),any(),anyString(),anyInt(),anyString());
    }

    // ---------- 메시지 종류(kind) ----------

    Message message(String body){
        MessageProperties props=new MessageProperties();
        props.setDeliveryTag(7);
        return new Message(body.getBytes(StandardCharsets.UTF_8),props);
    }
    @Test void legacyMessageWithoutKindIsAnAnalysisRun()throws Exception{
        queue.consume(message(ID+":1"),channel);
        verify(reviews).runQueued(any());
    }

    /** 승인을 없앤 뒤 브로커에 남은 재개 메시지는 실행하지 않고 실패 큐로 보낸다. 작업 행은 V15가 닫았다. */
    @Test void leftoverResumeMessageIsRejectedWithoutCallingAnything()throws Exception{
        queue.consume(message(ID+":1:resume"),channel);
        verify(channel).basicReject(7,false);
        verify(jdbc,never()).queryForList(anyString(),any(Object[].class));
        verifyNoInteractions(reviews);
    }

    @Test void unknownKindIsRejected()throws Exception{
        queue.consume(message(ID+":1:delete"),channel);
        verify(channel).basicReject(7,false);
        verify(jdbc,never()).queryForList(anyString(),any(Object[].class));
    }

    @Test void staleRunUsesAnalysisChecks(){
        when(jdbc.queryForList(startsWith("SELECT review_id::text,version_no,attempts"))).thenReturn(List.of(
            Map.of("review_id",ID,"version_no",1,"attempts",1,"tenant","tenant")));
        when(reviews.completed("tenant",ID,1)).thenReturn(true);
        queue.recoverStale();
        verify(jdbc).queryForList(startsWith("SELECT review_id::text,version_no,attempts"));
        verify(jdbc).update(contains("status=?"),eq("done"),eq("done"),eq(ID),eq(1));
        verify(reviews,never()).failQueued(anyString(),anyString(),anyInt());
    }

    /** 작업 행에는 종류가 없고, 메시지 본문은 예전처럼 reviewId:versionNo:run이다. */
    @Test void dispatcherPublishesRunMessages(){
        when(jdbc.queryForList(startsWith("SELECT review_id::text,version_no FROM app.review_jobs"))).thenReturn(List.of(
            Map.of("review_id",ID,"version_no",1)));
        doAnswer(call->{
            ((org.springframework.amqp.rabbit.connection.CorrelationData)call.getArgument(3)).getFuture()
                .complete(new org.springframework.amqp.rabbit.connection.CorrelationData.Confirm(true,null));
            return null;
        }).when(rabbit).convertAndSend(eq(""),anyString(),any(Object.class),any(org.springframework.amqp.rabbit.connection.CorrelationData.class));
        queue.dispatch();
        verify(rabbit).convertAndSend(eq(""),anyString(),eq(ID+":1:run"),any(org.springframework.amqp.rabbit.connection.CorrelationData.class));
        verify(jdbc).update(startsWith("UPDATE app.review_jobs SET status='published'"),eq(ID),eq(1));
    }
}
