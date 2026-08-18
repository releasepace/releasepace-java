package io.releasepace;

import java.util.*;

public class Flag {
    public final String key;
    public final String name;
    public final String type;
    public final boolean enabled;
    public final Object value;
    public final Integer rolloutPct;
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
        if (!(o instanceof Flag)) return false;
        Flag f = (Flag) o;
        return key.equals(f.key) && enabled == f.enabled && Objects.equals(value, f.value)
            && Objects.equals(rolloutPct, f.rolloutPct);
    }

    @Override public int hashCode() { return key.hashCode(); }
    @Override public String toString() {
        return "Flag{key=" + key + ", enabled=" + enabled + ", value=" + value + "}";
    }
}
