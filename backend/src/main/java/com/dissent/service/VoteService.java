package com.dissent.service;

import com.dissent.dto.Dtos.EntityRow;
import com.dissent.dto.Dtos.Issue;
import com.dissent.dto.Dtos.MySelection;
import com.dissent.repo.VoteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;

/** Business rules: OTP issue/verify, token issue, and one-vote-per-mobile casting. */
@Service
public class VoteService {

    private static final Logger log = LoggerFactory.getLogger(VoteService.class);
    private static final SecureRandom RNG = new SecureRandom();

    private final VoteRepository repo;
    private final int otpValidityMinutes;
    private final int tokenValidityMinutes;

    public VoteService(VoteRepository repo,
                       @Value("${app.otp.validity-minutes}") int otpValidityMinutes,
                       @Value("${app.token.validity-minutes}") int tokenValidityMinutes) {
        this.repo = repo;
        this.otpValidityMinutes = otpValidityMinutes;
        this.tokenValidityMinutes = tokenValidityMinutes;
    }

    public List<EntityRow> listEntities() {
        return repo.listEntities();
    }

    public List<Issue> topIssues() {
        return repo.topIssues(10);
    }

    /** Generates a 6-digit OTP, stores it, and (mock) prints it to the server console. */
    public void sendOtp(String mobile) {
        String code = String.format("%06d", RNG.nextInt(1_000_000));
        repo.upsertOtp(mobile, code, OffsetDateTime.now().plusMinutes(otpValidityMinutes));
        log.info("=== MOCK OTP for {} is {} (valid {} min) ===", mobile, code, otpValidityMinutes);
    }

    /** Verifies OTP and, on success, issues a time-bound vote token. Returns token or null. */
    @Transactional
    public String verifyOtp(String mobile, String code) {
        if (!repo.otpValid(mobile, code)) {
            return null;
        }
        repo.deleteOtp(mobile);
        String token = HexFormat.of().formatHex(randomBytes());
        repo.insertToken(token, mobile, OffsetDateTime.now().plusMinutes(tokenValidityMinutes));
        return token;
    }

    public int tokenValidityMinutes() {
        return tokenValidityMinutes;
    }

    /** Returned by castVote when the token is missing/expired, so the API can answer 401. */
    public static final String TOKEN_INVALID = "TOKEN_INVALID";

    /**
     * Casts (or changes) a vote using a valid token.
     * @return null on success, {@link #TOKEN_INVALID} if the token is bad, otherwise an error message.
     */
    @Transactional
    public String castVote(String token, long entityId, String reason) {
        // Validate the reason first so a too-short reason is always a 400, regardless of token state.
        if (reason == null || reason.trim().length() < 5) {
            return "Please give at least 5 characters explaining your selection.";
        }
        String mobile = repo.mobileForValidToken(token);
        if (mobile == null) {
            return TOKEN_INVALID;   // not an error to show — the UI will ask for a fresh OTP
        }
        if (!repo.entityExists(entityId)) {
            return "Selected entity does not exist.";
        }
        repo.upsertVote(mobile, entityId, reason.trim());
        return null;
    }

    /** Current selection for the mobile bound to this token, or null. */
    public MySelection mySelection(String token) {
        String mobile = repo.mobileForValidToken(token);
        if (mobile == null) {
            return null;
        }
        return repo.findSelection(mobile);
    }

    private static byte[] randomBytes() {
        byte[] b = new byte[24];
        RNG.nextBytes(b);
        return b;
    }
}
