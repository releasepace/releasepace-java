package io.releasepace;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * ReleasePace Java SDK
 *
 * <pre>{@code
 * ReleasePace fk = ReleasePace.builder()
 *     .apiKey("rp_live_xxx")
 *     .environment("production")
 *     .build();
 *
 * fk.connect();
 *
 * if (fk.isEnabled("new-checkout")) { ... }
 * String text = fk.getString("banner-text", "Default");
 * }</pre>
 */
public class ReleasePace implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(ReleasePace.class.getName());
    private static final String DEFAULT_API_URL = "https://api.releasepace.io";
    private static final String SDK_VERSION = "1.0.0";

    private final String apiKey;
    private final String environment;
    private final String apiUrl;
    private final long pollIntervalMs;
    private final Map<String, String> context;
    private final Consumer<List<Flag>> onUpdate;
    private final Consumer<Exception> onError;

    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<Map<String, Flag>> cache = new AtomicReference<>(new ConcurrentHashMap<>());
    private ScheduledExecutorService scheduler;
    private volatile boolean connected = false;

    private ReleasePace(Builder b) {
        this.apiKey = b.apiKey;
        this.environment = b.environment;
        this.apiUrl = b.apiUrl;
        this.pollIntervalMs = b.pollIntervalMs;
        this.context = Collections.unmodifiableMap(new HashMap<>(b.context));
        this.onUpdate = b.onUpdate;
        this.onError = b.onError;
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    // ── Public API ─────────────────────────────────────────────

    /** Fetch flags once and start background polling. */
    public ReleasePace connect() {
        fetchFlags();
        connected = true;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "releasepace-poll");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::fetchFlags, pollIntervalMs, pollIntervalMs, TimeUnit.MILLISECONDS);
        return this;
    }

    /** Stop polling. Implements AutoCloseable. */
    @Override
    public void close() {
        if (scheduler != null) scheduler.shutdown();
        connected = false;
    }

    /** Force an immediate re-fetch. */
    public void refresh() { fetchFlags(); }

    /** Return true if a boolean flag is enabled. */
    public boolean isEnabled(String key) {
        Flag flag = cache.get().get(key);
        if (flag == null || !flag.enabled) return false;
        if (flag.rolloutPct != null && flag.rolloutPct < 100) {
            String id = context.getOrDefault("userId",
                    context.getOrDefault("sessionId", key));
            return hashBucket(id + key) < flag.rolloutPct;
        }
        return true;
    }

    /** Get flag value as String, or return defaultValue. */
    public String getString(String key, String defaultValue) {
        Flag flag = cache.get().get(key);
        if (flag == null || !flag.enabled || flag.value == null) return defaultValue;
        return flag.value.toString();
    }

    /** Get flag value as double, or return defaultValue. */
    public double getNumber(String key, double defaultValue) {
        Flag flag = cache.get().get(key);
        if (flag == null || !flag.enabled || flag.value == null) return defaultValue;
        try { return Double.parseDouble(flag.value.toString()); }
        catch (NumberFormatException e) { return defaultValue; }
    }

    /** Get raw flag value object (can be Map, List, etc. for JSON flags). */
    public Object getValue(String key, Object defaultValue) {
        Flag flag = cache.get().get(key);
        if (flag == null || !flag.enabled) return defaultValue;
        return flag.value != null ? flag.value : defaultValue;
    }

    /** Get all flags as an unmodifiable list. */
    public List<Flag> getAllFlags() {
        return Collections.unmodifiableList(new ArrayList<>(cache.get().values()));
    }

    // ── Internal ───────────────────────────────────────────────

    private void fetchFlags() {
        try {
            StringBuilder url = new StringBuilder(apiUrl)
                .append("/api/client/features?environment=")
                .append(environment);
            context.forEach((k, v) ->
                url.append("&ctx_").append(k).append("=").append(v));

            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url.toString()))
                .header("Authorization", "Bearer " + apiKey)
                .header("X-ReleasePace-SDK", "java/" + SDK_VERSION)
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new IOException("ReleasePace API returned " + resp.statusCode());
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> body = mapper.readValue(resp.body(), Map.class);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> features = (List<Map<String, Object>>) body.get("features");

            Map<String, Flag> newCache = new ConcurrentHashMap<>();
            List<Flag> updated = new ArrayList<>();

            for (Map<String, Object> f : features) {
                Flag flag = Flag.fromMap(f);
                Flag old = cache.get().get(flag.key);
                if (!flag.equals(old)) updated.add(flag);
                newCache.put(flag.key, flag);
            }

            cache.set(newCache);

            if (!updated.isEmpty() && onUpdate != null) {
                onUpdate.accept(getAllFlags());
            }

        } catch (Exception e) {
            LOG.warning("ReleasePace fetch error: " + e.getMessage());
            if (onError != null) onError.accept(e);
        }
    }

    private static int hashBucket(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return new BigInteger(1, Arrays.copyOfRange(digest, 0, 4)).intValue() % 100;
        } catch (Exception e) {
            return input.hashCode() & 0x7FFFFFFF % 100;
        }
    }

    // ── Builder ────────────────────────────────────────────────

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String apiKey;
        private String environment = "production";
        private String apiUrl = DEFAULT_API_URL;
        private long pollIntervalMs = 30_000;
        private Map<String, String> context = new HashMap<>();
        private Consumer<List<Flag>> onUpdate;
        private Consumer<Exception> onError;

        public Builder apiKey(String v)          { this.apiKey = v; return this; }
        public Builder environment(String v)     { this.environment = v; return this; }
        public Builder apiUrl(String v)          { this.apiUrl = v; return this; }
        public Builder pollIntervalMs(long v)    { this.pollIntervalMs = v; return this; }
        public Builder context(Map<String,String> v) { this.context = v; return this; }
        public Builder onUpdate(Consumer<List<Flag>> v) { this.onUpdate = v; return this; }
        public Builder onError(Consumer<Exception> v)   { this.onError = v; return this; }

        public ReleasePace build() {
            Objects.requireNonNull(apiKey, "apiKey is required");
            return new ReleasePace(this);
        }
    }
}
