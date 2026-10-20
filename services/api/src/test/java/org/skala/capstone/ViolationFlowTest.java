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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
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
    @Autowired DataSource dataSource;
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

    RequestPostProcessor as(User u){return jwt().jwt(j->j.subject(u.id()).claim("tenantId",u.tenant()).claim("role",u.role()));}
    MockHttpServletRequestBuilder postJson(String path,Object body)throws Exception{
        return post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
    }
    JsonNode read(org.springframework.test.web.servlet.ResultActions r)throws Exception{
        return json.readTree(r.andReturn().getResponse().getContentAsString());
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
        assertThat(body.path("hitl").path("status").asText()).isEqualTo("pending");
        assertThat(body.path("hitl").path("undecidedCount").asInt()).isEqualTo(2);
        assertThat(body.path("hitl").path("canApprove").asBoolean()).isFalse();
    }

    @Test
    void zeroViolationsAutoApproveOnlyWhenBothPipelinesCompleted()throws Exception{
        String clean=review(),unconfigured=review();
        assertThat(violations.saveResult(TENANT,clean,1,result("complete","complete"))).isTrue();
        assertThat(violations.saveResult(TENANT,unconfigured,1,result("not_configured","not_configured"))).isFalse();
        var row=jdbc.queryForMap("SELECT hitl_status,decided_by,decided_at FROM app.reviews WHERE id=CAST(? AS uuid)",clean);
        assertThat(row.get("hitl_status")).isEqualTo("approved");
        assertThat(row.get("decided_by")).isNull();
        assertThat(row.get("decided_at")).isNotNull();
        JsonNode body=read(mvc.perform(get("/api/v1/reviews/"+clean).with(as(REVIEWER))));
        assertThat(body.path("hitl").path("autoApproved").asBoolean()).isTrue();
        assertThat(body.path("versions").get(0).path("approval").path("state").asText()).isEqualTo("approved");
        assertThat(jdbc.queryForObject("SELECT hitl_status FROM app.reviews WHERE id=CAST(? AS uuid)",String.class,unconfigured)).isEqualTo("pending");
        mvc.perform(postJson("/api/v1/reviews/"+clean+"/decision",Map.of("versionNo",1,"state","rejected","comment","c")).with(as(REVIEWER)))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("ALREADY_DECIDED"));
    }

    @Test
    void approvalRequiresEveryViolationResolved()throws Exception{
        String id=reviewWithTwoFindings();
        JsonNode v=read(mvc.perform(get("/api/v1/reviews/"+id).with(as(AUTHOR)))).path("violations");
        String first=v.get(0).path("id").asText(),second=v.get(1).path("id").asText();
        String base="/api/v1/reviews/"+id+"/violations/";

        mvc.perform(postJson(base+first+"/decision",Map.of("resolution","rewritten","attemptNo",1)).with(as(AUTHOR)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.violations[0].finalText").value("주름 개선 도움"))
            .andExpect(jsonPath("$.violations[0].finalTextValidated").value(true))
            .andExpect(jsonPath("$.violations[0].decidedBy").value(AUTHOR.id()));
        // 사람이 쓴 문구는 Agent 재검증을 통과하기 전까지 저장하지 않는다(기본 mock은 passed=false).
        mvc.perform(postJson(base+second+"/decision",Map.of("resolution","rewritten_manual","finalText","효과적인 보습")).with(as(AUTHOR)))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("MANUAL_TEXT_FAILED_VERIFICATION"));
        mvc.perform(postJson(base+second+"/decision",Map.of("resolution","rewritten")).with(as(AUTHOR)))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("NO_REWRITE_SUGGESTION"));
        assertThat(jdbc.queryForObject("SELECT final_resolution FROM app.violations WHERE id=CAST(? AS uuid)",String.class,second)).isNull();

        mvc.perform(postJson("/api/v1/reviews/"+id+"/decision",Map.of("versionNo",1,"state","approved","comment","ok")).with(as(REVIEWER)))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("VIOLATIONS_UNDECIDED"));

        mvc.perform(postJson(base+second+"/decision",Map.of("resolution","kept")).with(as(REVIEWER)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.violations[1].finalText").value("100% 효과"))
            .andExpect(jsonPath("$.hitl.canApprove").value(true));
        // 작성자는 문구를 고를 수 있지만 전체 승인은 할 수 없다.
        mvc.perform(postJson("/api/v1/reviews/"+id+"/decision",Map.of("versionNo",1,"state","approved","comment","ok")).with(as(AUTHOR)))
            .andExpect(status().isForbidden());
        mvc.perform(postJson("/api/v1/reviews/"+id+"/decision",Map.of("versionNo",1,"state","approved","comment","ok")).with(as(REVIEWER)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.hitl.status").value("approved"))
            .andExpect(jsonPath("$.hitl.decidedBy").value(REVIEWER.id()))
            .andExpect(jsonPath("$.versions[0].approval.state").value("approved"));
        var row=jdbc.queryForMap("SELECT hitl_status,hitl_comment,decided_by::text AS by FROM app.reviews WHERE id=CAST(? AS uuid)",id);
        assertThat(row).containsEntry("hitl_status","approved").containsEntry("hitl_comment","ok").containsEntry("by",REVIEWER.id());
        verify(agent).post(eq("/internal/runs/resume"),argThat(c->c.path("state").asText().equals("approved")));
        // 결정 이후에는 문구도 잠긴다.
        mvc.perform(postJson(base+second+"/decision",Map.of("resolution","kept")).with(as(REVIEWER)))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("ALREADY_DECIDED"));
    }

    @Test
    void changesRequestedIsStoredAsRevisionRequested()throws Exception{
        String id=reviewWithTwoFindings();
        mvc.perform(postJson("/api/v1/reviews/"+id+"/decision",Map.of("versionNo",1,"state","changes_requested","comment","fix")).with(as(REVIEWER)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.hitl.status").value("revision_requested"))
            .andExpect(jsonPath("$.versions[0].approval.state").value("changes_requested"));
        verify(agent).post(eq("/internal/runs/resume"),argThat(c->c.path("state").asText().equals("changes_requested")));
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
    void reReviewClearsViolationsAndDecision(){
        String id=reviewWithTwoFindings();
        jdbc.update("UPDATE app.reviews SET hitl_status='rejected',decided_at=now() WHERE id=CAST(? AS uuid)",id);
        violations.resetForNewVersion(id);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.violations WHERE review_id=CAST(? AS uuid)",Integer.class,id)).isZero();
        assertThat(jdbc.queryForObject("SELECT hitl_status FROM app.reviews WHERE id=CAST(? AS uuid)",String.class,id)).isEqualTo("pending");
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
