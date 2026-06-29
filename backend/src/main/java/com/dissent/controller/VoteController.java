package com.dissent.controller;

import com.dissent.dto.Dtos.*;
import com.dissent.service.VoteService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api")
public class VoteController {

    // Accepts 8-15 digits, optional leading '+'. Adjust to your locale as needed.
    private static final Pattern MOBILE = Pattern.compile("^\\+?\\d{8,15}$");

    private final VoteService service;

    public VoteController(VoteService service) {
        this.service = service;
    }

    /** Public, read-only: the full list with live vote counts. */
    @GetMapping("/entities")
    public List<EntityRow> entities() {
        return service.listEntities();
    }

    /** Public, read-only: top reported issues (grouped reasons) for the left panel. */
    @GetMapping("/issues")
    public List<Issue> issues() {
        return service.topIssues();
    }

    /** Public, read-only: bullet suggestions for the reason typeahead (local substring match). */
    @GetMapping("/bullets")
    public List<String> bullets(@RequestParam(name = "q", required = false) String q) {
        return service.suggestBullets(q);
    }

    /** Step 1: request an OTP for a mobile number. */
    @PostMapping("/otp/request")
    public ResponseEntity<?> requestOtp(@RequestBody OtpRequest req) {
        String mobile = normalize(req.mobile());
        if (mobile == null) {
            return ResponseEntity.badRequest().body(new ApiError("Enter a valid mobile number."));
        }
        service.sendOtp(mobile);
        return ResponseEntity.ok().build();
    }

    /** Step 2: verify OTP, receive a time-bound vote token. */
    @PostMapping("/otp/verify")
    public ResponseEntity<?> verifyOtp(@RequestBody VerifyRequest req) {
        String mobile = normalize(req.mobile());
        if (mobile == null || req.code() == null) {
            return ResponseEntity.badRequest().body(new ApiError("Mobile and code are required."));
        }
        String token = service.verifyOtp(mobile, req.code().trim());
        if (token == null) {
            return ResponseEntity.status(401).body(new ApiError("Invalid or expired OTP."));
        }
        return ResponseEntity.ok(new VerifyResponse(token, service.tokenValidityMinutes()));
    }

    /** Step 3: cast or change a vote using the token. Returns the resulting selection. */
    @PostMapping("/vote")
    public ResponseEntity<?> vote(@RequestBody CastRequest req) {
        if (req.token() == null) {
            return ResponseEntity.status(401).body(new ApiError("Missing token."));
        }
        String err = service.castVote(req.token(), req.entityId(), req.reason());
        if (VoteService.TOKEN_INVALID.equals(err)) {
            // Token missing/expired: signal the client to re-verify via OTP (not a user-facing error).
            return ResponseEntity.status(401).body(new ApiError("Verify your mobile to continue."));
        }
        if (err != null) {
            return ResponseEntity.badRequest().body(new ApiError(err));
        }
        // Return the selection so the page can show it top-right without a separate call.
        return ResponseEntity.ok(service.mySelection(req.token()));
    }

    private static String normalize(String raw) {
        if (raw == null) return null;
        String m = raw.trim().replaceAll("[\\s-]", "");
        return MOBILE.matcher(m).matches() ? m : null;
    }
}
