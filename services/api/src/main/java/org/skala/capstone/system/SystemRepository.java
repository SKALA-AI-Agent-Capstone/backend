package org.skala.capstone.system;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SystemRepository {
    private final JdbcTemplate jdbc;

    public SystemRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void ping() {
        jdbc.queryForObject("SELECT 1", Integer.class);
    }
}
