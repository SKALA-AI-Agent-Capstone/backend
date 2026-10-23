package org.skala.capstone.evidence;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** app.product_evidence. 조회와 변경은 모두 기업(tenant) 범위로 제한하고, 목록과 상세에서는 파일 바이트와 쪽별 본문을 읽지 않는다. */
@Repository
public class EvidenceRepository {
    private static final String VIEW =
            "SELECT id::text AS id, product_id::text AS product_id, doc_type, file_name, file_mime, file_size,"
                    + " file_sha256, status, failure_code, reader_version, read_started_at, read_finished_at,"
                    + " issuer_key, issuer_name, codes::text AS codes, version_label, issued_on, page_count,"
                    + " unread_pages::text AS unread_pages, report_products::text AS report_products,"
                    + " product_match::text AS product_match, warnings::text AS warnings, is_current,"
                    + " supersedes::text AS supersedes, change_note, created_at, updated_at"
                    + " FROM app.product_evidence WHERE tenant_id = CAST(? AS uuid)";

    private final JdbcTemplate jdbc;

    public EvidenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 현재 판이 먼저, 그다음 최근 순. */
    public List<Map<String, Object>> list(String tenant, String product) {
        return jdbc.queryForList(
                VIEW + " AND product_id = CAST(? AS uuid) ORDER BY is_current DESC, created_at DESC", tenant, product);
    }

    public Optional<Map<String, Object>> find(String tenant, String product, String id) {
        return jdbc
                .queryForList(VIEW + " AND product_id = CAST(? AS uuid) AND id = CAST(? AS uuid)", tenant, product, id)
                .stream()
                .findFirst();
    }

    public Optional<Map<String, Object>> findByHash(String tenant, String product, String sha256) {
        return jdbc
                .queryForList(VIEW + " AND product_id = CAST(? AS uuid) AND file_sha256 = ?", tenant, product, sha256)
                .stream()
                .findFirst();
    }

    public void insert(String id, String tenant, String product, String docType, String name, String mime,
            byte[] bytes, String sha256, String createdBy) {
        jdbc.update(
                "INSERT INTO app.product_evidence (id, tenant_id, product_id, doc_type, file_name, file_mime,"
                        + " file_size, file_sha256, file_bytes, created_by) VALUES (CAST(? AS uuid), CAST(? AS uuid),"
                        + " CAST(? AS uuid), ?, ?, ?, ?, ?, ?, CAST(? AS uuid))",
                id, tenant, product, docType, name, mime, bytes.length, sha256, bytes, createdBy);
    }

    /** 다시 읽기: 이전 결과를 비우고 읽는 중으로 돌린다. 이미 읽는 중이면 0을 돌려준다. */
    public int restart(String tenant, String id) {
        return jdbc.update(
                "UPDATE app.product_evidence SET status = 'reading', failure_code = NULL, read_started_at = now(),"
                        + " read_finished_at = NULL, updated_at = now() WHERE tenant_id = CAST(? AS uuid)"
                        + " AND id = CAST(? AS uuid) AND status <> 'reading'",
                tenant, id);
    }

    public Optional<Map<String, Object>> file(String id) {
        return jdbc
                .queryForList("SELECT file_bytes, file_mime FROM app.product_evidence WHERE id = CAST(? AS uuid)", id)
                .stream()
                .findFirst();
    }

    /** Agent가 돌려준 판독 결과를 저장한다. 읽는 중이 아닌 행(그 사이 지워졌거나 다시 시작된 행)은 바꾸지 않는다. */
    public int saveResult(String id, String readerVersion, String issuerKey, String issuerName, String codes,
            String versionLabel, String issuedOn, int pageCount, String pages, String unreadPages,
            String reportProducts, String productMatch, String warnings) {
        return jdbc.update(
                "UPDATE app.product_evidence SET status = 'done', failure_code = NULL, reader_version = ?,"
                        + " issuer_key = ?, issuer_name = ?, codes = CAST(? AS jsonb), version_label = ?, issued_on = ?,"
                        + " page_count = ?, pages = CAST(? AS jsonb), unread_pages = CAST(? AS jsonb),"
                        + " report_products = CAST(? AS jsonb), product_match = CAST(? AS jsonb),"
                        + " warnings = CAST(? AS jsonb), read_finished_at = now(), updated_at = now()"
                        + " WHERE id = CAST(? AS uuid) AND status = 'reading'",
                readerVersion, issuerKey, issuerName, codes, versionLabel, issuedOn, pageCount, pages, unreadPages,
                reportProducts, productMatch, warnings, id);
    }

    public int fail(String id, String code) {
        return jdbc.update(
                "UPDATE app.product_evidence SET status = 'failed', failure_code = ?, read_finished_at = now(),"
                        + " updated_at = now() WHERE id = CAST(? AS uuid) AND status = 'reading'",
                code, id);
    }

    /** 서버가 내려갔다 올라와 아무도 읽고 있지 않은 채 남은 행을 실패로 닫는다. */
    public int failStale(int minutes) {
        return jdbc.update(
                "UPDATE app.product_evidence SET status = 'failed', failure_code = 'READ_INTERRUPTED',"
                        + " read_finished_at = now(), updated_at = now() WHERE status = 'reading'"
                        + " AND read_started_at < now() - make_interval(mins => ?)",
                minutes);
    }

    /** 같은 제품에서 같은 기관·같은 코드로 이미 읽힌 다른 파일(현재 판). */
    public List<Map<String, Object>> sameCode(String tenant, String product, String id, String issuerKey, String label,
            String code) {
        return jdbc.queryForList(
                "SELECT id::text AS id, version_label, issued_on, file_name FROM app.product_evidence"
                        + " WHERE tenant_id = CAST(? AS uuid) AND product_id = CAST(? AS uuid) AND id <> CAST(? AS uuid)"
                        + " AND status = 'done' AND is_current AND issuer_key = ?"
                        + " AND codes @> CAST(? AS jsonb) ORDER BY created_at DESC",
                tenant, product, id, issuerKey, "[{\"label\":" + quote(label) + ",\"value\":" + quote(code) + "}]");
    }

    public void supersede(String oldId, String newId, String note) {
        jdbc.update("UPDATE app.product_evidence SET is_current = false, updated_at = now() WHERE id = CAST(? AS uuid)", oldId);
        jdbc.update(
                "UPDATE app.product_evidence SET supersedes = CAST(? AS uuid), change_note = ?, updated_at = now()"
                        + " WHERE id = CAST(? AS uuid)",
                oldId, note, newId);
    }

    public int delete(String tenant, String product, String id) {
        return jdbc.update(
                "DELETE FROM app.product_evidence WHERE tenant_id = CAST(? AS uuid) AND product_id = CAST(? AS uuid)"
                        + " AND id = CAST(? AS uuid)",
                tenant, product, id);
    }

    /** 검토가 속한 제품의 현재 판 중 읽기가 끝난 증빙. 검토는 파일을 다시 읽지 않고 이 결과를 쓴다. 쪽별 글자(pages)를 함께 준다. */
    public List<Map<String, Object>> readyForReview(String reviewId) {
        return jdbc.queryForList(
                "SELECT e.id::text AS id, e.doc_type, e.file_name, e.issuer_key, e.issuer_name, e.codes::text AS codes,"
                        + " e.version_label, e.page_count, e.unread_pages::text AS unread_pages,"
                        + " e.report_products::text AS report_products, e.product_match::text AS product_match,"
                        + " e.pages::text AS pages, e.reader_version FROM app.product_evidence e"
                        + " JOIN app.reviews r ON r.product_id = e.product_id AND r.tenant_id = e.tenant_id"
                        + " WHERE r.id = CAST(? AS uuid) AND e.status = 'done' AND e.is_current ORDER BY e.created_at",
                reviewId);
    }

    public int readyCount(String tenant, String product) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM app.product_evidence WHERE tenant_id = CAST(? AS uuid) AND product_id = CAST(? AS uuid)"
                        + " AND status = 'done' AND is_current",
                Integer.class, tenant, product);
    }

    public boolean reading(String tenant, String product) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM app.product_evidence WHERE tenant_id = CAST(? AS uuid)"
                                + " AND product_id = CAST(? AS uuid) AND status = 'reading')",
                        Boolean.class, tenant, product));
    }

    /** 증빙이 속한 기업과 제품, 현재 상태. 큐 소비자는 id만 받으므로 여기서 찾는다. 지워졌으면 빈 값이다. */
    public Optional<Map<String, Object>> owner(String id) {
        return jdbc
                .queryForList(
                        "SELECT tenant_id::text AS tenant, product_id::text AS product, status FROM app.product_evidence"
                                + " WHERE id = CAST(? AS uuid)",
                        id)
                .stream()
                .findFirst();
    }

    public Optional<String> productName(String tenant, String product) {
        return jdbc
                .queryForList("SELECT name FROM app.products WHERE tenant_id = CAST(? AS uuid) AND id = CAST(? AS uuid)",
                        String.class, tenant, product)
                .stream()
                .findFirst();
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
