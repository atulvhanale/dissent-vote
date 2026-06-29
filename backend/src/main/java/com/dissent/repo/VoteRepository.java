package com.dissent.repo;

import com.dissent.dto.Dtos.EntityRow;
import com.dissent.dto.Dtos.Issue;
import com.dissent.dto.Dtos.MySelection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

/** All database access for the app. Plain JDBC for a lean MVP. */
@Repository
public class VoteRepository {

    private final JdbcTemplate jdbc;

    public VoteRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Entities with their live vote counts. Important entities first — the governing party,
     * the opposition party, then the PM — in a fixed order (sort_order). Everyone else
     * (parties, ministers, MPs) follows, ranked by dissent votes descending.
     */
    public List<EntityRow> listEntities() {
        return jdbc.query(
            "SELECT e.id, e.type, e.name, e.party_name, e.department, e.important, " +
            "       COALESCE(c.cnt, 0) AS votes " +
            "FROM entity e " +
            "LEFT JOIN (SELECT entity_id, COUNT(*) cnt FROM vote GROUP BY entity_id) c " +
            "       ON c.entity_id = e.id " +
            "ORDER BY e.important DESC, " +                                  // important block on top
            "         CASE WHEN e.important THEN e.sort_order END, " +       // fixed order within it
            "         COALESCE(c.cnt, 0) DESC, " +                           // rest by dissent votes
            "         e.sort_order",
            (rs, i) -> new EntityRow(
                rs.getLong("id"),
                rs.getString("type"),
                rs.getString("name"),
                rs.getString("party_name"),
                rs.getString("department"),
                rs.getBoolean("important"),
                rs.getLong("votes")));
    }

    /** Issues for the left panel: grouped by the canonical bullet (falling back to raw reason). */
    public List<Issue> topIssues(int limit) {
        return jdbc.query(
            "SELECT COALESCE(NULLIF(TRIM(bullet), ''), INITCAP(TRIM(reason))) AS reason, " +
            "       COUNT(*) AS votes " +
            "FROM vote " +
            "GROUP BY COALESCE(NULLIF(TRIM(bullet), ''), INITCAP(TRIM(reason))) " +
            "ORDER BY votes DESC LIMIT ?",
            (rs, i) -> new Issue(rs.getString("reason"), rs.getLong("votes")),
            limit);
    }

    // ----- Bullets (canonical issue suggestions) -----

    /** All known bullets, most popular first (popularity = how many votes reference them). */
    public List<String> allBullets() {
        return jdbc.query(
            "SELECT b.text FROM bullet b " +
            "LEFT JOIN (SELECT bullet, COUNT(*) c FROM vote GROUP BY bullet) v ON v.bullet = b.text " +
            "ORDER BY COALESCE(v.c, 0) DESC, b.text",
            (rs, i) -> rs.getString("text"));
    }

    /** Bullets matching a typeahead query (case-insensitive substring), popular first. */
    public List<String> searchBullets(String q, int limit) {
        return jdbc.query(
            "SELECT b.text FROM bullet b " +
            "LEFT JOIN (SELECT bullet, COUNT(*) c FROM vote GROUP BY bullet) v ON v.bullet = b.text " +
            "WHERE b.text ILIKE ? " +
            "ORDER BY COALESCE(v.c, 0) DESC, b.text LIMIT ?",
            (rs, i) -> rs.getString("text"),
            "%" + q + "%", limit);
    }

    /** Inserts a bullet if it's new (case-insensitive); no-op if it already exists. */
    public void ensureBullet(String text) {
        jdbc.update("INSERT INTO bullet (text) VALUES (?) ON CONFLICT (text) DO NOTHING", text);
    }

    public boolean entityExists(long entityId) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM entity WHERE id = ?", Integer.class, entityId);
        return n != null && n > 0;
    }

    // ----- OTP -----

    public void upsertOtp(String mobile, String code, OffsetDateTime expiresAt) {
        jdbc.update(
            "INSERT INTO otp (mobile, code, expires_at) VALUES (?, ?, ?) " +
            "ON CONFLICT (mobile) DO UPDATE SET code = EXCLUDED.code, expires_at = EXCLUDED.expires_at",
            mobile, code, expiresAt);
    }

    /** Returns true if a non-expired OTP matches. */
    public boolean otpValid(String mobile, String code) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM otp WHERE mobile = ? AND code = ? AND expires_at > now()",
            Integer.class, mobile, code);
        return n != null && n > 0;
    }

    public void deleteOtp(String mobile) {
        jdbc.update("DELETE FROM otp WHERE mobile = ?", mobile);
    }

    // ----- Vote token -----

    public void insertToken(String token, String mobile, OffsetDateTime expiresAt) {
        jdbc.update("INSERT INTO vote_token (token, mobile, expires_at) VALUES (?, ?, ?)",
            token, mobile, expiresAt);
    }

    /** Returns the mobile bound to a still-valid token, or null. */
    public String mobileForValidToken(String token) {
        return jdbc.query(
            "SELECT mobile FROM vote_token WHERE token = ? AND expires_at > now()",
            rs -> rs.next() ? rs.getString("mobile") : null,
            token);
    }

    // ----- Vote -----

    /** One row per mobile; changing selection overwrites it. {@code bullet} may be null. */
    public void upsertVote(String mobile, long entityId, String reason, String bullet) {
        jdbc.update(
            "INSERT INTO vote (mobile, entity_id, reason, bullet, created_at) " +
            "VALUES (?, ?, ?, ?, now()) " +
            "ON CONFLICT (mobile) DO UPDATE SET entity_id = EXCLUDED.entity_id, " +
            "       reason = EXCLUDED.reason, bullet = EXCLUDED.bullet, created_at = now()",
            mobile, entityId, reason, bullet);
    }

    public MySelection findSelection(String mobile) {
        return jdbc.query(
            "SELECT v.entity_id, e.name, e.type, v.reason, v.created_at " +
            "FROM vote v JOIN entity e ON e.id = v.entity_id WHERE v.mobile = ?",
            rs -> rs.next()
                ? new MySelection(
                    rs.getLong("entity_id"),
                    rs.getString("name"),
                    rs.getString("type"),
                    rs.getString("reason"),
                    rs.getObject("created_at", OffsetDateTime.class))
                : null,
            mobile);
    }
}
