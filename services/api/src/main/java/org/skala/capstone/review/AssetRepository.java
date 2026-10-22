package org.skala.capstone.review;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AssetRepository {
    private final JdbcTemplate jdbc;

    public AssetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void create(String assetId, String reviewId, int version, String name, String mime, String role,
            String docType, byte[] bytes, String hash) {
        jdbc.update("INSERT INTO app.review_assets(id,review_id,version_no,name,mime_type,role,doc_type,size_bytes,sha256,bytes) "
            + "VALUES (CAST(? AS uuid),CAST(? AS uuid),?,?,?,?,?,?,?,?)",
            assetId, reviewId, version, name, mime, role, docType, bytes.length, hash, bytes);
    }

    public List<Map<String, Object>> ids(String reviewId, int version) {
        return jdbc.queryForList("SELECT id FROM app.review_assets WHERE review_id=CAST(? AS uuid) AND version_no=?", reviewId, version);
    }

    public List<Map<String, Object>> persisted(String tenant, String reviewId, String assetId) {
        return jdbc.queryForList("SELECT a.bytes,a.mime_type FROM app.review_assets a JOIN app.reviews r ON r.id=a.review_id "
            + "WHERE a.id=CAST(? AS uuid) AND a.review_id=CAST(? AS uuid) AND r.tenant_id=CAST(? AS uuid)", assetId, reviewId, tenant);
    }

    /** 저장된 썸네일이 있으면 그것만, 없으면 썸네일을 만들 원본까지 읽는다. 기업 범위 밖이면 빈 목록이다. */
    public List<Map<String, Object>> thumbnailOrOriginal(String tenant, String reviewId, String assetId) {
        return jdbc.queryForList("SELECT a.thumbnail, CASE WHEN a.thumbnail IS NULL THEN a.bytes END AS bytes, a.mime_type "
            + "FROM app.review_assets a JOIN app.reviews r ON r.id=a.review_id "
            + "WHERE a.id=CAST(? AS uuid) AND a.review_id=CAST(? AS uuid) AND r.tenant_id=CAST(? AS uuid)",
            assetId, reviewId, tenant);
    }

    public void saveThumbnail(String assetId, byte[] thumbnail) {
        jdbc.update("UPDATE app.review_assets SET thumbnail=? WHERE id=CAST(? AS uuid) AND thumbnail IS NULL",
            thumbnail, assetId);
    }

    public List<Map<String, Object>> scopedMime(String reviewId, int version, String assetId) {
        return jdbc.queryForList("SELECT mime_type FROM app.review_assets "
            + "WHERE id=CAST(? AS uuid) AND review_id=CAST(? AS uuid) AND version_no=?", assetId, reviewId, version);
    }

    public List<Map<String, Object>> scopedOriginal(String reviewId, int version, String assetId) {
        return jdbc.queryForList("SELECT bytes FROM app.review_assets "
            + "WHERE id=CAST(? AS uuid) AND review_id=CAST(? AS uuid) AND version_no=?", assetId, reviewId, version);
    }
}
