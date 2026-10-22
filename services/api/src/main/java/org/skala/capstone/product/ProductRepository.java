package org.skala.capstone.product;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** app.products. 모든 조회와 변경은 기업(tenant) 범위로 제한한다. 이미지 바이트는 목록과 상세 조회에서 읽지 않는다. */
@Repository
public class ProductRepository {
    private static final String SUMMARY =
            "SELECT p.id::text AS id, p.name, p.type, p.image_mime, p.image_sha256,"
                    + " p.image_thumbnail IS NOT NULL AS has_thumbnail, p.created_at, p.updated_at,"
                    + " count(r.id) AS review_count, max(r.updated_at) AS latest_review_at"
                    + " FROM app.products p LEFT JOIN app.reviews r ON r.product_id = p.id"
                    + " WHERE p.tenant_id = CAST(? AS uuid)";

    private final JdbcTemplate jdbc;

    public ProductRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 최근에 검토가 있었거나 수정된 제품부터. */
    public List<Map<String, Object>> list(String tenant) {
        return jdbc.queryForList(
                SUMMARY
                        + " GROUP BY p.id ORDER BY greatest(p.updated_at,"
                        + " coalesce(max(r.updated_at), p.updated_at)) DESC LIMIT 200",
                tenant);
    }

    public Optional<Map<String, Object>> find(String tenant, String id) {
        return jdbc
                .queryForList(SUMMARY + " AND p.id = CAST(? AS uuid) GROUP BY p.id", tenant, id)
                .stream()
                .findFirst();
    }

    public void create(
            String id,
            String tenant,
            String name,
            String type,
            ProductImage image,
            String createdBy) {
        jdbc.update(
                "INSERT INTO app.products (id, tenant_id, name, type, image, image_mime,"
                    + " image_sha256, image_thumbnail, created_by) VALUES (CAST(? AS uuid), CAST(?"
                    + " AS uuid), ?, ?, ?, ?, ?, ?, CAST(? AS uuid))",
                id,
                tenant,
                name,
                type,
                image == null ? null : image.bytes(),
                image == null ? null : image.mime(),
                image == null ? null : image.sha256(),
                image == null ? null : image.thumbnail(),
                createdBy);
    }

    /** 예전 검토 접수(제품 이름만 보낸 요청)용. 같은 이름의 제품이 있으면 그 id를, 없으면 새로 만든 id를 돌려준다. */
    public String findOrCreate(String tenant, String name, String createdBy) {
        return jdbc.queryForObject(
                "INSERT INTO app.products (id, tenant_id, name, created_by) VALUES"
                    + " (gen_random_uuid(), CAST(? AS uuid), ?, CAST(? AS uuid)) ON CONFLICT"
                    + " (tenant_id, name) DO UPDATE SET name = EXCLUDED.name RETURNING id::text",
                String.class,
                tenant,
                name,
                createdBy);
    }

    public Optional<String> name(String tenant, String id) {
        return jdbc
                .queryForList(
                        "SELECT name FROM app.products WHERE id = CAST(? AS uuid) AND tenant_id ="
                                + " CAST(? AS uuid)",
                        String.class,
                        id,
                        tenant)
                .stream()
                .findFirst();
    }

    public int update(String tenant, String id, String name, String type) {
        return jdbc.update(
                "UPDATE app.products SET name = ?, type = ?, updated_at = now() WHERE id = CAST(?"
                    + " AS uuid) AND tenant_id = CAST(? AS uuid)",
                name,
                type,
                id,
                tenant);
    }

    public int replaceImage(String tenant, String id, ProductImage image) {
        return jdbc.update(
                "UPDATE app.products SET image = ?, image_mime = ?, image_sha256 = ?,"
                    + " image_thumbnail = ?, updated_at = now() WHERE id = CAST(? AS uuid) AND"
                    + " tenant_id = CAST(? AS uuid)",
                image.bytes(),
                image.mime(),
                image.sha256(),
                image.thumbnail(),
                id,
                tenant);
    }

    /** thumbnail이 true면 썸네일만, 아니면 원본만 읽는다. 제품이 없으면 빈 목록이다. */
    public List<Map<String, Object>> image(String tenant, String id, boolean thumbnail) {
        return jdbc.queryForList(
                "SELECT "
                        + (thumbnail ? "image_thumbnail AS bytes" : "image AS bytes")
                        + ", image_mime FROM app.products WHERE id = CAST(? AS uuid) AND tenant_id"
                        + " = CAST(? AS uuid)",
                id,
                tenant);
    }

    /** 검토가 하나도 없는 제품만 지운다. 지운 행 수(0 또는 1)를 돌려준다. */
    public int deleteIfUnused(String tenant, String id) {
        return jdbc.update(
                "DELETE FROM app.products p WHERE p.id = CAST(? AS uuid) AND p.tenant_id = CAST(? AS"
                        + " uuid) AND NOT EXISTS (SELECT 1 FROM app.reviews r WHERE r.product_id ="
                        + " p.id)",
                id,
                tenant);
    }

    public record ProductImage(byte[] bytes, String mime, String sha256, byte[] thumbnail) {}
}
