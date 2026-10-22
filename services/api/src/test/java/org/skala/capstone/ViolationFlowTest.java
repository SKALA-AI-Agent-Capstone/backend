package org.skala.capstone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 실제 PostgreSQL(Flyway V1~V5)에서 결과 저장, 문구별 결정, 전체 승인과 기업 범위를 검증한다. Agent와 Redis는 대체한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {
    "capstone.internal-token=unit-test-only-token-not-for-production-12345",
    "capstone.agent-base-url=http://127.0.0.1:1",
    "capstone.jwt-secret=unit-test-only-jwt-secret-not-for-production-12345",
    "capstone.review.human-rewrite-limit=2"
})
class ViolationFlowTest {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired ViolationService violations;
    @Autowired ReviewCreditService credits;
    @Autowired CreditCatalog catalog;
    @Autowired DataSource dataSource;
    @Autowired ReviewService reviews;
    @Autowired org.springframework.transaction.support.TransactionTemplate tx;
    @MockitoBean AgentClient agent;
    @MockitoBean StringRedisTemplate redis;

    record User(String id,String tenant,String role){}
    static final String TENANT=uuid(1),OTHER_TENANT=uuid(2);
    static final User AUTHOR=new User(uuid(11),TENANT,"author"),REVIEWER=new User(uuid(12),TENANT,"reviewer"),
        COLLEAGUE=new User(uuid(13),TENANT,"author"),OUTSIDER=new User(uuid(21),OTHER_TENANT,"admin");
    static String uuid(int n){return String.format("00000000-0000-0000-0000-%012d",n);}

    @BeforeEach
    void seed()throws Exception{
        for(String t:List.of(TENANT,OTHER_TENANT))
            jdbc.update("INSERT INTO app.tenants VALUES (CAST(? AS uuid),?) ON CONFLICT DO NOTHING",t,"t"+t.substring(35));
        for(User u:List.of(AUTHOR,REVIEWER,COLLEAGUE,OUTSIDER))
            jdbc.update("INSERT INTO app.users (id,tenant_id,email,name,password_hash,role) VALUES (CAST(? AS uuid),CAST(? AS uuid),?,?,'x',?) ON CONFLICT DO NOTHING",
                u.id(),u.tenant(),u.id()+"@test",u.role()+u.id().substring(35),u.role());
        when(agent.post(anyString(),any())).thenReturn(json.createObjectNode());
    }

    @Test
    void creditCatalogAndReviewCostComeFromDatabase() {
        // V11: 활성 금액권은 100, 500, 1,000, 2,000 네 종류다
        assertThat(catalog.packages()).hasSize(4);
        assertThat(catalog.price(100)).isEqualTo(5000);
        assertThat(catalog.price(500)).isEqualTo(23000);
        assertThat(catalog.price(1000)).isEqualTo(42000);
        assertThat(catalog.price(2000)).isEqualTo(70000);
        assertThat(catalog.price(200)).isNull();
        assertThat(catalog.reviewCost()).isEqualTo(100);
        jdbc.update("UPDATE app.credit_policy SET review_cost=120 WHERE id=true");
        try { assertThat(catalog.reviewCost()).isEqualTo(120); }
        finally { jdbc.update("UPDATE app.credit_policy SET review_cost=100 WHERE id=true"); }
    }

    RequestPostProcessor as(User u){return jwt().jwt(j->j.subject(u.id()).claim("tenantId",u.tenant()).claim("role",u.role()));}
    MockHttpServletRequestBuilder postJson(String path,Object body)throws Exception{
        return post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
    }
    JsonNode read(org.springframework.test.web.servlet.ResultActions r)throws Exception{
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    @Test
    void zeroBalanceCannotStartReviewAndConcurrentReservationsCannotOverspend() throws Exception {
        jdbc.update("UPDATE app.users SET credit_balance=0 WHERE id=CAST(? AS uuid)",AUTHOR.id());
        int before=jdbc.queryForObject("SELECT count(*) FROM app.reviews WHERE author_id=CAST(? AS uuid)",Integer.class,AUTHOR.id());
        var file=new MockMultipartFile("files","ad.png","image/png",new byte[]{1});
        mvc.perform(multipart("/api/v1/reviews").file(file)
                .param("metadata","{\"product\":{\"name\":\"검사\"}}")
                .with(as(AUTHOR)))
            .andExpect(status().isPaymentRequired());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.reviews WHERE author_id=CAST(? AS uuid)",Integer.class,AUTHOR.id()))
            .isEqualTo(before);

        jdbc.update("UPDATE app.users SET credit_balance=100 WHERE id=CAST(? AS uuid)",AUTHOR.id());
        String first=UUID.randomUUID().toString();
        credits.reserve(TENANT,AUTHOR.id(),first,1);
        assertThat(jdbc.queryForObject("SELECT credit_balance FROM app.users WHERE id=CAST(? AS uuid)",Integer.class,AUTHOR.id())).isZero();
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.web.server.ResponseStatusException.class,
            () -> credits.reserve(TENANT,AUTHOR.id(),UUID.randomUUID().toString(),1));
        credits.release(first,1);
        credits.release(first,1);
        assertThat(jdbc.queryForObject("SELECT credit_balance FROM app.users WHERE id=CAST(? AS uuid)",Integer.class,AUTHOR.id())).isEqualTo(100);
        String second=UUID.randomUUID().toString();
        credits.reserve(TENANT,AUTHOR.id(),second,1);
        credits.charge(second,1);
        credits.release(second,1);
        assertThat(jdbc.queryForObject("SELECT credit_balance FROM app.users WHERE id=CAST(? AS uuid)",Integer.class,AUTHOR.id())).isZero();
    }

    int balance(){
        return jdbc.queryForObject("SELECT credit_balance FROM app.users WHERE id=CAST(? AS uuid)",Integer.class,AUTHOR.id());
    }
    String reserveReview(){
        jdbc.update("UPDATE app.users SET credit_balance=500 WHERE id=CAST(? AS uuid)",AUTHOR.id());
        String id=review();
        credits.reserve(TENANT,AUTHOR.id(),id,1);
        return id;
    }
    ObjectNode run(String id){return json.createObjectNode().put("reviewId",id).put("tenantId",TENANT).put("versionNo",1);}
    String chargeStatus(String id){
        return jdbc.queryForObject("SELECT status FROM app.review_credit_charges WHERE review_id=CAST(? AS uuid) AND version_no=1",String.class,id);
    }

    @Test
    void redisUploadFailureRollsBackCreditAndReview()throws Exception{
        jdbc.update("UPDATE app.users SET credit_balance=500 WHERE id=CAST(? AS uuid)",AUTHOR.id());
        int beforeReviews=jdbc.queryForObject("SELECT count(*) FROM app.reviews",Integer.class);
        int beforeCharges=jdbc.queryForObject("SELECT count(*) FROM app.review_credit_charges",Integer.class);
        int beforeAssets=jdbc.queryForObject("SELECT count(*) FROM app.review_assets",Integer.class);
        @SuppressWarnings("unchecked") ValueOperations<String,String> values=mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        doThrow(new RedisConnectionFailureException("synthetic Redis outage")).when(values).set(anyString(),anyString(),any(java.time.Duration.class));
        var file=new MockMultipartFile("files","ad.png","image/png",new byte[]{(byte)137,80,78,71,13,10,26,10,0});
        mvc.perform(multipart("/api/v1/reviews").file(file).param("metadata","{\"product\":{\"name\":\"검사\"}}").with(as(AUTHOR)))
            .andExpect(status().isBadGateway()).andExpect(jsonPath("$.message").value("UPLOAD_STORAGE_FAILED"));
        assertThat(balance()).isEqualTo(500);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.reviews",Integer.class)).isEqualTo(beforeReviews);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.review_credit_charges",Integer.class)).isEqualTo(beforeCharges);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.review_assets",Integer.class)).isEqualTo(beforeAssets);
        verifyNoInteractions(agent);
    }

    @Test
    void analysisFailureReturnsReservedCreditOnlyOnce()throws Exception{
        String id=reserveReview();
        when(agent.post(eq("/internal/runs"),any())).thenThrow(new java.io.IOException("synthetic Agent outage"));
        reviews.execute(run(id));
        assertThat(read(id).path("versions").get(0).path("status").asText()).isEqualTo("failed");
        assertThat(chargeStatus(id)).isEqualTo("released");
        assertThat(balance()).isEqualTo(500);
        reviews.failQueued(TENANT,id,1);
        assertThat(balance()).isEqualTo(500);
    }

    @Test
    void partialOrUnconfiguredAnalysisKeepsResultsWithoutCharging()throws Exception{
        for(var pipeline:List.of(List.of("partial","complete"),List.of("not_configured","not_configured"),
            List.of("needs_input","complete"),List.of("complete","partial"),List.of("",""))){
            String id=reserveReview();
            when(agent.post(eq("/internal/runs"),any())).thenReturn(result(pipeline.get(0),pipeline.get(1),finding("f","보습 도움","low")));
            reviews.runQueued(run(id));
            assertThat(read(id).path("versions").get(0).path("result").path("findings")).hasSize(1);
            assertThat(chargeStatus(id)).isEqualTo("released");
            assertThat(balance()).isEqualTo(500);
            assertThat(reviews.completed(TENANT,id,1)).isTrue();
            credits.release(id,1);
            assertThat(balance()).isEqualTo(500);
        }
    }

    @Test
    void fullyCompletedAnalysisChargesItsReservation()throws Exception{
        String id=reserveReview();
        when(agent.post(eq("/internal/runs"),any())).thenReturn(result("complete","complete"));
        reviews.runQueued(run(id));
        assertThat(chargeStatus(id)).isEqualTo("charged");
        assertThat(balance()).isEqualTo(400);
        credits.release(id,1);
        assertThat(balance()).isEqualTo(400);
        assertThat(reviews.completed(TENANT,id,1)).isTrue();
    }

    @Test
    void partialResultRefundRollsBackWithItsResultTransaction(){
        String id=reserveReview();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(status->{
            credits.settle(id,1,false);
            throw new IllegalStateException("synthetic result storage failure");
        }));
        assertThat(chargeStatus(id)).isEqualTo("reserved");
        assertThat(balance()).isEqualTo(400);
        credits.release(id,1);
        assertThat(balance()).isEqualTo(500);
    }

    @Autowired ReviewEvents events;

    @Test
    void progressEventsAreStoredAndRelayedToTheTenantStreamOnly()throws Exception{
        String id=review();
        jdbc.update("UPDATE app.reviews SET document=jsonb_set(document,'{versions,0,steps}','[0,0,0,0,0]'::jsonb) WHERE id=CAST(? AS uuid)",id);
        String token="run-token-for-sse-test-0123456789abcdef";
        jdbc.update("INSERT INTO app.review_runs VALUES (CAST(? AS uuid),1,?)",id,ReviewService.hash(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        String internal="Bearer unit-test-only-token-not-for-production-12345";

        // 다른 기업은 스트림을 열 수 없다.
        mvc.perform(get("/api/v1/reviews/"+id+"/events").with(as(OUTSIDER))).andExpect(status().isNotFound());

        var stream=mvc.perform(get("/api/v1/reviews/"+id+"/events").with(as(AUTHOR)).accept(MediaType.TEXT_EVENT_STREAM))
            .andExpect(request().asyncStarted()).andReturn();
        assertThat(events.subscriberCount(id)).isEqualTo(1);

        // 실행 토큰이 틀리면 이벤트를 받지 않는다.
        mvc.perform(post("/internal/reviews/"+id+"/events").header("Authorization",internal).header("X-Run-Token","wrong")
                .contentType(MediaType.APPLICATION_JSON).content("{\"versionNo\":1,\"type\":\"step_done\",\"step\":0}"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/internal/reviews/"+id+"/events").header("Authorization",internal).header("X-Run-Token",token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"versionNo\":1,\"type\":\"made_up\"}"))
            .andExpect(status().isBadRequest());
        for(String body:List.of("{\"versionNo\":1,\"type\":\"step_done\",\"step\":1}",
                "{\"versionNo\":1,\"type\":\"tool_started\",\"tool\":\"verify_patent\"}",
                "{\"versionNo\":1,\"type\":\"tool_finished\",\"tool\":\"verify_patent\",\"ok\":false}"))
            mvc.perform(post("/internal/reviews/"+id+"/events").header("Authorization",internal).header("X-Run-Token",token)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        assertThat(read(id).path("versions").get(0).path("steps").get(1).asInt()).isEqualTo(100);

        events.publish(id,1,"completed");
        String text=stream.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(text).contains("event:snapshot").contains("event:step_done").contains("\"step\":1")
            .contains("event:tool_started").contains("\"tool\":\"verify_patent\"").contains("\"ok\":false")
            .contains("event:completed").doesNotContain("approval_required");
        assertThat(events.subscriberCount(id)).isZero();

        // 다시 접속하면 Last-Event-ID 이후 이벤트만 다시 받는다.
        jdbc.update("UPDATE app.reviews SET document=jsonb_set(document,'{versions,0,status}','\"analyzing\"'::jsonb) WHERE id=CAST(? AS uuid)",id);
        long last=events.replay(id).get(events.replay(id).size()-2).id();
        var again=mvc.perform(get("/api/v1/reviews/"+id+"/events").with(as(AUTHOR)).header("Last-Event-ID",Long.toString(last)))
            .andExpect(request().asyncStarted()).andReturn();
        String replayed=again.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(replayed).contains("event:snapshot").contains("event:completed").doesNotContain("event:tool_started");
    }

    @Test
    void finishedReviewStreamSendsOnlyTheSnapshot()throws Exception{
        String id=reviewWithTwoFindings();
        var result=mvc.perform(get("/api/v1/reviews/"+id+"/events").with(as(REVIEWER))).andReturn();
        String text=result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(text).contains("event:snapshot").contains("\"violations\"");
        assertThat(events.subscriberCount(id)).isZero();
    }

    @Test
    void savedResultIsTheFinalReportWithoutApprovalOrAgentResume()throws Exception{
        clearInvocations(agent);
        String clean=review(),withFindings=review();
        violations.saveResult(TENANT,clean,1,result("complete","complete"));
        violations.saveResult(TENANT,withFindings,1,result("complete","complete",finding("text-0","주름 개선","low")));
        // 위반이 0건이어도 자동 승인은 없고, 재개 작업도 Agent 재개 호출도 없다.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.review_jobs WHERE review_id IN (CAST(? AS uuid),CAST(? AS uuid))",
            Integer.class,clean,withFindings)).isZero();
        verify(agent,after(300).never()).post(eq("/internal/runs/resume"),any(),any());
        verify(agent,never()).post(eq("/internal/runs/resume"),any());
        assertThat(jdbc.queryForObject("SELECT hitl_status FROM app.reviews WHERE id=CAST(? AS uuid)",String.class,clean)).isEqualTo("pending");
        JsonNode body=read(mvc.perform(get("/api/v1/reviews/"+clean).with(as(AUTHOR))).andExpect(status().isOk()));
        assertThat(body.path("hitl").path("violationCount").asInt()).isZero();
        assertThat(body.path("hitl").has("status")).isFalse();
        assertThat(body.path("versions").get(0).path("status").asText()).isEqualTo("done");
    }

    @Test
    void reviewDecisionAndReReviewEndpointsAreGone()throws Exception{
        String id=reviewWithTwoFindings();
        mvc.perform(postJson("/api/v1/reviews/"+id+"/decision",Map.of("versionNo",1,"state","approved","comment","ok")).with(as(REVIEWER)))
            .andExpect(status().isNotFound());
        var file=new MockMultipartFile("files","ad.png","image/png",new byte[]{(byte)0x89,0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A,1});
        mvc.perform(multipart("/api/v1/reviews/"+id+"/re-review").file(file).param("metadata","{\"product\":{\"name\":\"p\"}}").with(as(AUTHOR)))
            .andExpect(status().isNotFound());
    }

    @Test
    void legacyPendingResumeNoLongerKeepsTheStreamOpen()throws Exception{
        // 승인을 없애기 전에 저장된 문서에는 approval.agentResume=pending이 남아 있을 수 있다.
        String id=reviewWithTwoFindings();
        jdbc.update("UPDATE app.reviews SET document=jsonb_set(document,'{versions,0,approval,agentResume}','\"pending\"'::jsonb) WHERE id=CAST(? AS uuid)",id);
        var result=mvc.perform(get("/api/v1/reviews/"+id+"/events").with(as(AUTHOR))).andReturn();
        assertThat(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).contains("event:snapshot");
        assertThat(events.subscriberCount(id)).isZero();
    }

    /** 분석 중인 1버전 review를 만든다. */
    String review(){
        String id=UUID.randomUUID().toString();
        ObjectNode doc=json.createObjectNode().put("id",id).put("tenantId",TENANT).put("authorId",AUTHOR.id()).put("authorName","author").put("productName","p");
        ObjectNode v=doc.putArray("versions").addObject().put("no",1).put("status","analyzing");
        v.putObject("input").putArray("files");v.putNull("result");
        v.putObject("approval").put("state","pending").putArray("history");
        jdbc.update("INSERT INTO app.reviews(id,tenant_id,author_id,document) VALUES (CAST(? AS uuid),CAST(? AS uuid),CAST(? AS uuid),CAST(? AS jsonb))",id,TENANT,AUTHOR.id(),doc.toString());
        return id;
    }
    ObjectNode result(String text,String visual,ObjectNode... findings){
        ObjectNode result=json.createObjectNode();
        ArrayNode list=result.putArray("findings");for(ObjectNode f:findings)list.add(f);
        result.putArray("visualFindings");
        result.putObject("pipelineStatus").put("text",text).put("visual",visual);
        return (ObjectNode)json.createObjectNode().set("result",result);
    }
    ObjectNode finding(String id,String phrase,String severity,String... rewrites){
        ObjectNode f=json.createObjectNode().put("id",id).put("phrase",phrase).put("severity",severity).put("reason","r").put("claimType","wrinkle");
        ArrayNode r=f.putArray("rewrites");for(String s:rewrites)r.add(s);
        return f;
    }
    String reviewWithTwoFindings(){
        String id=review();
        violations.saveResult(TENANT,id,1,result("complete","complete",
            finding("text-0","7일 주름 개선","low","주름 개선 도움","주름 개선 기능성"),
            finding("text-1","100% 효과","CRITICAL")));
        return id;
    }

    @Test
    void agentResultBecomesViolationRowsLinkedToFindings()throws Exception{
        String id=reviewWithTwoFindings();
        JsonNode body=read(mvc.perform(get("/api/v1/reviews/"+id).with(as(AUTHOR))).andExpect(status().isOk()));
        JsonNode v=body.path("violations");
        assertThat(v).hasSize(2);
        assertThat(v.get(0).path("findingId").asText()).isEqualTo("text-0");
        assertThat(v.get(0).path("textExcerpt").asText()).isEqualTo("7일 주름 개선");
        assertThat(v.get(0).path("severity").asText()).isEqualTo("low");
        assertThat(v.get(0).path("rewriteSuggestion").asText()).isEqualTo("주름 개선 기능성");
        assertThat(v.get(0).path("rewriteAttemptCount").asInt()).isEqualTo(2);
        assertThat(v.get(0).path("rewrites").get(1).path("attemptNo").asInt()).isEqualTo(2);
        assertThat(v.get(0).path("rewrites").get(0).path("requestedByHuman").asBoolean()).isFalse();
        assertThat(v.get(1).path("severity").asText()).isEqualTo("medium");
        assertThat(v.get(1).path("rewriteSuggestion").isNull()).isTrue();
        assertThat(body.path("versions").get(0).path("result").path("findings").get(0).path("violationId").asText())
            .isEqualTo(v.get(0).path("id").asText());
        assertThat(body.path("hitl").path("violationCount").asInt()).isEqualTo(2);
        assertThat(body.path("hitl").path("undecidedCount").asInt()).isEqualTo(2);
    }

    @Test
    void violationsCanBeDecidedOneByOneAndChangedLater()throws Exception{
        String id=reviewWithTwoFindings();
        JsonNode v=read(mvc.perform(get("/api/v1/reviews/"+id).with(as(AUTHOR)))).path("violations");
        String first=v.get(0).path("id").asText(),second=v.get(1).path("id").asText();
        String base="/api/v1/reviews/"+id+"/violations/";

        mvc.perform(postJson(base+first+"/decision",Map.of("resolution","rewritten","attemptNo",1)).with(as(AUTHOR)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.violations[0].finalText").value("주름 개선 도움"))
            .andExpect(jsonPath("$.violations[0].finalTextValidated").value(true))
            .andExpect(jsonPath("$.violations[0].decidedBy").value(AUTHOR.id()))
            .andExpect(jsonPath("$.hitl.undecidedCount").value(1));
        // 사람이 쓴 문구는 Agent 재검증을 통과하기 전까지 저장하지 않는다(기본 mock은 passed=false).
        mvc.perform(postJson(base+second+"/decision",Map.of("resolution","rewritten_manual","finalText","효과적인 보습")).with(as(AUTHOR)))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("MANUAL_TEXT_FAILED_VERIFICATION"));
        mvc.perform(postJson(base+second+"/decision",Map.of("resolution","rewritten")).with(as(AUTHOR)))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("NO_REWRITE_SUGGESTION"));
        assertThat(jdbc.queryForObject("SELECT final_resolution FROM app.violations WHERE id=CAST(? AS uuid)",String.class,second)).isNull();

        // 검토자는 남의 검토에서도 문구를 고를 수 있다. 모두 결정해도 완료 상태는 따로 없다.
        mvc.perform(postJson(base+second+"/decision",Map.of("resolution","kept")).with(as(REVIEWER)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.violations[1].finalText").value("100% 효과"))
            .andExpect(jsonPath("$.hitl.undecidedCount").value(0))
            .andExpect(jsonPath("$.hitl.status").doesNotExist());
        // 결정은 잠기지 않는다. 나중에 다시 바꿀 수 있다.
        mvc.perform(postJson(base+first+"/decision",Map.of("resolution","kept")).with(as(AUTHOR)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.violations[0].finalResolution").value("kept"));
        verify(agent,never()).post(eq("/internal/runs/resume"),any(),any());
    }

    @Test
    void humanRewriteRequestsAreLimited()throws Exception{
        String id=reviewWithTwoFindings();
        String violation=read(mvc.perform(get("/api/v1/reviews/"+id).with(as(AUTHOR)))).path("violations").get(0).path("id").asText();
        String path="/api/v1/reviews/"+id+"/violations/"+violation+"/rewrite-requests";
        when(agent.post(eq("/internal/rewrites"),any())).thenReturn(json.createObjectNode().put("text","새 제안 A"),json.createObjectNode().put("text","새 제안 B"));

        mvc.perform(postJson(path,Map.of("retryNote","더 짧게")).with(as(AUTHOR)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.violations[0].rewriteSuggestion").value("새 제안 A"))
            .andExpect(jsonPath("$.violations[0].rewriteAttemptCount").value(3))
            .andExpect(jsonPath("$.violations[0].humanRetryCount").value(1))
            .andExpect(jsonPath("$.violations[0].rewrites[2].retryNote").value("더 짧게"))
            .andExpect(jsonPath("$.violations[0].rewrites[2].requestedByHuman").value(true));
        verify(agent).post(eq("/internal/rewrites"),argThat(c->c.path("previousRewrites").size()==2 && c.path("retryNote").asText().equals("더 짧게")));
        mvc.perform(postJson(path,Map.of()).with(as(REVIEWER))).andExpect(status().isOk());
        mvc.perform(postJson(path,Map.of()).with(as(REVIEWER)))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("HUMAN_REWRITE_LIMIT_REACHED"));
        verify(agent,times(2)).post(eq("/internal/rewrites"),any());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.violation_rewrites WHERE violation_id=CAST(? AS uuid) AND requested_by_human",Integer.class,violation)).isEqualTo(2);
    }

    @Test
    void manualRewriteIsSavedOnlyAfterAgentVerificationPasses()throws Exception{
        String id=reviewWithTwoFindings();
        String violation=read(mvc.perform(get("/api/v1/reviews/"+id).with(as(AUTHOR)))).path("violations").get(1).path("id").asText();
        String path="/api/v1/reviews/"+id+"/violations/"+violation+"/decision";
        when(agent.post(eq("/internal/rewrites/verify"),any())).thenReturn(json.createObjectNode().put("passed",true).put("reasoning","안전함"));

        mvc.perform(postJson(path,Map.of("resolution","rewritten_manual","finalText","효과적인 보습")).with(as(AUTHOR)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.violations[1].finalText").value("효과적인 보습"))
            .andExpect(jsonPath("$.violations[1].finalResolution").value("rewritten_manual"))
            .andExpect(jsonPath("$.violations[1].finalTextValidated").value(true));
        verify(agent).post(eq("/internal/rewrites/verify"),argThat(c->
            c.path("reviewId").asText().equals(id) && c.path("text").asText().equals("효과적인 보습") && c.path("reason").asText().equals("r")));
        assertThat(jdbc.queryForObject("SELECT final_text_validated FROM app.violations WHERE id=CAST(? AS uuid)",Boolean.class,violation)).isTrue();
    }

    @Test
    void manualRewriteVerificationFailureReportsConfigurationSeparatelyFromGenericFailure()throws Exception{
        String id=reviewWithTwoFindings();
        String violation=read(mvc.perform(get("/api/v1/reviews/"+id).with(as(AUTHOR)))).path("violations").get(1).path("id").asText();
        String path="/api/v1/reviews/"+id+"/violations/"+violation+"/decision";
        var ctor=AgentClient.AgentFailure.class.getDeclaredConstructor(int.class,String.class);ctor.setAccessible(true);
        when(agent.post(eq("/internal/rewrites/verify"),any())).thenThrow(ctor.newInstance(503,"TEXT_MODEL_NOT_CONFIGURED"));
        mvc.perform(postJson(path,Map.of("resolution","rewritten_manual","finalText","효과적인 보습")).with(as(AUTHOR)))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.message").value("REWRITE_MODEL_NOT_CONFIGURED"));
        assertThat(jdbc.queryForObject("SELECT final_resolution FROM app.violations WHERE id=CAST(? AS uuid)",String.class,violation)).isNull();
    }

    @Test
    void missingRewriteModelIsReportedAndNothingStored()throws Exception{
        String id=reviewWithTwoFindings();
        String violation=read(mvc.perform(get("/api/v1/reviews/"+id).with(as(AUTHOR)))).path("violations").get(1).path("id").asText();
        var ctor=AgentClient.AgentFailure.class.getDeclaredConstructor(int.class,String.class);ctor.setAccessible(true);
        when(agent.post(eq("/internal/rewrites"),any())).thenThrow(ctor.newInstance(503,"TEXT_MODEL_NOT_CONFIGURED"));
        mvc.perform(postJson("/api/v1/reviews/"+id+"/violations/"+violation+"/rewrite-requests",Map.of()).with(as(AUTHOR)))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.message").value("REWRITE_MODEL_NOT_CONFIGURED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.violation_rewrites WHERE violation_id=CAST(? AS uuid)",Integer.class,violation)).isZero();
    }

    @Test
    void persistedOriginalAssetIsServedOnlyToOwningTenant()throws Exception{
        String id=review();
        String assetId=UUID.randomUUID().toString();
        byte[] png={(byte)0x89,0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A,1,2,3,4};
        jdbc.update("INSERT INTO app.review_assets(id,review_id,version_no,name,mime_type,role,doc_type,size_bytes,sha256,bytes) "+
            "VALUES (CAST(? AS uuid),CAST(? AS uuid),1,'ad.png','image/png','advertisement','',?,'x',?)",assetId,id,png.length,png);

        var ok=mvc.perform(get("/api/v1/reviews/"+id+"/assets/"+assetId).with(as(AUTHOR))).andExpect(status().isOk())
            .andExpect(header().string("Content-Type","image/png")).andReturn();
        assertThat(ok.getResponse().getContentAsByteArray()).isEqualTo(png);

        mvc.perform(get("/api/v1/reviews/"+id+"/assets/"+assetId).with(as(OUTSIDER))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/reviews/"+id+"/assets/"+UUID.randomUUID()).with(as(AUTHOR))).andExpect(status().isNotFound());
    }

    @Test
    void everyNewPathIsTenantScoped()throws Exception{
        String id=reviewWithTwoFindings(),other=reviewWithTwoFindings();
        String violation=read(mvc.perform(get("/api/v1/reviews/"+id).with(as(AUTHOR)))).path("violations").get(0).path("id").asText();
        String base="/api/v1/reviews/"+id;
        mvc.perform(get(base).with(as(OUTSIDER))).andExpect(status().isNotFound());
        mvc.perform(postJson(base+"/violations/"+violation+"/decision",Map.of("resolution","kept")).with(as(OUTSIDER))).andExpect(status().isNotFound());
        mvc.perform(postJson(base+"/violations/"+violation+"/rewrite-requests",Map.of()).with(as(OUTSIDER))).andExpect(status().isNotFound());
        mvc.perform(postJson(base+"/decision",Map.of("versionNo",1,"state","rejected","comment","x")).with(as(OUTSIDER))).andExpect(status().isNotFound());
        // 다른 review의 violation id를 섞어 쓸 수 없다.
        mvc.perform(postJson("/api/v1/reviews/"+other+"/violations/"+violation+"/decision",Map.of("resolution","kept")).with(as(REVIEWER)))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("VIOLATION_NOT_FOUND"));
        // 같은 기업이어도 작성자가 아닌 author는 문구를 결정할 수 없다.
        mvc.perform(postJson(base+"/violations/"+violation+"/decision",Map.of("resolution","kept")).with(as(COLLEAGUE))).andExpect(status().isForbidden());
        mvc.perform(postJson(base+"/violations/not-a-uuid/decision",Map.of("resolution","kept")).with(as(REVIEWER))).andExpect(status().isBadRequest());
        JsonNode list=read(mvc.perform(get("/api/v1/reviews").with(as(OUTSIDER))));
        assertThat(list.findValuesAsText("id")).doesNotContain(id);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.violations WHERE final_resolution IS NOT NULL AND review_id=CAST(? AS uuid)",Integer.class,id)).isZero();
    }

    @Test
    void v5BackfillsLegacyJsonbFindings(){
        String id=review();
        ObjectNode doc=(ObjectNode)read(id);
        ObjectNode v=(ObjectNode)doc.withArray("versions").get(0);
        v.put("status","done");
        v.set("result",result("complete","complete",finding("text-0","기존 문구","high","a"," ","b")).path("result"));
        jdbc.update("UPDATE app.reviews SET document=CAST(? AS jsonb) WHERE id=CAST(? AS uuid)",doc.toString(),id);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V5__backfill_violations.sql")).execute(dataSource);
        var row=jdbc.queryForMap("SELECT id::text AS id,severity,rewrite_suggestion,rewrite_attempt_count FROM app.violations WHERE review_id=CAST(? AS uuid)",id);
        assertThat(row).containsEntry("severity","high").containsEntry("rewrite_suggestion","b").containsEntry("rewrite_attempt_count",2);
        assertThat(read(id).path("versions").get(0).path("result").path("findings").get(0).path("violationId").asText()).isEqualTo(row.get("id"));
    }
    JsonNode read(String id){
        try{return json.readTree(jdbc.queryForObject("SELECT document::text FROM app.reviews WHERE id=CAST(? AS uuid)",String.class,id));}
        catch(Exception e){throw new IllegalStateException(e);}
    }
}
