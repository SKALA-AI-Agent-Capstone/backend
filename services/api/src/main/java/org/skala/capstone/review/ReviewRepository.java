package org.skala.capstone.review;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import java.util.function.Consumer;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class ReviewRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public ReviewRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    private ObjectNode parse(String raw) {
        try {
            return (ObjectNode) json.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public ObjectNode get(String tenant, String id) {
        var rows =
                jdbc.queryForList(
                        "SELECT document::text FROM app.reviews WHERE id=CAST(? AS uuid) AND"
                            + " tenant_id=CAST(? AS uuid)",
                        id,
                        tenant);
        if (rows.isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND");
        return parse(rows.getFirst().get("document").toString());
    }

    public List<ObjectNode> list(String tenant) {
        return jdbc.query(
                "SELECT document::text FROM app.reviews WHERE tenant_id=CAST(? AS uuid) ORDER BY"
                    + " updated_at DESC LIMIT 200",
                (rs, n) -> parse(rs.getString(1)),
                tenant);
    }

    /** 한 제품의 광고 검토. 순서와 한도는 전체 목록과 같다. */
    public List<ObjectNode> listByProduct(String tenant, String productId) {
        return jdbc.query(
                "SELECT document::text FROM app.reviews WHERE tenant_id=CAST(? AS uuid) AND"
                    + " product_id=CAST(? AS uuid) ORDER BY updated_at DESC LIMIT 200",
                (rs, n) -> parse(rs.getString(1)),
                tenant,
                productId);
    }

    @Transactional
    public ObjectNode mutate(String tenant, String id, Consumer<ObjectNode> action) {
        var rows =
                jdbc.queryForList(
                        "SELECT document::text FROM app.reviews WHERE id=CAST(? AS uuid) AND"
                            + " tenant_id=CAST(? AS uuid) FOR UPDATE",
                        id,
                        tenant);
        if (rows.isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND");
        ObjectNode doc = parse(rows.getFirst().get("document").toString());
        action.accept(doc);
        doc.put("updatedAt", java.time.Instant.now().toString());
        jdbc.update(
                "UPDATE app.reviews SET document=CAST(? AS jsonb),updated_at=now() WHERE id=CAST(?"
                    + " AS uuid)",
                doc.toString(),
                id);
        return doc;
    }

    public void create(String id, String tenant, String author, String productId, ObjectNode doc) {
        jdbc.update(
                "INSERT INTO app.reviews(id,tenant_id,author_id,product_id,document) VALUES (CAST(?"
                    + " AS uuid),CAST(? AS uuid),CAST(? AS uuid),CAST(? AS uuid),CAST(? AS jsonb))",
                id,
                tenant,
                author,
                productId,
                doc.toString());
    }

    public void delete(String tenant, String id) {
        jdbc.update(
                "DELETE FROM app.reviews WHERE id=CAST(? AS uuid) AND tenant_id=CAST(? AS uuid)",
                id,
                tenant);
    }

    public String tenant(String id) {
        return jdbc.queryForObject(
                "SELECT tenant_id::text FROM app.reviews WHERE id=CAST(? AS uuid)",
                String.class,
                id);
    }

    public List<Map<String, Object>> lockAuthor(String tenant, String id) {
        return jdbc.queryForList(
                "SELECT author_id::text AS author_id FROM app.reviews WHERE id=CAST(? AS uuid) AND"
                    + " tenant_id=CAST(? AS uuid) FOR UPDATE",
                id,
                tenant);
    }
}
