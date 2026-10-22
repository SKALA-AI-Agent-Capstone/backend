package org.skala.capstone;

import org.skala.capstone.common.AgentClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Durable task notification; the database remains the source of job state and input. */
@Configuration
@EnableRabbit
@ConditionalOnProperty(name="capstone.review.queue-enabled",havingValue="true")
class ReviewQueueTopology {
    static final String MAIN="review.run";
    static final String DEAD="review.run.dead";
    static final String DEAD_EXCHANGE="review.dead";

    @Bean Queue reviewRunQueue(){
        return QueueBuilder.durable(MAIN).quorum().deadLetterExchange(DEAD_EXCHANGE).build();
    }
    @Bean Queue reviewDeadQueue(){return QueueBuilder.durable(DEAD).build();}
    @Bean DirectExchange reviewDeadExchange(){return new DirectExchange(DEAD_EXCHANGE);}
    @Bean Binding reviewDeadBinding(Queue reviewDeadQueue,DirectExchange reviewDeadExchange){
        return BindingBuilder.bind(reviewDeadQueue).to(reviewDeadExchange).with(MAIN);
    }
}

@Component
@ConditionalOnProperty(name="capstone.review.queue-enabled",havingValue="true")
public class ReviewJobQueue {
    private static final Logger log=LoggerFactory.getLogger(ReviewJobQueue.class);
    /** Analysis is paid and slow, so it gets few attempts. */
    static final int MAX_ATTEMPTS=3;
    /** 승인 뒤 Agent 재개(kind=resume)는 2026-10-22 승인을 없애면서 지웠다. 남은 종류는 분석(run)뿐이다. */
    static final String RUN="run";
    private final JdbcTemplate jdbc;
    private final RabbitTemplate rabbit;
    private final ObjectMapper json;
    private final ReviewService reviews;

    public ReviewJobQueue(JdbcTemplate jdbc,RabbitTemplate rabbit,ObjectMapper json,ReviewService reviews){
        this.jdbc=jdbc;this.rabbit=rabbit;this.json=json;this.reviews=reviews;
        rabbit.setMandatory(true);
    }

    static int retryDelay(int attempt){return attempt==1?5:30;}

    /** Outbox publisher: an API restart cannot lose a committed job. Duplicate publishes are harmless. */
    @Scheduled(fixedDelay=3000,initialDelay=3000)
    public void dispatch(){
        var rows=jdbc.queryForList("SELECT review_id::text,version_no,kind FROM app.review_jobs "+
            "WHERE status IN ('pending','retry') AND kind='run' AND next_attempt_at<=now() ORDER BY next_attempt_at LIMIT 20");
        for(var row:rows){
            String id=row.get("review_id").toString(),kind=row.get("kind").toString();
            int no=((Number)row.get("version_no")).intValue();
            try{
                CorrelationData confirmation=new CorrelationData(UUID.randomUUID().toString());
                rabbit.convertAndSend("",ReviewQueueTopology.MAIN,id+":"+no+":"+kind,confirmation);
                var result=confirmation.getFuture().get(10,TimeUnit.SECONDS);
                if(!result.isAck() || confirmation.getReturned()!=null)
                    throw new IllegalStateException("REVIEW_QUEUE_PUBLISH_NOT_CONFIRMED");
                jdbc.update("UPDATE app.review_jobs SET status='published',updated_at=now() "+
                    "WHERE review_id=CAST(? AS uuid) AND version_no=? AND status IN ('pending','retry') AND kind=?",id,no,kind);
            }catch(Exception error){
                log.warn("Review {} job publish failed for {} v{}",kind,id,no,error);
            }
        }
    }

    /**
     * A task is ACKed only after its outcome or next retry has been recorded in PostgreSQL.
     * The body is reviewId:versionNo:kind. Messages published before V14 have no kind and are analysis runs.
     * A leftover resume message is rejected to the dead-letter queue; V15 closed its job row.
     */
    @RabbitListener(queues=ReviewQueueTopology.MAIN,ackMode="MANUAL",concurrency="2")
    public void consume(org.springframework.amqp.core.Message message,Channel channel)throws Exception{
        long tag=message.getMessageProperties().getDeliveryTag();
        String[] key=new String(message.getBody(),StandardCharsets.UTF_8).split(":",3);
        if(key.length<2){channel.basicReject(tag,false);return;}
        String id=key[0],kind=key.length==3?key[2]:RUN;
        int no;
        try{no=Integer.parseInt(key[1]);UUID.fromString(id);}
        catch(IllegalArgumentException invalid){channel.basicReject(tag,false);return;}
        if(!RUN.equals(kind)){channel.basicReject(tag,false);return;}
        var claimed=jdbc.queryForList("UPDATE app.review_jobs SET status='running',attempts=attempts+1,updated_at=now() "+
            "WHERE review_id=CAST(? AS uuid) AND version_no=? AND status IN ('pending','published','retry') "+
            "AND next_attempt_at<=now() AND kind=? "+
            "RETURNING request::text,attempts",id,no,kind);
        if(claimed.isEmpty()){channel.basicAck(tag,false);return;}
        Map<String,Object> row=claimed.getFirst();
        int attempt=((Number)row.get("attempts")).intValue();
        var request=json.readTree(row.get("request").toString());
        String tenant=request.path("tenantId").asText();
        try{
            if(!reviews.completed(tenant,id,no))reviews.runQueued(request);
        }catch(Exception failure){
            try{
                var agentFailure=failure instanceof AgentClient.AgentFailure f?f:null;
                boolean permanent=agentFailure!=null
                    && agentFailure.status>=400 && agentFailure.status<500 && agentFailure.status!=429;
                String trace=agentFailure==null?null:agentFailure.traceId;
                if(!permanent && attempt<MAX_ATTEMPTS){
                    int delay=retryDelay(attempt);
                    jdbc.update("UPDATE app.review_jobs SET status='retry',updated_at=now(), "+
                        "next_attempt_at=now()+(? * interval '1 second'),last_error=?,last_trace_id=? "+
                        "WHERE review_id=CAST(? AS uuid) AND version_no=? AND kind=?",delay,failure.getClass().getSimpleName(),trace,id,no,kind);
                    channel.basicAck(tag,false);
                    reviews.retryScheduled(id,no,attempt,delay);
                }else{
                    reviews.failQueued(tenant,id,no);
                    jdbc.update("UPDATE app.review_jobs SET status='failed',request='{}'::jsonb,updated_at=now(),last_error=?,last_trace_id=? "+
                        "WHERE review_id=CAST(? AS uuid) AND version_no=? AND kind=?",failure.getClass().getSimpleName(),trace,id,no,kind);
                    channel.basicReject(tag,false);
                }
            }catch(Exception settlementError){
                log.error("Review {} job failure could not be recorded for {} v{}",kind,id,no,settlementError);
                channel.basicNack(tag,false,true);
            }
            return;
        }
        try{
            jdbc.update("UPDATE app.review_jobs SET status='done',request='{}'::jsonb,updated_at=now(),last_error=NULL,last_trace_id=NULL "+
                "WHERE review_id=CAST(? AS uuid) AND version_no=? AND kind=?",id,no,kind);
            channel.basicAck(tag,false);
        }catch(Exception settlementError){
            log.error("Review {} job completion could not be recorded for {} v{}",kind,id,no,settlementError);
            channel.basicNack(tag,false,true);
        }
    }

    /**
     * Recover deliveries left running by a crashed consumer after the Agent's HTTP timeout (analysis 300 seconds).
     */
    @Scheduled(fixedDelay=60000,initialDelay=60000)
    public void recoverStale(){
        var rows=jdbc.queryForList("SELECT review_id::text,version_no,kind,attempts,request->>'tenantId' AS tenant "+
            "FROM app.review_jobs WHERE status='running' AND kind='run' AND updated_at<now()-interval '7 minutes' LIMIT 20");
        for(var row:rows){
            String id=row.get("review_id").toString(),kind=row.get("kind").toString();
            int no=((Number)row.get("version_no")).intValue(),attempts=((Number)row.get("attempts")).intValue();
            String tenant=row.get("tenant").toString();
            try{
                boolean done=reviews.completed(tenant,id,no);
                boolean failed=reviews.terminalFailure(tenant,id,no);
                if(!done && !failed && attempts>=MAX_ATTEMPTS){reviews.failQueued(tenant,id,no);failed=true;}
                String status=done?"done":failed?"failed":"retry";
                jdbc.update("UPDATE app.review_jobs SET status=?,request=CASE WHEN ?='retry' THEN request ELSE '{}'::jsonb END, "+
                    "next_attempt_at=now(),updated_at=now() "+
                    "WHERE review_id=CAST(? AS uuid) AND version_no=? AND kind=? AND status='running' AND updated_at<now()-interval '7 minutes'",
                    status,status,id,no,kind);
            }catch(Exception error){
                log.warn("Stale review {} job recovery failed for {} v{}",kind,id,no,error);
            }
        }
    }
}
