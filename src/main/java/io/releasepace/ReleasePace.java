package io.releasepace;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
    private static final String SDK_VERSION = resolveSdkVersion();

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

    /**
     * Fetch flags once and start background polling. Repeated calls are idempotent.
     * @return this client
     */
    public synchronized ReleasePace connect() {
        if (connected) return this;
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
    public synchronized void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        connected = false;
    }

    /** Force an immediate re-fetch. */
    public void refresh() { fetchFlags(); }

    /**
     * Evaluate a boolean flag.
     * @param key flag key
     * @return {@code true} when enabled for the configured context
     */
    public boolean isEnabled(String key) {
        return explain(key).enabled;
    }

    /**
     * Full evaluation result for a flag — same reason codes the dashboard
     * lookup screen shows. Useful in logs when debugging a missing feature.
     */
    public Evaluation.Result explain(String key) {
        Flag flag = cache.get().get(key);
        if (flag == null) {
            return Evaluation.Result.of(key, false, null, "NOT_FOUND");
        }
        // Segments not pre-loaded in this version; attribute-based rules work
        // without them. in_segment evaluates against an empty set for now.
        return Evaluation.evaluate(flag, context, Map.of());
    }

    /**
     * Get a flag value as text.
     * @param key flag key
     * @param defaultValue value returned when the flag is unavailable or disabled
     * @return the evaluated value or {@code defaultValue}
     */
    public String getString(String key, String defaultValue) {
        Flag flag = cache.get().get(key);
        if (flag == null || !flag.enabled || flag.value == null) return defaultValue;
        return flag.value.toString();
    }

    /**
     * Get a flag value as a number.
     * @param key flag key
     * @param defaultValue value returned when the flag cannot be evaluated as a number
     * @return the evaluated value or {@code defaultValue}
     */
    public double getNumber(String key, double defaultValue) {
        Flag flag = cache.get().get(key);
        if (flag == null || !flag.enabled || flag.value == null) return defaultValue;
        try { return Double.parseDouble(flag.value.toString()); }
        catch (NumberFormatException e) { return defaultValue; }
    }

    /**
     * Get the raw flag value, including maps and lists for JSON flags.
     * @param key flag key
     * @param defaultValue value returned when the flag is unavailable or disabled
     * @return the raw evaluated value or {@code defaultValue}
     */
    public Object getValue(String key, Object defaultValue) {
        Flag flag = cache.get().get(key);
        if (flag == null || !flag.enabled) return defaultValue;
        return flag.value != null ? flag.value : defaultValue;
    }

    /**
     * Get a stable, key-sorted snapshot of all cached flags.
     * @return an unmodifiable flag list
     */
    public List<Flag> getAllFlags() {
        List<Flag> flags = new ArrayList<>(cache.get().values());
        flags.sort(Comparator.comparing(flag -> flag.key));
        return Collections.unmodifiableList(flags);
    }

    // ── Internal ───────────────────────────────────────────────

    private void fetchFlags() {
        try {
            StringBuilder url = new StringBuilder(apiUrl)
                .append("/api/client/features?environment=")
                .append(encodeQueryValue(environment));

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

            if (features == null) {
                throw new IOException("ReleasePace API response is missing features");
            }

            Map<String, Flag> newCache = new HashMap<>();

            for (Map<String, Object> f : features) {
                Flag flag = Flag.fromMap(f);
                newCache.put(flag.key, flag);
            }

            Map<String, Flag> oldCache = cache.getAndSet(Collections.unmodifiableMap(newCache));

            if (!newCache.equals(oldCache) && onUpdate != null) {
                try {
                    onUpdate.accept(getAllFlags());
                } catch (RuntimeException callbackError) {
                    LOG.warning("ReleasePace onUpdate callback error: " + callbackError.getMessage());
                }
            }

        } catch (Exception e) {
            LOG.warning("ReleasePace fetch error: " + e.getMessage());
            if (onError != null) {
                try {
                    onError.accept(e);
                } catch (RuntimeException callbackError) {
                    LOG.warning("ReleasePace onError callback error: " + callbackError.getMessage());
                }
            }
        }
    }

    private static String encodeQueryValue(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String resolveSdkVersion() {
        String version = ReleasePace.class.getPackage().getImplementationVersion();
        return version != null ? version : "dev";
    }


    // ── Builder ────────────────────────────────────────────────

    /**
     * Create a client builder.
     * @return a new client builder
     */
    public static Builder builder() { return new Builder(); }

    /** Builds configured {@link ReleasePace} clients. */
    public static class Builder {
        private String apiKey;
        private String environment = "production";
        private String apiUrl = DEFAULT_API_URL;
        private long pollIntervalMs = 30_000;
        private Map<String, String> context = new HashMap<>();
        private Consumer<List<Flag>> onUpdate;
        private Consumer<Exception> onError;

        /** Creates a builder with production defaults. */
        public Builder() {}

        /**
         * Set the SDK API key.
         * @param v SDK API key
         * @return this builder
         */
        public Builder apiKey(String v)          { this.apiKey = v; return this; }
        /**
         * Set the environment to evaluate.
         * @param v environment slug
         * @return this builder
         */
        public Builder environment(String v)     { this.environment = v; return this; }
        /**
         * Override the API base URL.
         * @param v ReleasePace API base URL
         * @return this builder
         */
        public Builder apiUrl(String v)          { this.apiUrl = v; return this; }
        /**
         * Set the background polling interval.
         * @param v polling interval in milliseconds
         * @return this builder
         */
        public Builder pollIntervalMs(long v)    { this.pollIntervalMs = v; return this; }
        /**
         * Set evaluation attributes sent with fetch requests.
         * @param v evaluation context
         * @return this builder
         */
        public Builder context(Map<String,String> v) { this.context = v; return this; }
        /**
         * Register a flag snapshot change callback.
         * @param v callback invoked when the flag snapshot changes
         * @return this builder
         */
        public Builder onUpdate(Consumer<List<Flag>> v) { this.onUpdate = v; return this; }
        /**
         * Register a fetch error callback.
         * @param v callback invoked when fetching fails
         * @return this builder
         */
        public Builder onError(Consumer<Exception> v)   { this.onError = v; return this; }

        /**
         * Build the configured client without connecting it.
         * @return a validated, disconnected client
         */
        public ReleasePace build() {
            requireNonBlank(apiKey, "apiKey");
            requireNonBlank(environment, "environment");
            requireNonBlank(apiUrl, "apiUrl");
            if (pollIntervalMs <= 0) {
                throw new IllegalArgumentException("pollIntervalMs must be greater than zero");
            }
            Objects.requireNonNull(context, "context is required");
            context.forEach((key, value) -> {
                requireNonBlank(key, "context key");
                Objects.requireNonNull(value, "context value is required");
            });
            return new ReleasePace(this);
        }

        private static void requireNonBlank(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " is required");
            }
        }
    }
}
