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
     * Entities with their live vote counts. Parties first (fixed order), then the PM,
     * then all other members (Minister/MP/MLA) ranked by dissent votes descending
     * (ties broken by sort_order).
     */
    public List<EntityRow> listEntities() {
        return jdbc.query(
            "SELECT e.id, e.type, e.name, e.party_name, e.department, " +
            "       COALESCE(c.cnt, 0) AS votes " +
            "FROM entity e " +
            "LEFT JOIN (SELECT entity_id, COUNT(*) cnt FROM vote GROUP BY entity_id) c " +
            "       ON c.entity_id = e.id " +
            "ORDER BY CASE e.type WHEN 'PARTY' THEN 0 WHEN 'PM' THEN 1 ELSE 2 END, " +
            "         CASE WHEN e.type NOT IN ('PARTY','PM') THEN COALESCE(c.cnt, 0) END DESC, " +
            "         e.sort_order",
            (rs, i) -> new EntityRow(
                rs.getLong("id"),
                rs.getString("type"),
                rs.getString("name"),
                rs.getString("party_name"),
                rs.getString("department"),
                rs.getLong("votes")));
    }

    /** Reasons grouped (case-insensitive, trimmed) by how many people gave them, highest first. */
    public List<Issue> topIssues(int limit) {
        return jdbc.query(
            "SELECT INITCAP(TRIM(reason)) AS reason, COUNT(*) AS votes " +
            "FROM vote GROUP BY INITCAP(TRIM(reason)) ORDER BY votes DESC LIMIT ?",
            (rs, i) -> new Issue(rs.getString("reason"), rs.getLong("votes")),
            limit);
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

    /** One row per mobile; changing selection overwrites it. */
    public void upsertVote(String mobile, long entityId, String reason) {
        jdbc.update(
            "INSERT INTO vote (mobile, entity_id, reason, created_at) VALUES (?, ?, ?, now()) " +
            "ON CONFLICT (mobile) DO UPDATE SET entity_id = EXCLUDED.entity_id, " +
            "       reason = EXCLUDED.reason, created_at = now()",
            mobile, entityId, reason);
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
