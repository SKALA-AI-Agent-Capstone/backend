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
 * PaddleOCR가 한 번에 한 쪽씩만 돌기 때문에 일꾼은 하나이고, 대기열이 차면 바로 실패로 닫아 제품이 영영 읽는 중에 머물지 않게 한다.
 */
@Service
public class EvidenceReader implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(EvidenceReader.class);
    /** Agent의 판독 한도(280초)보다 길게. 이보다 오래 읽는 중인 행은 서버가 내려갔던 것으로 본다. */
    static final Duration AGENT_TIMEOUT = Duration.ofSeconds(295);
    static final int STALE_MINUTES = 10;

    private final EvidenceRepository evidence;
    private final AgentClient agent;
    private final ObjectMapper json;
    private final ExecutorService jobs =
            new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16),
                    Thread.ofPlatform().name("evidence-read-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    private volatile String readerVersion;
    private volatile long readerCheckedAt;
    private final long readerCacheMillis;

    public EvidenceReader(EvidenceRepository evidence, AgentClient agent, ObjectMapper json,
            @Value("${capstone.evidence.reader-cache-seconds:300}") long readerCacheSeconds) {
        this.readerCacheMillis = readerCacheSeconds * 1000;
        this.evidence = evidence;
        this.agent = agent;
        this.json = json;
    }

    public void submit(String tenant, String product, String id) {
        try {
            jobs.submit(() -> read(tenant, product, id));
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

    void read(String tenant, String product, String id) {
        try {
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
                    result.path("warnings").toString());
            // 읽는 사이 지워지거나 다시 시작된 행이면 저장되지 않았고, 개정본 판단도 하지 않는다.
            if (saved > 0) markRevisions(tenant, product, id, result);
        } catch (AgentClient.AgentFailure failure) {
            evidence.fail(id, failure.detail == null || failure.detail.isBlank() ? "EVIDENCE_READ_FAILED" : failure.detail);
        } catch (Exception failure) {
            log.warn("evidence read failed: {}", id, failure);
            evidence.fail(id, "EVIDENCE_READ_FAILED");
        }
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

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    void closeStale() {
        int closed = evidence.failStale(STALE_MINUTES);
        if (closed > 0) log.warn("{} evidence rows were reading for over {} minutes and were closed", closed, STALE_MINUTES);
    }

    @Override
    public void close() {
        jobs.shutdownNow();
    }
}
