package org.skala.capstone.review;

import org.skala.capstone.review.model.ReviewStatus;
import org.skala.capstone.review.model.ReviewDocument;
import org.skala.capstone.review.model.AnalysisResult;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class ReviewRunner implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ReviewRunner.class);
    private final ReviewRepository store;
    private final ReviewAssetService assets;
    private final ObjectMapper json;
    private final AgentClient agent;
    private final ResultWriter results;
    private final CreditLedgerService credits;
    private final TransactionTemplate tx;
    private final ReviewEvents events;
    private final ReviewFailureService failures;
    private final ExecutorService jobs=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),
        Thread.ofPlatform().name("review-job-",0).factory(),new ThreadPoolExecutor.AbortPolicy());

    public ReviewRunner(ReviewRepository store,ReviewAssetService assets,ObjectMapper json,AgentClient agent,
            ResultWriter results,CreditLedgerService credits,TransactionTemplate tx,ReviewEvents events,ReviewFailureService failures) {
        this.store=store;this.assets=assets;this.json=json;this.agent=agent;this.results=results;
        this.credits=credits;this.tx=tx;this.events=events;this.failures=failures;
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
        store.mutate(tenant,id,d->ReviewDocument.from(d).version(no).start());
        events.publishAfterCommit(id,no,"started");
        AnalysisResult response=AnalysisResult.fromAgent(callAgent("/internal/runs",request));
        tx.executeWithoutResult(status->{
            results.saveResult(tenant,id,no,response);
            credits.settle(id,no,response.chargeable());
        });
        // 결과가 저장되면 바로 리포트다. 승인 대기가 없으므로 completed 하나로 스트림을 닫는다.
        events.publishAfterCommit(id,no,"completed");
        try{assets.purge(id,no);}catch(Exception ignored){}
    }
    public boolean completed(String tenant,String id,int no){
        try{
            if(ReviewDocument.from(store.get(tenant,id)).version(no).status()!=ReviewStatus.DONE)return false;
            String status=credits.status(id,no);
            // A retained partial result is also terminal, with its reservation returned to the user.
            return "charged".equals(status) || "released".equals(status);
        }catch(Exception ignored){return false;}
    }
    public boolean terminalFailure(String tenant,String id,int no){
        try{
            if(ReviewDocument.from(store.get(tenant,id)).version(no).status()!=ReviewStatus.FAILED)return false;
            String status=credits.status(id,no);
            return "released".equals(status);
        }catch(Exception ignored){return false;}
    }
    /** The queue will run the job again; the browser keeps its stream and shows the retry. */
    public void retryScheduled(String id,int no,int attempt,int delaySeconds){
        events.publishAfterCommit(id,no,"retry_scheduled",json.createObjectNode().put("attempt",attempt).put("delaySeconds",delaySeconds));
    }
    public JsonNode callAgent(String path,JsonNode body)throws Exception{return agent.post(path,body);}
    public void fail(String tenant,String id,int no,String error){
        try {
            failures.fail(tenant,id,no,error);
        } catch (Exception pending) {
            log.warn("Review failure settlement will be recovered for {} v{}",id,no);
        }
    }
    public void failQueued(String tenant,String id,int no){
        failures.fail(tenant,id,no,"ANALYSIS_FAILED_REUPLOAD_REQUIRED");
        assets.purge(id,no);
    }

    @Override public void close(){jobs.shutdown();}
}
