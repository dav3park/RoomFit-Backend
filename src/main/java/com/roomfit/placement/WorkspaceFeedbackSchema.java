package com.roomfit.placement;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** The pilot intentionally executes only operations already supported by the deterministic executor. */
final class WorkspaceFeedbackSchema {
    private WorkspaceFeedbackSchema() {}

    static final Map<String, Object> SCHEMA = build();

    private static Map<String, Object> build() {
        Map<String, Object> target = object("furnitureId", string(), "furnitureType", string(),
                "labelKeyword", string(), "locationHint", nullable(enumeration("NEAR_WINDOW", "CENTER", "LARGEST", "SMALLEST")),
                "ordinal", nullable(Map.of("type", "integer", "minimum", 1)));
        Map<String, Object> placement = object("relation", nullable(enumeration(Arrays.stream(FeedbackRelation.values()).map(Enum::name).toArray(String[]::new))),
                "magnitude", nullable(enumeration("SMALL", "MEDIUM", "LARGE")),
                "orientation", nullable(enumeration("QUARTER_TURN_CW", "QUARTER_TURN_CCW", "HALF_TURN", "ALIGN_WITH_WALL")),
                "side", nullable(enumeration("LEFT", "RIGHT", "FRONT", "BACK")));
        Map<String, Object> constraints = object("furnitureType", string(), "largerThanCurrent", bool(),
                "smallerThanCurrent", bool(), "minWidth", nullable(Map.of("type", "number", "minimum", 0.001, "maximum", 50)),
                "requiredStyleTags", array(string(), 12), "requiredLifestyleTags", array(string(), 12), "storagePreferred", bool());
        Map<String, Object> product = object("furnitureType", string(),
                "sizePreference", enumeration("SMALL", "LARGE", "SIMILAR", "ANY"),
                "storagePreferred", bool(), "styleKeywords", array(string(), 12));
        Map<String, Object> operation = object("operationId", string(),
                "type", enumeration("MOVE", "ROTATE", "REPLACE_PRODUCT", "ADD_FURNITURE", "REMOVE_FURNITURE", "SWAP_FURNITURE"),
                "target", target, "referenceTarget", nullable(target), "placement", nullable(placement),
                "constraints", nullable(constraints), "productRequirements", nullable(product),
                "replacementRequirements", nullable(product), "dependsOn", array(string(), 4));
        return object("version", enumeration("2.0"), "requestKind", enumeration("DIRECT", "COMPOSITE", "CLARIFICATION"),
                "operations", array(operation, 4), "goals", array(string(), 0),
                "clarification", nullable(object("question", string(), "targetFurnitureType", string(),
                        "requiredField", enumeration("targetFurnitureId", "referenceFurnitureId", "action", "directionFrame", "request"),
                        "candidateFurnitureIds", array(string(), 12))),
                "reason", string(), "preserve", array(object("furnitureId", string(),
                        "fields", array(enumeration("PRODUCT", "DIMENSIONS", "POSITION", "ORIENTATION", "APPEARANCE"), 5)), 12),
                "unsupportedReason", nullable(enumeration("UNSUPPORTED_OPERATION", "TOO_MANY_OPERATIONS", "UNSUPPORTED_ANGLE", "INVALID_DIMENSION_VALUE", "NO_WINDOW")));
    }

    private static Map<String, Object> string() { return Map.of("type", "string", "maxLength", 500); }
    private static Map<String, Object> bool() { return Map.of("type", "boolean"); }
    private static Map<String, Object> enumeration(String... values) { return Map.of("type", "string", "enum", List.of(values)); }
    private static Map<String, Object> array(Map<String, Object> items, int maximum) { return Map.of("type", "array", "items", items, "maxItems", maximum); }
    private static Map<String, Object> nullable(Map<String, Object> schema) {
        Map<String, Object> result = new LinkedHashMap<>(schema);
        result.put("type", List.of(schema.get("type"), "null"));
        if (schema.containsKey("enum")) {
            List<Object> values = new ArrayList<>((List<?>) schema.get("enum"));
            values.add(null);
            result.put("enum", values);
        }
        return result;
    }
    private static Map<String, Object> object(Object... pairs) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) properties.put((String) pairs[i], pairs[i + 1]);
        return Map.of("type", "object", "properties", properties,
                "required", new ArrayList<>(properties.keySet()), "additionalProperties", false);
    }

    /** Validate the same closed schema locally; a provider's schema promise is not a trust boundary. */
    static void validate(JsonNode value) { validate(value, SCHEMA); }

    @SuppressWarnings("unchecked")
    private static void validate(JsonNode value, Map<String, Object> schema) {
        Object types = schema.get("type");
        List<?> allowed = types instanceof List<?> list ? list : List.of(types);
        if (value == null || value.isMissingNode()) invalid();
        if (value.isNull()) {
            if (!allowed.contains("null")) invalid();
            return;
        }
        String type = value.isObject() ? "object" : value.isArray() ? "array" : value.isTextual() ? "string"
                : value.isBoolean() ? "boolean" : value.isIntegralNumber() ? "integer" : value.isNumber() ? "number" : "unknown";
        if (!allowed.contains(type) && !("integer".equals(type) && allowed.contains("number"))) invalid();
        if (schema.containsKey("enum") && !((List<?>) schema.get("enum")).contains(value.asText())) invalid();
        if (value.isTextual() && value.asText().length() > ((Number) schema.getOrDefault("maxLength", 500)).intValue()) invalid();
        if (value.isNumber() && (!Double.isFinite(value.asDouble())
                || value.asDouble() < ((Number) schema.getOrDefault("minimum", -Double.MAX_VALUE)).doubleValue()
                || value.asDouble() > ((Number) schema.getOrDefault("maximum", Double.MAX_VALUE)).doubleValue())) invalid();
        if (value.isObject()) {
            Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
            if (value.size() != properties.size()) invalid();
            for (Map.Entry<String, Object> entry : properties.entrySet()) {
                validate(value.get(entry.getKey()), (Map<String, Object>) entry.getValue());
            }
        }
        if (value.isArray()) {
            if (value.size() > ((Number) schema.get("maxItems")).intValue()) invalid();
            for (JsonNode item : value) validate(item, (Map<String, Object>) schema.get("items"));
        }
    }

    private static void invalid() { throw new IllegalArgumentException("Invalid workspace feedback schema"); }
}
