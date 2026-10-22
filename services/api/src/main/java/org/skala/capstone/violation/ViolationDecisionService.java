package org.skala.capstone.violation;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;
import org.skala.capstone.common.AgentClient;
import org.skala.capstone.review.ReviewRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** 문구 결정과 다시쓰기의 권한, 잠금과 Agent 검증을 담당한다. */
@Service
public class ViolationDecisionService {
    private final ReviewRepository store;
    private final ViolationRepository repository;
    private final ReviewViewAssembler views;
    private final ObjectMapper json;
    private final AgentClient agent;
    private final TransactionTemplate tx;
    private final int humanRewriteLimit;

    public ViolationDecisionService(ReviewRepository store,ViolationRepository repository,ReviewViewAssembler views,
            ObjectMapper json,AgentClient agent,TransactionTemplate tx,
            @Value("${capstone.review.human-rewrite-limit:3}")int humanRewriteLimit) {
        this.store=store;this.repository=repository;this.views=views;this.json=json;this.agent=agent;
        this.tx=tx;this.humanRewriteLimit=humanRewriteLimit;
    }

    record Locked(ObjectNode doc,String authorId){}

    /** 같은 기업의 review를 잠그고, 분석이 끝나 문구를 고칠 수 있는 상태인지 검사한다. */
    Locked lockOpenReview(Jwt jwt,String id){
        String tenant=jwt.getClaimAsString("tenantId");
        var rows=store.lockAuthor(tenant,id);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"REVIEW_NOT_FOUND");
        String author=(String)rows.getFirst().get("author_id");
        if(!author.equals(jwt.getSubject()) && !Set.of("admin","reviewer").contains(jwt.getClaimAsString("role")))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"AUTHOR_OR_REVIEWER_REQUIRED");
        ObjectNode doc=store.get(tenant,id);
        if(doc.withArray("versions").isEmpty() || !"done".equals(ReviewRepository.latest(doc).path("status").asText()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"VERSION_NOT_READY");
        return new Locked(doc,author);
    }

    Map<String,Object> lockViolation(String id,String violationId){
        var rows=repository.lock(id,violationId);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"VIOLATION_NOT_FOUND");
        return rows.getFirst();
    }

    public ObjectNode decideViolation(Jwt jwt,String id,String violationId,JsonNode body){
        uuid(id);uuid(violationId);
        String resolution=body.path("resolution").asText("");
        if(!Set.of("kept","rewritten","rewritten_manual").contains(resolution))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"INVALID_RESOLUTION");
        String tenant=jwt.getClaimAsString("tenantId");
        // 사람이 쓴 문구는 Agent 재검증을 통과해야 저장할 수 있다(app.violations의
        // final_resolution<>'rewritten_manual' OR final_text_validated 제약과 같은 규칙).
        // requestRewrite와 같은 이유로 Agent 호출은 락 밖에서 한다 — 외부 HTTP 왕복 동안
        // 행 잠금을 들고 있지 않는다.
        if("rewritten_manual".equals(resolution)){
            String manualText=body.path("finalText").asText("").trim();
            if(manualText.isBlank())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"FINAL_TEXT_REQUIRED");
            String[] target=tx.execute(status->{
                Locked review=lockOpenReview(jwt,id);
                String excerpt=(String)lockViolation(id,violationId).get("text_excerpt");
                for(JsonNode f:ReviewRepository.latest(review.doc()).path("result").path("findings"))
                    if(violationId.equals(f.path("violationId").asText()))return new String[]{f.path("reason").asText(""),excerpt};
                return new String[]{"",excerpt};
            });
            JsonNode verification;
            try{
                // 원문도 함께 보내 원문의 규칙 표현이 남았는지, 새 주장이 들어갔는지까지 검사받는다.
                verification=agent.post("/internal/rewrites/verify",store.mapper().createObjectNode()
                    .put("reviewId",id).put("tenantId",tenant).put("text",manualText).put("reason",target[0]).put("phrase",target[1]));
            }catch(AgentClient.AgentFailure failure){
                if(failure.status==503 && "TEXT_MODEL_NOT_CONFIGURED".equals(failure.detail))
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"REWRITE_MODEL_NOT_CONFIGURED");
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"REWRITE_VERIFICATION_FAILED");
            }catch(Exception error){
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"AGENT_UNAVAILABLE");
            }
            if(!verification.path("passed").asBoolean(false)){
                // reasoning을 그대로 메시지에 실어 프론트가 카드의 사유 칸에 바로 덮어쓸 수 있게 한다.
                String reasoning=verification.path("reasoning").asText("");
                throw new ResponseStatusException(HttpStatus.CONFLICT,reasoning.isBlank()?"MANUAL_TEXT_FAILED_VERIFICATION":reasoning);
            }
            tx.executeWithoutResult(status->{
                lockOpenReview(jwt,id);
                lockViolation(id,violationId);
                repository.decide(violationId,resolution,manualText,jwt.getSubject());
            });
            return views.view(tenant,id);
        }
        tx.executeWithoutResult(status->{
            lockOpenReview(jwt,id);
            var violation=lockViolation(id,violationId);
            String finalText=switch(resolution){
                case "kept"->{
                    if(body.hasNonNull("finalText"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"FINAL_TEXT_NOT_ALLOWED");
                    yield (String)violation.get("text_excerpt");
                }
                default->{ // "rewritten" — 위에서 "kept"/"rewritten_manual"을 전부 처리했으니 나머지는 이것뿐이다
                    if(body.hasNonNull("finalText"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"FINAL_TEXT_NOT_ALLOWED");
                    if(body.hasNonNull("attemptNo")){
                        var picked=repository.rewriteAttempt(violationId,body.path("attemptNo").asInt());
                        if(picked.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"REWRITE_ATTEMPT_NOT_FOUND");
                        yield picked.getFirst();
                    }
                    if(violation.get("rewrite_suggestion")==null)throw new ResponseStatusException(HttpStatus.CONFLICT,"NO_REWRITE_SUGGESTION");
                    yield (String)violation.get("rewrite_suggestion");
                }
            };
            repository.decide(violationId,resolution,finalText,jwt.getSubject());
        });
        return views.view(tenant,id);
    }

    /**
     * 사람의 "다시쓰기" 요청. Agent 호출은 잠금 밖에서 하고, 저장 직전에 한도를 다시 검사한다.
     * 한도는 requested_by_human 행 수로 판정한다(자동 리라이트는 세지 않는다).
     */
    public ObjectNode requestRewrite(Jwt jwt,String id,String violationId,JsonNode body){
        uuid(id);uuid(violationId);
        String note=body.path("retryNote").asText("").trim();
        if(note.length()>1000)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"RETRY_NOTE_TOO_LONG");
        String retryNote=note.isEmpty()?null:note;
        String tenant=jwt.getClaimAsString("tenantId");
        ObjectNode command=tx.execute(status->{
            Locked review=lockOpenReview(jwt,id);
            var violation=lockViolation(id,violationId);
            requireRetryLeft(violationId);
            JsonNode finding=json.createObjectNode();
            for(JsonNode f:ReviewRepository.latest(review.doc()).path("result").path("findings"))
                if(violationId.equals(f.path("violationId").asText()))finding=f;
            ObjectNode c=json.createObjectNode().put("reviewId",id).put("tenantId",tenant)
                .put("phrase",(String)violation.get("text_excerpt")).put("reason",finding.path("reason").asText(""))
                .put("claimType",finding.path("claimType").asText("")).put("retryNote",retryNote);
            c.set("previousRewrites",json.valueToTree(repository.previousRewrites(violationId)));
            return c;
        });
        String text;
        try{
            text=agent.post("/internal/rewrites",command).path("text").asText("").trim();
        }catch(AgentClient.AgentFailure failure){
            if(failure.status==503 && "TEXT_MODEL_NOT_CONFIGURED".equals(failure.detail))
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"REWRITE_MODEL_NOT_CONFIGURED");
            // 검증을 통과한 문안이 없을 때 Agent가 마지막 실패 이유를 준다. 시도 횟수는 쓰지 않는다.
            if(failure.status==422 && failure.detail!=null && !failure.detail.isBlank())
                throw new ResponseStatusException(HttpStatus.CONFLICT,failure.detail);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"REWRITE_FAILED");
        }catch(Exception error){
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"AGENT_UNAVAILABLE");
        }
        if(text.isEmpty())throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"REWRITE_FAILED");
        tx.executeWithoutResult(status->{
            lockOpenReview(jwt,id);
            lockViolation(id,violationId);
            requireRetryLeft(violationId);
            int next=repository.nextAttempt(violationId);
            repository.createHumanRewrite(violationId,next,text,retryNote);
        });
        return views.view(tenant,id);
    }

    void requireRetryLeft(String violationId){
        int used=repository.humanRetries(violationId);
        if(used>=humanRewriteLimit)throw new ResponseStatusException(HttpStatus.CONFLICT,"HUMAN_REWRITE_LIMIT_REACHED");
    }

    static void uuid(String value){UUID.fromString(value);}
}
