package org.skala.capstone.review;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.concurrent.*;
import org.skala.capstone.billing.CreditLedgerService;
import org.skala.capstone.common.AgentClient;
import org.skala.capstone.review.events.ReviewEvents;
import org.skala.capstone.violation.ResultWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ReviewRunner implements AutoCloseable {
    private final ReviewRepository store;
    private final ReviewAssetService assets;
    private final ObjectMapper json;
    private final AgentClient agent;
    private final ResultWriter results;
    private final CreditLedgerService credits;
    private final TransactionTemplate tx;
    private final ReviewEvents events;
    private final ExecutorService jobs=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),
        Thread.ofPlatform().name("review-job-",0).factory(),new ThreadPoolExecutor.AbortPolicy());

    public ReviewRunner(ReviewRepository store,ReviewAssetService assets,ObjectMapper json,AgentClient agent,
            ResultWriter results,CreditLedgerService credits,TransactionTemplate tx,ReviewEvents events) {
        this.store=store;this.assets=assets;this.json=json;this.agent=agent;this.results=results;
        this.credits=credits;this.tx=tx;this.events=events;
    }

    public void afterCommit(ObjectNode request) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try { jobs.submit(()->execute(request)); }
                catch(RejectedExecutionException busy) {
                    Thread.ofVirtual().start(()->{
                        String tenant=request.path("tenantId").asText(),id=request.path("reviewId").asText();
                        int no=request.path("versionNo").asInt();
                        fail(tenant,id,no,"BUSY_REUPLOAD_REQUIRED");
                        assets.purge(id,no);
                    });
                }
            }
        });
    }

    public void execute(ObjectNode request){
        String tenant=request.path("tenantId").asText(),id=request.path("reviewId").asText();int no=request.path("versionNo").asInt();
        try{
            runQueued(request);
        }catch(Exception e){fail(tenant,id,no,"ANALYSIS_FAILED_REUPLOAD_REQUIRED");}
        finally{assets.purge(id,no);}
    }
    public void runQueued(JsonNode request)throws Exception{
        String tenant=request.path("tenantId").asText(),id=request.path("reviewId").asText();int no=request.path("versionNo").asInt();
        store.mutate(tenant,id,d->ReviewRepository.version(d,no).put("status","analyzing"));
        events.publish(id,no,"started");
        JsonNode response=callAgent("/internal/runs",request);
        tx.executeWithoutResult(status->{
            results.saveResult(tenant,id,no,response);
            JsonNode pipeline=response.path("result").path("pipelineStatus");
            // HTTP 200 alone is not a completed analysis: missing models and partial results are free.
            credits.settle(id,no,"complete".equals(pipeline.path("text").asText())
                && "complete".equals(pipeline.path("visual").asText()));
        });
        // 결과가 저장되면 바로 리포트다. 승인 대기가 없으므로 completed 하나로 스트림을 닫는다.
        events.publish(id,no,"completed");
        try{assets.purge(id,no);}catch(Exception ignored){}
    }
    public boolean completed(String tenant,String id,int no){
        try{
            if(!"done".equals(ReviewRepository.version(store.get(tenant,id),no).path("status").asText()))return false;
            String status=credits.status(id,no);
            // A retained partial result is also terminal, with its reservation returned to the user.
            return "charged".equals(status) || "released".equals(status);
        }catch(Exception ignored){return false;}
    }
    public boolean terminalFailure(String tenant,String id,int no){
        try{
            if(!"failed".equals(ReviewRepository.version(store.get(tenant,id),no).path("status").asText()))return false;
            String status=credits.status(id,no);
            return "released".equals(status);
        }catch(Exception ignored){return false;}
    }
    /** The queue will run the job again; the browser keeps its stream and shows the retry. */
    public void retryScheduled(String id,int no,int attempt,int delaySeconds){
        events.publish(id,no,"retry_scheduled",json.createObjectNode().put("attempt",attempt).put("delaySeconds",delaySeconds));
    }
    public JsonNode callAgent(String path,JsonNode body)throws Exception{return agent.post(path,body);}
    public void fail(String tenant,String id,int no,String error){
        try{store.mutate(tenant,id,d->{var v=ReviewRepository.version(d,no);v.put("status","failed").put("error",error);});}catch(Exception ignored){}
        try{credits.release(id,no);}catch(Exception ignored){}
        events.publish(id,no,"failed",json.createObjectNode().put("error",error));
    }
    public void failQueued(String tenant,String id,int no){
        // Both operations are idempotent, so a redelivery can finish either one after a crash.
        credits.release(id,no);
        store.mutate(tenant,id,d->{var v=ReviewRepository.version(d,no);
            v.put("status","failed").put("error","ANALYSIS_FAILED_REUPLOAD_REQUIRED");});
        assets.purge(id,no);
        events.publish(id,no,"failed",json.createObjectNode().put("error","ANALYSIS_FAILED_REUPLOAD_REQUIRED"));
    }

    @Override public void close(){jobs.shutdown();}
}
