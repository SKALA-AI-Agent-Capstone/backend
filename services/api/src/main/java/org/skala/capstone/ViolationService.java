package org.skala.capstone;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * 문구별 위반(app.violations, app.violation_rewrites)의 원본. 검토 전체 승인과 재검토는 2026-10-22에 없앴다.
 * 결과는 분석이 끝나면 바로 리포트로 보이고, 사람은 문구마다 원문 유지, 수정안 채택, 직접 수정, 다시쓰기를 그때그때 한다.
 * reviews.hitl_* 컬럼은 남아 있지만 더 이상 읽거나 쓰지 않는다.
 */
@Service
public class ViolationService {
    static final Set<String> SEVERITIES=Set.of("high","medium","low");
    final ReviewStore store;
    final JdbcTemplate jdbc;
    final ObjectMapper json;
    final AgentClient agent;
    final TransactionTemplate tx;
    final int humanRewriteLimit;
    public ViolationService(ReviewStore store,AgentClient agent,TransactionTemplate tx,
        @Value("${capstone.review.human-rewrite-limit:3}")int humanRewriteLimit){
        this.store=store;this.jdbc=store.jdbc();this.json=store.mapper();this.agent=agent;this.tx=tx;this.humanRewriteLimit=humanRewriteLimit;
    }

    // ---------- Agent 결과 저장 ----------

    /**
     * Agent 결과를 버전에 저장하고, 최신 버전이면 findings를 violations로 교체한다.
     * 각 finding에는 violationId를 넣어 프론트가 JSONB finding과 행을 연결할 수 있게 한다.
     */
    public void saveResult(String tenant,String id,int no,JsonNode response){
        ObjectNode result=response.path("result") instanceof ObjectNode o?o.deepCopy():json.createObjectNode();
        record Row(String id,String excerpt,String severity,List<String> rewrites){}
        List<Row> rows=new ArrayList<>();
        for(JsonNode f:result.path("findings")){
            String phrase=f.path("phrase").asText("");
            if(phrase.isBlank() || !(f instanceof ObjectNode finding))continue;
            String violationId=UUID.randomUUID().toString();
            finding.put("violationId",violationId);
            List<String> rewrites=new ArrayList<>();
            for(JsonNode r:f.path("rewrites"))if(!r.asText("").isBlank())rewrites.add(r.asText());
            rows.add(new Row(violationId,phrase,severity(f.path("severity").asText("")),rewrites));
        }
        tx.executeWithoutResult(status->{
            boolean[] latest={false};
            store.mutate(tenant,id,d->{
                ObjectNode v=ReviewStore.version(d,no);
                v.set("result",result);v.put("status","done");
                v.set("steps",json.valueToTree(List.of(100,100,100,100,100)));
                latest[0]=ReviewStore.latest(d).path("no").asInt()==no;
            });
            if(!latest[0])return;
            jdbc.update("DELETE FROM app.violations WHERE review_id=CAST(? AS uuid)",id);
            for(Row row:rows){
                String suggestion=row.rewrites().isEmpty()?null:row.rewrites().getLast();
                jdbc.update("INSERT INTO app.violations(id,review_id,text_excerpt,severity,rewrite_suggestion,rewrite_attempt_count) VALUES (CAST(? AS uuid),CAST(? AS uuid),?,?,?,?)",
                    row.id(),id,row.excerpt(),row.severity(),suggestion,row.rewrites().size());
                for(int i=0;i<row.rewrites().size();i++)
                    jdbc.update("INSERT INTO app.violation_rewrites(id,violation_id,attempt_no,text,requested_by_human) VALUES (CAST(? AS uuid),CAST(? AS uuid),?,?,false)",
                        UUID.randomUUID().toString(),row.id(),i+1,row.rewrites().get(i));
            }
        });
    }

    /** Agent severity가 허용값이 아니면 낮추지 않고 medium으로 둔다. */
    static String severity(String raw){
        String value=raw.toLowerCase(Locale.ROOT).trim();
        return SEVERITIES.contains(value)?value:"medium";
    }

    // ---------- 조회 ----------

    public ObjectNode view(String tenant,String id){
        uuid(id);
        ObjectNode doc=store.get(tenant,id);
        attach(doc,id);
        return doc;
    }

    public List<ObjectNode> list(String tenant){
        Map<String,ObjectNode> hitl=new HashMap<>();
        jdbc.query("""
            SELECT r.id::text,
              (SELECT count(*) FROM app.violations v WHERE v.review_id=r.id),
              (SELECT count(*) FROM app.violations v WHERE v.review_id=r.id AND v.final_resolution IS NULL)
            FROM app.reviews r WHERE r.tenant_id=CAST(? AS uuid)""",
            rs->{hitl.put(rs.getString(1),hitlNode(rs.getInt(2),rs.getInt(3)));},tenant);
        List<ObjectNode> docs=store.list(tenant);
        for(ObjectNode doc:docs){ObjectNode h=hitl.get(doc.path("id").asText());if(h!=null)doc.set("hitl",h);}
        return docs;
    }

    void attach(ObjectNode doc,String id){
        // finding 순서와 id를 최신 버전 JSONB에서 가져와 violations를 같은 순서로 낸다.
        Map<String,String> findingIds=new HashMap<>();Map<String,Integer> order=new HashMap<>();
        ArrayNode versions=doc.withArray("versions");
        if(!versions.isEmpty()){
            int i=0;for(JsonNode f:ReviewStore.latest(doc).path("result").path("findings")){
                String vid=f.path("violationId").asText("");
                if(!vid.isEmpty()){findingIds.put(vid,f.path("id").asText(null));order.put(vid,i);}
                i++;
            }
        }
        Map<String,ArrayNode> rewrites=new HashMap<>();
        jdbc.query("""
            SELECT w.violation_id::text,w.attempt_no,w.text,w.requested_by_human,w.retry_note,w.created_at
            FROM app.violation_rewrites w JOIN app.violations v ON v.id=w.violation_id
            WHERE v.review_id=CAST(? AS uuid) ORDER BY w.attempt_no""",rs->{
                rewrites.computeIfAbsent(rs.getString(1),k->json.createArrayNode()).add(json.createObjectNode()
                    .put("attemptNo",rs.getInt(2)).put("text",rs.getString(3)).put("requestedByHuman",rs.getBoolean(4))
                    .put("retryNote",rs.getString(5)).put("createdAt",iso(rs.getObject(6,OffsetDateTime.class))));
            },id);
        List<ObjectNode> violations=jdbc.query("""
            SELECT v.id::text,v.text_excerpt,v.severity,v.rewrite_suggestion,v.rewrite_attempt_count,v.final_resolution,
              v.final_text,v.final_text_validated,v.decided_by::text,u.name,v.decided_at,v.created_at
            FROM app.violations v LEFT JOIN app.users u ON u.id=v.decided_by
            WHERE v.review_id=CAST(? AS uuid) ORDER BY v.created_at,v.id""",(rs,n)->{
                String vid=rs.getString(1);
                ArrayNode history=rewrites.getOrDefault(vid,json.createArrayNode());
                int human=0;for(JsonNode r:history)if(r.path("requestedByHuman").asBoolean())human++;
                ObjectNode v=json.createObjectNode().put("id",vid).put("findingId",findingIds.get(vid))
                    .put("textExcerpt",rs.getString(2)).put("severity",rs.getString(3)).put("rewriteSuggestion",rs.getString(4))
                    .put("rewriteAttemptCount",rs.getInt(5)).put("humanRetryCount",human).put("humanRetryLimit",humanRewriteLimit)
                    .put("finalResolution",rs.getString(6)).put("finalText",rs.getString(7)).put("finalTextValidated",rs.getBoolean(8))
                    .put("decidedBy",rs.getString(9)).put("decidedByName",rs.getString(10)).put("decidedAt",iso(rs.getObject(11,OffsetDateTime.class)));
                v.set("rewrites",history);
                return v;
            },id);
        violations.sort(Comparator.comparing(v->order.getOrDefault(v.path("id").asText(),Integer.MAX_VALUE)));
        doc.set("violations",json.valueToTree(violations));
        int undecided=(int)violations.stream().filter(v->v.path("finalResolution").isNull()).count();
        doc.set("hitl",hitlNode(violations.size(),undecided));
    }

    /** 리포트 하단의 "확인 문구 N건 중 M건 결정" 진행률. 결정이 끝나도 따로 완료 상태는 없다. */
    ObjectNode hitlNode(int total,int undecided){
        return json.createObjectNode().put("violationCount",total).put("undecidedCount",undecided);
    }
    static String iso(OffsetDateTime at){return at==null?null:at.toInstant().toString();}

    // ---------- 문구별 결정 ----------

    record Locked(ObjectNode doc,String authorId){}

    /** 같은 기업의 review를 잠그고, 분석이 끝나 문구를 고칠 수 있는 상태인지 검사한다. */
    Locked lockOpenReview(Jwt jwt,String id){
        String tenant=jwt.getClaimAsString("tenantId");
        var rows=jdbc.queryForList("SELECT author_id::text AS author_id FROM app.reviews WHERE id=CAST(? AS uuid) AND tenant_id=CAST(? AS uuid) FOR UPDATE",id,tenant);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"REVIEW_NOT_FOUND");
        String author=(String)rows.getFirst().get("author_id");
        if(!author.equals(jwt.getSubject()) && !Set.of("admin","reviewer").contains(jwt.getClaimAsString("role")))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"AUTHOR_OR_REVIEWER_REQUIRED");
        ObjectNode doc=store.get(tenant,id);
        if(doc.withArray("versions").isEmpty() || !"done".equals(ReviewStore.latest(doc).path("status").asText()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"VERSION_NOT_READY");
        return new Locked(doc,author);
    }

    Map<String,Object> lockViolation(String id,String violationId){
        var rows=jdbc.queryForList("SELECT text_excerpt,rewrite_suggestion FROM app.violations WHERE id=CAST(? AS uuid) AND review_id=CAST(? AS uuid) FOR UPDATE",violationId,id);
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
                for(JsonNode f:ReviewStore.latest(review.doc()).path("result").path("findings"))
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
                jdbc.update("""
                    UPDATE app.violations SET final_resolution=?,final_text=?,final_text_validated=true,
                      decided_by=CAST(? AS uuid),decided_at=now() WHERE id=CAST(? AS uuid)""",resolution,manualText,jwt.getSubject(),violationId);
            });
            return view(tenant,id);
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
                        var picked=jdbc.queryForList("SELECT text FROM app.violation_rewrites WHERE violation_id=CAST(? AS uuid) AND attempt_no=?",
                            String.class,violationId,body.path("attemptNo").asInt());
                        if(picked.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"REWRITE_ATTEMPT_NOT_FOUND");
                        yield picked.getFirst();
                    }
                    if(violation.get("rewrite_suggestion")==null)throw new ResponseStatusException(HttpStatus.CONFLICT,"NO_REWRITE_SUGGESTION");
                    yield (String)violation.get("rewrite_suggestion");
                }
            };
            jdbc.update("""
                UPDATE app.violations SET final_resolution=?,final_text=?,final_text_validated=true,
                  decided_by=CAST(? AS uuid),decided_at=now() WHERE id=CAST(? AS uuid)""",resolution,finalText,jwt.getSubject(),violationId);
        });
        return view(tenant,id);
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
            for(JsonNode f:ReviewStore.latest(review.doc()).path("result").path("findings"))
                if(violationId.equals(f.path("violationId").asText()))finding=f;
            ObjectNode c=json.createObjectNode().put("reviewId",id).put("tenantId",tenant)
                .put("phrase",(String)violation.get("text_excerpt")).put("reason",finding.path("reason").asText(""))
                .put("claimType",finding.path("claimType").asText("")).put("retryNote",retryNote);
            c.set("previousRewrites",json.valueToTree(jdbc.queryForList(
                "SELECT text FROM app.violation_rewrites WHERE violation_id=CAST(? AS uuid) ORDER BY attempt_no",String.class,violationId)));
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
            int next=jdbc.queryForObject("SELECT coalesce(max(attempt_no),0)+1 FROM app.violation_rewrites WHERE violation_id=CAST(? AS uuid)",Integer.class,violationId);
            jdbc.update("INSERT INTO app.violation_rewrites(id,violation_id,attempt_no,text,requested_by_human,retry_note) VALUES (CAST(? AS uuid),CAST(? AS uuid),?,?,true,?)",
                UUID.randomUUID().toString(),violationId,next,text,retryNote);
            jdbc.update("UPDATE app.violations SET rewrite_suggestion=?,rewrite_attempt_count=? WHERE id=CAST(? AS uuid)",text,next,violationId);
        });
        return view(tenant,id);
    }

    void requireRetryLeft(String violationId){
        int used=jdbc.queryForObject("SELECT count(*) FROM app.violation_rewrites WHERE violation_id=CAST(? AS uuid) AND requested_by_human",Integer.class,violationId);
        if(used>=humanRewriteLimit)throw new ResponseStatusException(HttpStatus.CONFLICT,"HUMAN_REWRITE_LIMIT_REACHED");
    }

    static void uuid(String value){UUID.fromString(value);}
}
