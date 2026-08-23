package io.releasepace;

import java.util.*;

/** Immutable feature flag returned by ReleasePace. */
public class Flag {
    /** Stable flag key used for evaluation. */
    public final String key;
    /** Human-readable flag name. */
    public final String name;
    /** Flag value type reported by the API. */
    public final String type;
    /** Whether the flag is enabled. */
    public final boolean enabled;
    /** Raw flag value, or {@code null} for value-less flags. */
    public final Object value;
    /** Percentage rollout from 0 through 100, or {@code null}. */
    public final Integer rolloutPct;
    /** Targeting strategies supplied by the API. */
    public final List<Map<String, Object>> strategies;

    Flag(String key, String name, String type, boolean enabled,
         Object value, Integer rolloutPct, List<Map<String, Object>> strategies) {
        this.key = key;
        this.name = name;
        this.type = type;
        this.enabled = enabled;
        this.value = value;
        this.rolloutPct = rolloutPct;
        this.strategies = strategies != null ? Collections.unmodifiableList(strategies) : List.of();
    }

    @SuppressWarnings("unchecked")
    static Flag fromMap(Map<String, Object> m) {
        return new Flag(
            (String) m.get("key"),
            (String) m.get("name"),
            (String) m.getOrDefault("type", "boolean"),
            Boolean.TRUE.equals(m.get("enabled")),
            m.get("value"),
            m.get("rollout_pct") != null ? ((Number) m.get("rollout_pct")).intValue() : null,
            (List<Map<String, Object>>) m.getOrDefault("strategies", List.of())
        );
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Flag f)) return false;
        return enabled == f.enabled
            && Objects.equals(key, f.key)
            && Objects.equals(name, f.name)
            && Objects.equals(type, f.type)
            && Objects.equals(value, f.value)
            && Objects.equals(rolloutPct, f.rolloutPct)
            && Objects.equals(strategies, f.strategies);
    }

    @Override public int hashCode() {
        return Objects.hash(key, name, type, enabled, value, rolloutPct, strategies);
    }
    @Override public String toString() {
        return "Flag{key=" + key + ", enabled=" + enabled + ", value=" + value + "}";
    }
}
