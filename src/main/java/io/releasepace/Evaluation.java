package io.releasepace;

import java.util.*;

/**
 * Flag evaluation engine.
 *
 * <p>Mirrors {@code api/src/lib/evaluate.ts} exactly. Evaluation order:
 * <ol>
 *   <li>Global kill switch — flag disabled beats everything</li>
 *   <li>Targeting rules   — ordered, first match wins</li>
 *   <li>Rule rollout      — a matched rule can itself be partial</li>
 *   <li>Default rollout   — baseline for everyone else</li>
 * </ol>
 *
 * <p>Every result carries a {@code reason} so "why is this on for Acme?" is
 * answerable without guessing.
 */
final class Evaluation {

    private Evaluation() {}

    /** Full evaluation result. */
    static final class Result {
        final String key;
        final boolean enabled;
        final Object value;
        final String reason;
        final String ruleId;
        final String missingAttribute;

        private Result(String key, boolean enabled, Object value,
                       String reason, String ruleId, String missingAttribute) {
            this.key              = key;
            this.enabled          = enabled;
            this.value            = value;
            this.reason           = reason;
            this.ruleId           = ruleId;
            this.missingAttribute = missingAttribute;
        }

        static Result of(String key, boolean enabled, Object value, String reason) {
            return new Result(key, enabled, value, reason, null, null);
        }
        static Result withRule(String key, boolean enabled, Object value, String reason, String ruleId) {
            return new Result(key, enabled, value, reason, ruleId, null);
        }
        static Result missing(String key, String attr) {
            return new Result(key, false, null, "MISSING_BUCKET_ATTRIBUTE", null, attr);
        }
    }

    /**
     * Evaluate a flag for a given context.
     *
     * @param flag     flag + state from the API
     * @param context  SDK evaluation context (tenantId, userId, plan, …)
     * @param segments segment key → set of entity keys (may be empty)
     */
    @SuppressWarnings("unchecked")
    static Result evaluate(Flag flag, Map<String, String> context,
                           Map<String, Set<String>> segments) {
        if (context  == null) context  = Map.of();
        if (segments == null) segments = Map.of();

        // 1. Kill switch
        if (!flag.enabled) {
            return Result.of(flag.key, false, null, "KILL_SWITCH");
        }

        // 2. Targeting rules — first match wins
        for (Map<String, Object> rule : flag.targetingRules) {
            if (!matchesRule(rule, context, segments)) continue;

            Map<String, Object> serve = (Map<String, Object>) rule.get("serve");
            boolean serveEnabled = Boolean.TRUE.equals(serve.get("enabled"));
            Object served = serve.containsKey("value") ? serve.get("value") : flag.value;
            String ruleId = (String) rule.get("id");

            // 3. Optional rollout within the matched audience
            Integer pct = toInt(rule.get("rollout_pct"));
            if (pct != null && pct < 100) {
                String bucketBy = nonEmpty((String) rule.get("bucket_by"));
                if (bucketBy == null) bucketBy = nonEmpty(flag.bucketBy);
                if (bucketBy == null) bucketBy = "userId";
                String entityId = bucketKeyFor(context, bucketBy);
                if (entityId == null) return Result.missing(flag.key, bucketBy);

                boolean included = Bucketing.inRollout(flag.key, entityId, pct);
                return Result.withRule(flag.key,
                        included && serveEnabled,
                        served,
                        included ? "TARGETING_MATCH" : "TARGETING_MATCH_ROLLOUT_EXCLUDED",
                        ruleId);
            }

            return Result.withRule(flag.key, serveEnabled, served, "TARGETING_MATCH", ruleId);
        }

        // 4. Default rollout
        Integer pct = flag.rolloutPct;
        if (pct != null && pct < 100) {
            String bucketBy = nonEmpty(flag.bucketBy);
            if (bucketBy == null) bucketBy = "userId";
            String entityId = bucketKeyFor(context, bucketBy);
            if (entityId == null) return Result.missing(flag.key, bucketBy);

            boolean included = Bucketing.inRollout(flag.key, entityId, pct);
            return Result.of(flag.key, included, flag.value,
                    included ? "DEFAULT_ROLLOUT_INCLUDED" : "DEFAULT_ROLLOUT_EXCLUDED");
        }

        return Result.of(flag.key, true, flag.value, "DEFAULT");
    }

    @SuppressWarnings("unchecked")
    private static boolean matchesRule(Map<String, Object> rule,
                                       Map<String, String> context,
                                       Map<String, Set<String>> segments) {
        List<Map<String, Object>> conditions =
                (List<Map<String, Object>>) rule.get("conditions");
        if (conditions == null || conditions.isEmpty()) return false;
        for (Map<String, Object> cond : conditions) {
            if (!matchesCondition(cond, context, segments)) return false;
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static boolean matchesCondition(Map<String, Object> cond,
                                            Map<String, String> context,
                                            Map<String, Set<String>> segments) {
        String attribute = (String) cond.get("attribute");
        String op        = (String) cond.get("op");
        String actual    = attribute != null ? context.get(attribute) : null;

        switch (op == null ? "" : op) {
            case "in_segment":
            case "not_in_segment": {
                String segKey = (String) cond.get("value");
                Set<String> members = segments.getOrDefault(segKey, Set.of());
                boolean hit = actual != null && members.contains(actual);
                return op.equals("in_segment") ? hit : !hit;
            }
            case "not_in": {
                // A missing attribute is genuinely "not in" the list.
                if (actual == null) return true;
                List<String> values = (List<String>) cond.get("values");
                return values == null || !values.contains(actual);
            }
        }

        // Every remaining operator needs a present attribute.
        if (actual == null) return false;

        switch (op) {
            case "in": {
                List<String> values = (List<String>) cond.get("values");
                return values != null && values.contains(actual);
            }
            case "equals":
                return actual.equals(cond.get("value"));
            case "contains": {
                String v = (String) cond.get("value");
                return v != null && actual.contains(v);
            }
            case "starts_with": {
                String v = (String) cond.get("value");
                return v != null && actual.startsWith(v);
            }
            case "semver_gte": {
                String v = (String) cond.get("value");
                return v != null && compareSemver(actual, v) >= 0;
            }
            default:
                // Unknown operator from a newer dashboard: fail closed.
                return false;
        }
    }

    private static String bucketKeyFor(Map<String, String> ctx, String bucketBy) {
        String raw = "tenantId".equals(bucketBy) ? ctx.get("tenantId") : ctx.get("userId");
        if (raw == null) return null;
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String nonEmpty(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static Integer toInt(Object v) {
        if (v == null) return null;
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(v.toString()); } catch (NumberFormatException e) { return null; }
    }

    /** Numeric semver comparison. Pre-release tags are ignored. */
    static int compareSemver(String a, String b) {
        int[] pa = parseSemver(a);
        int[] pb = parseSemver(b);
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            int va = i < pa.length ? pa[i] : 0;
            int vb = i < pb.length ? pb[i] : 0;
            if (va != vb) return va < vb ? -1 : 1;
        }
        return 0;
    }

    private static int[] parseSemver(String v) {
        if (v.startsWith("v")) v = v.substring(1);
        int dash = v.indexOf('-');
        if (dash >= 0) v = v.substring(0, dash);
        String[] parts = v.split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { out[i] = Integer.parseInt(parts[i]); } catch (NumberFormatException e) { out[i] = 0; }
        }
        return out;
    }
}
