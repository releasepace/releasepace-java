# releasepace-java

Official Java SDK for [ReleasePace](https://releasepace.io) — production-grade feature flags.

[![Maven Central](https://img.shields.io/maven-central/v/io.releasepace/releasepace-java)](https://central.sonatype.com/artifact/io.releasepace/releasepace-java)
[![Java](https://img.shields.io/badge/Java-17+-blue)](https://openjdk.org/)
[![license](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

---

## Installation

**Maven:**
```xml
<dependency>
  <groupId>io.releasepace</groupId>
  <artifactId>releasepace-java</artifactId>
  <version>1.0.0</version>
</dependency>
```

**Gradle:**
```gradle
implementation 'io.releasepace:releasepace-java:1.0.0'
```

---

## Quick start

```java
import io.releasepace.ReleasePace;

// try-with-resources — auto-closes and stops polling
try (ReleasePace rp = ReleasePace.builder()
        .apiKey("rp_live_xxxxxxxxxxxx")
        .environment("production")
        .build()
        .connect()) {

    if (rp.isEnabled("new-checkout")) {
        renderNewCheckout();
    }

    String label = rp.getString("cta-label",  "Get started");
    double limit = rp.getNumber("rate-limit",  100.0);
}
```

---

## Spring Boot integration

```java
@Configuration
public class ReleasePaceConfig {

    @Bean(destroyMethod = "close")
    public ReleasePace releasePace(@Value("${releasepace.api-key}") String apiKey,
                                   @Value("${releasepace.environment}") String env) {
        return ReleasePace.builder()
            .apiKey(apiKey)
            .environment(env)
            .onError(e -> log.error("ReleasePace error", e))
            .build()
            .connect();
    }
}

@RestController
public class CheckoutController {

    @Autowired ReleasePace rp;

    @GetMapping("/checkout")
    public String checkout() {
        return rp.isEnabled("new-checkout") ? "v2" : "v1";
    }
}
```

---

## API reference

### Builder

| Method | Type | Default | Description |
|--------|------|---------|-------------|
| `apiKey(String)` | required | — | SDK key |
| `environment(String)` | `String` | `"production"` | Environment slug |
| `apiUrl(String)` | `String` | `https://api.releasepace.io` | Override URL |
| `pollIntervalMs(long)` | `long` | `30000` | Poll interval in ms |
| `context(Map<String,String>)` | `Map` | `{}` | Evaluation context |
| `onUpdate(Consumer<List<Flag>>)` | `Consumer` | `null` | Flag change callback |
| `onError(Consumer<Exception>)` | `Consumer` | `null` | Error callback |

### Methods

| Method | Returns | Description |
|--------|---------|-------------|
| `connect()` | `ReleasePace` | Fetch flags, start polling |
| `close()` | `void` | Stop polling (AutoCloseable) |
| `refresh()` | `void` | Force re-fetch |
| `isEnabled(String key)` | `boolean` | Boolean flag check |
| `getString(String key, String default)` | `String` | String flag value |
| `getNumber(String key, double default)` | `double` | Number flag value |
| `getValue(String key, Object default)` | `Object` | Any flag value |
| `getAllFlags()` | `List<Flag>` | All current flags |

---


## Author

**[Aryaa Tiwari](https://github.com/AryaaTiwari)** — [LinkedIn](https://www.linkedin.com/in/aryaa-tiwari/)

## License

MIT © [ReleasePace](https://releasepace.io)
