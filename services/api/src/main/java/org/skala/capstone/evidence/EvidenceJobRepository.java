package org.skala.capstone.evidence;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** app.evidence_jobs. 증빙 하나에 작업 하나이고, 다시 읽기는 같은 행을 처음 상태로 되돌린다. */
@Repository
public class EvidenceJobRepository {
    private final JdbcTemplate jdbc;

    public EvidenceJobRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void create(String evidenceId) {
        jdbc.update(
                "INSERT INTO app.evidence_jobs (evidence_id) VALUES (CAST(? AS uuid)) ON CONFLICT (evidence_id) DO UPDATE"
                        + " SET status = 'pending', attempts = 0, next_attempt_at = now(), updated_at = now(), last_error = NULL",
                evidenceId);
    }

    public List<String> ready() {
        return jdbc.queryForList(
                "SELECT evidence_id::text FROM app.evidence_jobs WHERE status IN ('pending', 'retry')"
                        + " AND next_attempt_at <= now() ORDER BY next_attempt_at LIMIT 20",
                String.class);
    }

    public void published(String id) {
        jdbc.update(
                "UPDATE app.evidence_jobs SET status = 'published', updated_at = now() WHERE evidence_id = CAST(? AS uuid)"
                        + " AND status IN ('pending', 'retry')",
                id);
    }

    /** 가져간 작업의 시도 횟수. 이미 끝났거나 지워졌으면 빈 목록이다. */
    public List<Map<String, Object>> claim(String id) {
        return jdbc.queryForList(
                "UPDATE app.evidence_jobs SET status = 'running', attempts = attempts + 1, updated_at = now()"
                        + " WHERE evidence_id = CAST(? AS uuid) AND status IN ('pending', 'published', 'retry')"
                        + " AND next_attempt_at <= now() RETURNING attempts",
                id);
    }

    public void retry(String id, int delaySeconds, String error) {
        jdbc.update(
                "UPDATE app.evidence_jobs SET status = 'retry', updated_at = now(),"
                        + " next_attempt_at = now() + (? * interval '1 second'), last_error = ? WHERE evidence_id = CAST(? AS uuid)",
                delaySeconds, error, id);
    }

    public void failed(String id, String error) {
        jdbc.update(
                "UPDATE app.evidence_jobs SET status = 'failed', updated_at = now(), last_error = ? WHERE evidence_id = CAST(? AS uuid)",
                error, id);
    }

    public void done(String id) {
        jdbc.update(
                "UPDATE app.evidence_jobs SET status = 'done', updated_at = now(), last_error = NULL WHERE evidence_id = CAST(? AS uuid)",
                id);
    }

    /** 소비자가 죽어 running으로 남은 작업. Agent 호출 한도(295초)보다 충분히 길게 기다린다. */
    public List<Map<String, Object>> stale() {
        return jdbc.queryForList(
                "SELECT evidence_id::text AS id, attempts FROM app.evidence_jobs WHERE status = 'running'"
                        + " AND updated_at < now() - interval '7 minutes' LIMIT 20");
    }

    public void recover(String id, String status) {
        jdbc.update(
                "UPDATE app.evidence_jobs SET status = ?, next_attempt_at = now(), updated_at = now()"
                        + " WHERE evidence_id = CAST(? AS uuid) AND status = 'running' AND updated_at < now() - interval '7 minutes'",
                status, id);
    }
}
