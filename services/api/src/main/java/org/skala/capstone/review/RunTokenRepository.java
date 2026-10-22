package org.skala.capstone.review;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RunTokenRepository {
    private final JdbcTemplate jdbc;

    public RunTokenRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void create(String reviewId, int version, String hash) {
        jdbc.update("INSERT INTO app.review_runs VALUES (CAST(? AS uuid),?,?)", reviewId, version, hash);
    }

    public List<Map<String, Object>> find(String reviewId, int version) {
        return jdbc.queryForList("SELECT token_hash FROM app.review_runs WHERE review_id=CAST(? AS uuid) AND version_no=?", reviewId, version);
    }
}
