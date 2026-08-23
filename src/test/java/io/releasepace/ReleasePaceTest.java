package io.releasepace;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReleasePaceTest {

    private HttpServer mockServer;
    private int port;
    private String serverUrl;
    private final AtomicReference<String> responseBody = new AtomicReference<>();
    private final AtomicReference<String> lastRawQuery = new AtomicReference<>();
    private final AtomicInteger requestCount = new AtomicInteger();

    private static final String MOCK_RESPONSE = """
        {
          "version": 1,
          "environment": "test",
          "features": [
            {"key":"bool-on",    "name":"Bool On",    "type":"boolean","enabled":true, "value":null,    "rollout_pct":null, "strategies":[]},
            {"key":"bool-off",   "name":"Bool Off",   "type":"boolean","enabled":false,"value":null,    "rollout_pct":null, "strategies":[]},
            {"key":"str-flag",   "name":"Str Flag",   "type":"string", "enabled":true, "value":"hello", "rollout_pct":null, "strategies":[]},
            {"key":"num-flag",   "name":"Num Flag",   "type":"number", "enabled":true, "value":42,      "rollout_pct":null, "strategies":[]},
            {"key":"rollout-0",  "name":"Roll 0",     "type":"boolean","enabled":true, "value":null,    "rollout_pct":0,    "strategies":[]},
            {"key":"rollout-100","name":"Roll 100",   "type":"boolean","enabled":true, "value":null,    "rollout_pct":100,  "strategies":[]},
            {"key":"rollout-50", "name":"Roll 50",    "type":"boolean","enabled":true, "value":null,    "rollout_pct":50,   "strategies":[]}
          ]
        }
        """;

    @BeforeAll
    void startServer() throws Exception {
        mockServer = HttpServer.create(new InetSocketAddress(0), 0);
        port = mockServer.getAddress().getPort();
        serverUrl = "http://localhost:" + port;
        responseBody.set(MOCK_RESPONSE);

        mockServer.createContext("/api/client/features", exchange -> {
            requestCount.incrementAndGet();
            lastRawQuery.set(exchange.getRequestURI().getRawQuery());
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth == null || !auth.startsWith("Bearer ")) {
                exchange.sendResponseHeaders(401, 0);
                exchange.close();
                return;
            }
            byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
        });
        mockServer.start();
    }

    @AfterAll
    void stopServer() {
        if (mockServer != null) mockServer.stop(0);
    }

    ReleasePace buildClient() {
        return ReleasePace.builder()
            .apiKey("rp_live_test")
            .environment("test")
            .apiUrl(serverUrl)
            .pollIntervalMs(Long.MAX_VALUE) // disable polling
            .build()
            .connect();
    }

    @Test
    void requiresApiKey() {
        assertThrows(IllegalArgumentException.class, () ->
            ReleasePace.builder().build());
    }

    @Test
    void rejectsInvalidPollInterval() {
        assertThrows(IllegalArgumentException.class, () -> ReleasePace.builder()
            .apiKey("rp_live_test")
            .pollIntervalMs(0)
            .build());
    }

    @Test
    void connectLoadsFlags() {
        try (ReleasePace rp = buildClient()) {
            List<Flag> flags = rp.getAllFlags();
            assertFalse(flags.isEmpty(), "Should load flags");
        }
    }

    @Test
    void isEnabledTrueForEnabledFlag() {
        try (ReleasePace rp = buildClient()) {
            assertTrue(rp.isEnabled("bool-on"));
        }
    }

    @Test
    void isEnabledFalseForDisabledFlag() {
        try (ReleasePace rp = buildClient()) {
            assertFalse(rp.isEnabled("bool-off"));
        }
    }

    @Test
    void isEnabledFalseForMissingFlag() {
        try (ReleasePace rp = buildClient()) {
            assertFalse(rp.isEnabled("does-not-exist"));
        }
    }

    @Test
    void getStringReturnsValue() {
        try (ReleasePace rp = buildClient()) {
            assertEquals("hello", rp.getString("str-flag", "default"));
        }
    }

    @Test
    void getStringReturnsDefaultWhenDisabled() {
        try (ReleasePace rp = buildClient()) {
            assertEquals("fallback", rp.getString("bool-off", "fallback"));
        }
    }

    @Test
    void getNumberReturnsValue() {
        try (ReleasePace rp = buildClient()) {
            assertEquals(42.0, rp.getNumber("num-flag", 0));
        }
    }

    @Test
    void rollout0AlwaysFalse() {
        try (ReleasePace rp = buildClient()) {
            assertFalse(rp.isEnabled("rollout-0"));
        }
    }

    @Test
    void rollout100AlwaysTrue() {
        try (ReleasePace rp = buildClient()) {
            assertTrue(rp.isEnabled("rollout-100"));
        }
    }

    @Test
    void rollout50IsSticky() {
        try (ReleasePace rp = buildClient()) {
            var ctx = new java.util.HashMap<String, String>();
            ctx.put("userId", "sticky-test-user");
            ReleasePace rp2 = ReleasePace.builder()
                .apiKey("rp_live_test")
                .environment("test")
                .apiUrl(serverUrl)
                .pollIntervalMs(Long.MAX_VALUE)
                .context(ctx)
                .build()
                .connect();

            boolean r1 = rp2.isEnabled("rollout-50");
            boolean r2 = rp2.isEnabled("rollout-50");
            assertEquals(r1, r2, "Rollout should be sticky for same user");
            rp2.close();
        }
    }

    @Test
    void contextAndEnvironmentAreUrlEncoded() {
        var context = new HashMap<String, String>();
        context.put("user id", "a&b=c");
        try (ReleasePace ignored = ReleasePace.builder()
            .apiKey("rp_live_test")
            .environment("test env")
            .apiUrl(serverUrl)
            .pollIntervalMs(Long.MAX_VALUE)
            .context(context)
            .build()
            .connect()) {
            assertEquals("environment=test+env&ctx_user+id=a%26b%3Dc", lastRawQuery.get());
        }
    }

    @Test
    void connectIsIdempotent() {
        int before = requestCount.get();
        try (ReleasePace rp = ReleasePace.builder()
            .apiKey("rp_live_test")
            .apiUrl(serverUrl)
            .pollIntervalMs(Long.MAX_VALUE)
            .build()) {
            assertSame(rp, rp.connect());
            assertSame(rp, rp.connect());
            assertEquals(before + 1, requestCount.get());
        }
    }

    @Test
    void onUpdateCalledForInitialLoadAndRemoval() {
        var updateCount = new AtomicInteger();
        try (ReleasePace rp = ReleasePace.builder()
            .apiKey("rp_live_test")
            .apiUrl(serverUrl)
            .pollIntervalMs(Long.MAX_VALUE)
            .onUpdate(flags -> updateCount.incrementAndGet())
            .build()
            .connect()) {
            assertEquals(1, updateCount.get());
            responseBody.set("""
                {"version":2,"environment":"test","features":[]}
                """);
            rp.refresh();
            assertEquals(2, updateCount.get());
            assertTrue(rp.getAllFlags().isEmpty());
        } finally {
            responseBody.set(MOCK_RESPONSE);
        }
    }
}
