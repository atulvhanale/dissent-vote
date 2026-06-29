package com.dissent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Turns a voter's free-text reason into a short canonical "issue" bullet using Claude.
 *
 * Implemented as raw HTTPS against the Messages API (model claude-opus-4-8) — the official
 * Anthropic Java SDK isn't available in this build's offline dependency cache, and Jackson +
 * java.net.http are already on the classpath, so no new dependency is needed.
 *
 * If no API key is configured the service is disabled: callers fall back to using the raw
 * reason as-is, and we simply log it.
 */
@Service
public class AiBulletService {

    private static final Logger log = LoggerFactory.getLogger(AiBulletService.class);
    private static final String ENDPOINT = "https://api.anthropic.com/v1/messages";

    private final String apiKey;
    private final String model;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    public AiBulletService(@Value("${app.anthropic.api-key:}") String apiKey,
                           @Value("${app.anthropic.model:claude-opus-4-8}") String model) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
    }

    public boolean isEnabled() {
        return !apiKey.isEmpty();
    }

    /**
     * Maps a raw reason to a canonical bullet. If one of {@code existingBullets} captures the same
     * idea, that exact text is returned (so similar reasons converge); otherwise a new short bullet
     * is coined. Returns {@code null} when AI is disabled or the call fails — callers then fall
     * back to the raw reason.
     */
    public String summarizeToBullet(String reason, List<String> existingBullets) {
        if (!isEnabled()) {
            log.info("AI disabled (no API key) — storing raw reason as-is: \"{}\"", reason);
            return null;
        }
        try {
            String prompt = buildPrompt(reason, existingBullets);
            ObjectNode body = json.createObjectNode();
            body.put("model", model);
            body.put("max_tokens", 100);
            ArrayNode messages = body.putArray("messages");
            ObjectNode msg = messages.addObject();
            msg.put("role", "user");
            msg.put("content", prompt);

            HttpRequest req = HttpRequest.newBuilder(URI.create(ENDPOINT))
                    .timeout(Duration.ofSeconds(20))
                    .header("content-type", "application/json")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.warn("Claude returned HTTP {} — falling back to raw reason. Body: {}",
                        resp.statusCode(), truncate(resp.body()));
                return null;
            }
            // Response shape: { "content": [ { "type": "text", "text": "..." }, ... ] }
            JsonNode root = json.readTree(resp.body());
            JsonNode content = root.path("content");
            if (content.isArray()) {
                for (JsonNode block : content) {
                    if ("text".equals(block.path("type").asText())) {
                        String bullet = clean(block.path("text").asText());
                        if (!bullet.isEmpty()) return bullet;
                    }
                }
            }
            log.warn("Claude response had no usable text — falling back to raw reason.");
            return null;
        } catch (Exception e) {
            log.warn("Claude call failed ({}) — falling back to raw reason.", e.toString());
            return null;
        }
    }

    private String buildPrompt(String reason, List<String> existingBullets) {
        StringBuilder sb = new StringBuilder();
        sb.append("You normalise voter dissent into a single short issue bullet for a public dashboard.\n");
        sb.append("Rules:\n");
        sb.append("- Output ONE concise bullet (3-8 words), Title Case, no trailing punctuation.\n");
        sb.append("- If the reason matches one of the existing bullets in meaning, reply with that ")
          .append("existing bullet VERBATIM so similar reasons group together.\n");
        sb.append("- Otherwise invent a new short bullet capturing the core grievance.\n");
        sb.append("- Reply with ONLY the bullet text. No quotes, no explanation, no list marker.\n\n");
        sb.append("Existing bullets:\n");
        if (existingBullets == null || existingBullets.isEmpty()) {
            sb.append("(none yet)\n");
        } else {
            for (String b : existingBullets) sb.append("- ").append(b).append("\n");
        }
        sb.append("\nVoter's reason: ").append(reason).append("\n\nBullet:");
        return sb.toString();
    }

    /** Strip stray quotes / list markers / extra lines the model might add. */
    private static String clean(String s) {
        if (s == null) return "";
        String t = s.strip();
        int nl = t.indexOf('\n');
        if (nl >= 0) t = t.substring(0, nl).strip();
        t = t.replaceAll("^[-*\\d.\\s]+", "").strip();      // leading bullet/number markers
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            t = t.substring(1, t.length() - 1).strip();
        }
        if (t.length() > 200) t = t.substring(0, 200).strip();
        return t;
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
