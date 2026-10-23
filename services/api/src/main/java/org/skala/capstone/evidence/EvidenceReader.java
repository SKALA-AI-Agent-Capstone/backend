package org.skala.capstone.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.skala.capstone.common.AgentClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 증빙을 Agent에게 읽히고 결과를 저장한다. 읽는 동안 행은 status=reading이고, 그동안 그 제품의 검토 접수는 막힌다.
 * 큐가 켜져 있으면(운영) 작업은 outbox와 RabbitMQ를 거쳐 EvidenceJobQueue가 가져가고, 꺼져 있으면(테스트, 단독 실행) 이 서비스의
 * 스레드 하나가 읽는다. 어느 쪽이든 PaddleOCR가 한 번에 한 쪽씩만 돌기 때문에 읽는 일꾼은 하나다.
 */
@Service
public class EvidenceReader implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(EvidenceReader.class);
    /** Agent의 판독 한도(280초)보다 길게. */
    static final Duration AGENT_TIMEOUT = Duration.ofSeconds(295);
    /** 큐 없이 읽는 경우에만 쓴다: 이보다 오래 reading인 행은 서버가 내려갔던 것으로 본다. */
    static final int STALE_MINUTES = 10;

    private final EvidenceRepository evidence;
    private final EvidenceJobRepository jobs;
    private final AgentClient agent;
    private final ObjectMapper json;
    private final boolean queueEnabled;
    private final long readerCacheMillis;
    private final ExecutorService inline =
            new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16),
                    Thread.ofPlatform().name("evidence-read-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    private volatile String readerVersion;
    private volatile long readerCheckedAt;

    public EvidenceReader(EvidenceRepository evidence, EvidenceJobRepository jobs, AgentClient agent, ObjectMapper json,
            @Value("${capstone.review.queue-enabled:false}") boolean queueEnabled,
            @Value("${capstone.evidence.reader-cache-seconds:300}") long readerCacheSeconds) {
        this.evidence = evidence;
        this.jobs = jobs;
        this.agent = agent;
        this.json = json;
        this.queueEnabled = queueEnabled;
        this.readerCacheMillis = readerCacheSeconds * 1000;
    }

    /** 읽기를 맡긴다. 행은 이미 reading으로 저장돼 있다. */
    public void submit(String id) {
        if (queueEnabled) {
            jobs.create(id);
            return;
        }
        try {
            inline.submit(() -> readInline(id));
        } catch (RejectedExecutionException busy) {
            evidence.fail(id, "READER_BUSY");
        }
    }

    /** Agent가 지금 쓰는 판독기 버전. 잠시(기본 5분) 기억하고, Agent에 닿지 않으면 null이다(저장된 결과를 그대로 믿는다). */
    public String currentReaderVersion() {
        long now = System.currentTimeMillis();
        if (readerVersion != null && now - readerCheckedAt < readerCacheMillis) return readerVersion;
        try {
            readerVersion = agent.get("/internal/evidence/reader").path("readerVersion").asText(null);
            readerCheckedAt = now;
        } catch (Exception unavailable) {
            return null;
        }
        return readerVersion;
    }

    private void readInline(String id) {
        try {
            readOnce(id);
        } catch (Exception failure) {
            if (!(failure instanceof AgentClient.AgentFailure)) log.warn("evidence read failed: {}", id, failure);
            evidence.fail(id, failureCode(failure));
        }
    }

    /** 한 번 읽고 저장한다. 실패는 그대로 던진다(재시도할지는 부른 쪽이 정한다). 그 사이 지워진 증빙은 조용히 넘어간다. */
    public void readOnce(String id) throws Exception {
        var owner = evidence.owner(id);
        if (owner.isEmpty()) return;
        String tenant = owner.get().get("tenant").toString(), product = owner.get().get("product").toString();
        String name = evidence.productName(tenant, product).orElse(null);
        ObjectNode body = json.createObjectNode().put("evidenceId", id);
        if (name != null) body.put("productName", name);
        JsonNode result = agent.post("/internal/evidence/parse", body, AGENT_TIMEOUT);
        JsonNode issuer = result.path("issuer");
        int saved = evidence.saveResult(id, result.path("readerVersion").asText(null),
                issuer.path("key").asText(null), issuer.path("name").asText(null), result.path("codes").toString(),
                result.path("version").asText(null), result.path("issuedOn").asText(null),
                result.path("pageCount").asInt(), result.path("pages").toString(),
                result.path("unreadPages").toString(), result.path("products").toString(),
                result.hasNonNull("productMatch") ? result.path("productMatch").toString() : null,
                result.path("warnings").toString(), result.path("checkPages").isArray() ? result.path("checkPages").toString() : "[]");
        // 읽는 사이 지워지거나 다시 시작된 행이면 저장되지 않았고, 개정본 판단도 하지 않는다.
        if (saved > 0) markRevisions(tenant, product, id, result);
    }

    /** 다시 해도 같은 결과일 오류(잘못된 파일, 없는 파일)는 재시도하지 않는다. 시간 초과와 5xx, 연결 오류는 다시 해 볼 만하다. */
    static boolean permanent(Exception failure) {
        return failure instanceof AgentClient.AgentFailure f && f.status >= 400 && f.status < 500 && f.status != 429;
    }

    static String failureCode(Exception failure) {
        if (failure instanceof AgentClient.AgentFailure f && f.detail != null && !f.detail.isBlank()) return f.detail;
        return "EVIDENCE_READ_FAILED";
    }

    /** 같은 기관의 같은 코드인데 파일이 다르면 개정본이다. 예전 행은 지우지 않고 현재 판에서만 내린다. */
    private void markRevisions(String tenant, String product, String id, JsonNode result) {
        String issuer = result.path("issuer").path("key").asText("");
        if (issuer.isEmpty()) return;
        for (JsonNode code : result.path("codes")) {
            String status = code.path("status").asText();
            if (!status.equals("confirmed") && !status.equals("single_page")) continue;
            String label = code.path("label").asText(), value = code.path("value").asText();
            for (var older : evidence.sameCode(tenant, product, id, issuer, label, value)) {
                evidence.supersede(older.get("id").toString(), id, note(value, older, result));
            }
            return;
        }
    }

    private static String note(String code, java.util.Map<String, Object> older, JsonNode result) {
        String before = String.valueOf(older.get("version_label")), after = result.path("version").asText("");
        if (!before.equals("null") && !after.isEmpty() && !before.equals(after)) {
            return "같은 코드 " + code + "의 개정본입니다. " + before + " → " + after;
        }
        return "같은 코드 " + code + "의 다른 파일입니다. 내용이 달라져 다시 읽었습니다.";
    }

    /** 큐 없이 읽을 때만: 서버가 멈춘 채 reading으로 남은 행을 닫는다. 큐가 있으면 작업 재시도가 그 일을 한다. */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    void closeStale() {
        if (queueEnabled) return;
        int closed = evidence.failStale(STALE_MINUTES);
        if (closed > 0) log.warn("{} evidence rows were reading for over {} minutes and were closed", closed, STALE_MINUTES);
    }

    @Override
    public void close() {
        inline.shutdownNow();
    }
}
