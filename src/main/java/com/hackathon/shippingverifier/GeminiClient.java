package com.hackathon.shippingverifier;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Shared Gemini connection. Credentials never leave the request header. */
@Service
public class GeminiClient {
    public static final String MODEL = "gemini-3.1-flash-lite";
    private final JsonMapper mapper;
    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15)).build();

    private final DurableData data;
    public GeminiClient(JsonMapper mapper, DurableData data) { this.mapper = mapper; this.data = data; }

    private long nextRequestNanos;
    private final Path cacheDirectory = Path.of("data", "ai-cache");
    private static final Set<String> CATEGORIES = Set.of(
        "BL_COMPARISON", "SI_REQUEST", "INVOICE_QUERY", "GENERAL", "SPAM");

    // One request at a time, including identical concurrent requests.
    public synchronized String generate(String instruction, String input, boolean json) {
        Path cacheFile;
        try {
            String identity = mapper.writeValueAsString(List.of(
                "shipping-cache-v1", MODEL, instruction, input, json));
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(identity.getBytes(StandardCharsets.UTF_8)));
            cacheFile = cacheDirectory.resolve(digest + ".txt");
        } catch (Exception error) {
            throw new AiFailure(500, "Could not prepare the AI cache.");
        }
        try {
            if (Files.isRegularFile(cacheFile)) {
                String saved = Files.readString(cacheFile);
                if (cacheable(saved, json)) return saved;
            }
        } catch (IOException ignored) {
            // An unreadable cache entry must never become an accepted result.
        }
        beforeRequest();
        String answer;
        try {
            answer = requestUncached(instruction, input, json);
        } catch (AiFailure failure) {
            if (failure.status() == 429) {
                nextRequestNanos = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            }
            throw failure;
        }
        if (cacheable(answer, json)) {
            try {
                data.write(cacheFile, answer.getBytes(StandardCharsets.UTF_8), false);
            } catch (IOException error) {
                throw new AiFailure(503, "AI answered, but its cache could not be saved. Check data storage before retrying.");
            }
        }
        return answer;
    }

    private boolean cacheable(String answer, boolean json) {
        if (answer == null || answer.isBlank()) return false;
        if (!json) return CATEGORIES.contains(answer.trim());
        try {
            JsonNode root = mapper.readTree(answer);
            return root.isObject() && root.path("document_type").isString()
                && root.path("fields").isObject();
        } catch (RuntimeException invalid) { return false; }
    }

    protected void beforeRequest() {
        long remaining = nextRequestNanos - System.nanoTime();
        // Fail promptly during a quota cooldown instead of holding a long queue.
        if (remaining > Duration.ofSeconds(15).toNanos()) {
            throw new AiFailure(429, "Gemini quota cooldown is active. Wait at least "
                + ((remaining / 1_000_000_000L) + 1)
                + " seconds. A daily quota requires its reset; repeated retries will not fix it.");
        }
        if (remaining > 0) {
            try {
                java.util.concurrent.TimeUnit.NANOSECONDS.sleep(remaining);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new AiFailure(503, "The AI request was interrupted while waiting.");
            }
        }
        nextRequestNanos = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    }

    protected String requestUncached(String instruction, String input, boolean json) {
        String key = System.getenv("GEMINI_API_KEY");
        if (key == null || key.isBlank()) {
            throw new AiFailure(503, "Java cannot find GEMINI_API_KEY.");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("systemInstruction", Map.of("parts", List.of(Map.of("text", instruction))));
        payload.put("contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", input)))));
        if (json) payload.put("generationConfig", Map.of("responseMimeType", "application/json"));
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://generativelanguage.googleapis.com/v1beta/models/" + MODEL + ":generateContent"))
                .timeout(Duration.ofSeconds(90))
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", key.trim())
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload))).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429) throw new AiFailure(429, "Gemini rate limit or quota reached. Check quota before retrying.");
            if (response.statusCode() != 200) throw new AiFailure(502, "Gemini returned HTTP " + response.statusCode() + ". No result was accepted.");
            JsonNode candidate = mapper.readTree(response.body()).path("candidates").path(0);
            JsonNode finish = candidate.path("finishReason");
            if (!finish.isString() || !"STOP".equals(finish.stringValue()))
                throw new AiFailure(502, "Gemini did not complete its answer. Retry the operation.");
            JsonNode parts = candidate.path("content").path("parts");
            if (!parts.isArray()) throw new AiFailure(502, "Gemini returned no answer text.");
            StringBuilder answer = new StringBuilder();
            for (int i = 0; i < parts.size(); i++) {
                JsonNode part = parts.get(i);
                JsonNode thought = part.path("thought");
                if (thought.isBoolean() && thought.booleanValue()) continue;
                JsonNode text = part.path("text");
                if (text.isString()) answer.append(text.stringValue());
            }
            if (answer.toString().isBlank()) throw new AiFailure(502, "Gemini returned an empty answer.");
            return answer.toString().trim();
        } catch (AiFailure failure) {
            throw failure;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AiFailure(503, "The AI request was interrupted.");
        } catch (IOException error) {
            throw new AiFailure(502, "The AI connection failed or timed out. Retry when the connection is available.");
        } catch (RuntimeException error) {
            System.err.println("Gemini response error: " + error.getClass().getSimpleName());
            throw new AiFailure(502, "Gemini returned an unreadable response. No result was accepted.");
        }
    }

    public static class AiFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final int status;
        public AiFailure(int status, String message) { super(message); this.status = status; }
        public int status() { return status; }
    }
}
