package com.roomfit.placement;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roomfit.agent.domain.AgentContext;
import com.roomfit.common.CustomException;
import com.roomfit.common.ErrorCode;
import com.roomfit.llm.LlmClient;
import com.roomfit.product.catalog.GeneratedFurnitureCatalog;
import com.roomfit.product.domain.MockProduct;
import com.roomfit.product.service.MockProductService;
import com.roomfit.room.Furniture;
import com.roomfit.room.FurnitureStatus;
import com.roomfit.room.Opening;
import com.roomfit.room.Position;
import com.roomfit.room.Room;
import com.roomfit.room.RotationUtils;
import com.roomfit.room.Wall;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 좌표(x/z/rotation)까지 LLM이 직접 생성하는 배치 추천 구현체.
 *
 * docs/llm-integration-plan.md는 "LLM이 직접 x/z/rotation을 생성하지 않는다"는
 * 원칙을 정했지만, 이 구현체는 그 원칙에서 벗어나 LLM이 전체 furniture 배열
 * (기존 가구 재배치 + 신규 추천)을 통째로 생성하도록 한다 — 서비스 방향
 * 결정에 따른 것. 대신 그 문서의 다른 안전 원칙("LLM 실패 시 rule-based
 * fallback", "최종 결과는 항상 validationResult로 검증")은 그대로 지킨다:
 * 응답은 반드시 ValidationService로 재검증하고, 실패하면 예외를 던져
 * FallbackPlacementService가 RuleBasedPlacementService로 대체하게 한다.
 */
public class LlmPlacementService implements PlacementService {

    // "storage" predates the generated catalog and remains a deliberately
    // supported legacy type. It is not an alias for any canonical storage type.
    private static final Set<String> EXPLICIT_LEGACY_TYPES = Set.of("storage");

    private final LlmClient llmClient;
    private final ValidationService validationService;
    private final MockProductService mockProductService;
    private final ObjectMapper objectMapper;

    public LlmPlacementService(LlmClient llmClient, ValidationService validationService,
                                MockProductService mockProductService, ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.validationService = validationService;
        this.mockProductService = mockProductService;
        this.objectMapper = objectMapper;
    }

    @Override
    public PlacementResult recommend(AgentContext context, Room room) {
        List<Furniture> activeExisting = room.getFurniture().stream()
                .filter(item -> item.getStatus() != FurnitureStatus.DELETED)
                .toList();
        List<MockProduct> selectedProducts = mockProductService.findByProductIds(context.getSelectedProductIds());

        String prompt = buildPrompt(room, activeExisting, context, selectedProducts);
        JsonNode root = parseJson(llmClient.complete(prompt));
        List<Furniture> candidate = toFurnitureList(root, activeExisting);
        normalizeSupportedFurniture(candidate);

        verifyRequestedTypeCounts(context, activeExisting, candidate);

        if (candidate.isEmpty()) {
            throw new CustomException(ErrorCode.RECOMMENDATION_FAILED);
        }

        ValidationResult validationResult = validationService.validateChange(room, activeExisting, candidate);
        if (!isFullyValid(validationResult)) {
            throw new CustomException(ErrorCode.RECOMMENDATION_FAILED);
        }

        return new PlacementResult(RecommendationStatus.SUCCESS, candidate, ScoreSummary.defaultSummary());
    }

    private boolean isFullyValid(ValidationResult result) {
        return result.isCollisionFree() && result.isBoundaryValid()
                && result.isDoorClearance() && result.isWindowClearance() && result.isPathSecured();
    }

    private String buildPrompt(Room room, List<Furniture> activeExisting, AgentContext context,
                                List<MockProduct> selectedProducts) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("room", roomPayload(room));
        input.put("existingFurniture", activeExisting.stream().map(this::furniturePayload).toList());
        input.put("lifestyleGoal", context.getLifestyleGoal());
        input.put("designStyle", context.getDesignStyle());
        input.put("requiredItems", context.getRequiredItems());
        input.put("optionalItems", context.getOptionalItems());
        input.put("styleTags", context.getStyleTags());
        input.put("preferredColorTone", context.getPreferredColorTone());
        input.put("selectedProducts", selectedProducts.stream().map(this::productPayload).toList());

        String inputJson;
        try {
            inputJson = objectMapper.writeValueAsString(input);
        } catch (JsonProcessingException e) {
            throw new CustomException(ErrorCode.RECOMMENDATION_FAILED);
        }

        return """
                You are an interior layout planner for a single room. Given the room JSON below,
                return a JSON object with a single "furniture" array describing the FINAL, complete
                furniture layout for this room (both kept/repositioned existing pieces and any newly
                recommended pieces for requiredItems/optionalItems).

                Coordinate system: x in [0, room.width], z in [0, room.depth], meters, corner origin
                (matches the room JSON below exactly). rotation is degrees (0/90/180/270 preferred).
                Each furniture item must include: id, type, label, width, depth, height,
                position ({x, z}), rotation, status (EXISTING, RECOMMENDED, or USER_MODIFIED),
                productId (nullable), styleTags (array, can be empty).

                Rules:
                - Include every existingFurniture item exactly once. For those items, id, type, label,
                  width, depth, height, status, productId, variantId, and styleTags are immutable.
                  You may change only position.x, position.z, and rotation.
                - Every type listed in requiredItems and optionalItems is a required independent request:
                  preserve its exact normalized type and do not substitute a similar type.
                - If both are present, center each monitor on the nearest desk and each TV on the
                  nearest media_console by using the support's exact position.x, position.z, and rotation.
                  Those monitor/desk and TV/media_console pairs intentionally overlap in footprint.
                - Every piece's full footprint (width/depth rotated by `rotation`) must stay strictly
                  inside the room's width/depth. Other furniture pairs must not overlap.
                - Leave clearance in front of doors and windows (do not block them).
                - If a selectedProducts entry matches a type you are placing, prefer using its exact
                  width/depth/height/productId/styleTags instead of inventing your own. variantId is
                  server-owned catalog metadata: do not generate or modify it.
                - Return JSON only — no markdown code fences, no explanation text.

                Room and context JSON:
                %s
                """.formatted(inputJson);
    }

    private void normalizeSupportedFurniture(List<Furniture> furniture) {
        for (Furniture dependent : furniture) {
            if (dependent.getStatus() == FurnitureStatus.DELETED || dependent.getPosition() == null) continue;
            String dependentType = GeneratedFurnitureCatalog.get().normalizeType(dependent.getType());
            String supporterType = switch (dependentType) {
                case "monitor" -> "desk";
                case "tv" -> "media_console";
                default -> null;
            };
            if (supporterType == null) continue;

            Furniture nearestSupporter = null;
            double nearestDistanceSquared = Double.POSITIVE_INFINITY;
            for (Furniture supporter : furniture) {
                if (supporter == dependent || supporter.getStatus() == FurnitureStatus.DELETED
                        || supporter.getPosition() == null
                        || !supporterType.equals(GeneratedFurnitureCatalog.get().normalizeType(supporter.getType()))) {
                    continue;
                }
                double deltaX = supporter.getPosition().getX() - dependent.getPosition().getX();
                double deltaZ = supporter.getPosition().getZ() - dependent.getPosition().getZ();
                double distanceSquared = deltaX * deltaX + deltaZ * deltaZ;
                if (distanceSquared < nearestDistanceSquared) {
                    nearestSupporter = supporter;
                    nearestDistanceSquared = distanceSquared;
                }
            }
            if (nearestSupporter == null) continue;

            dependent.setPosition(new Position(nearestSupporter.getPosition().getX(),
                    nearestSupporter.getPosition().getZ()));
            dependent.setRotation(nearestSupporter.getRotation());
        }
    }

    private Map<String, Object> roomPayload(Room room) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("width", room.getWidth());
        payload.put("depth", room.getDepth());
        payload.put("height", room.getHeight());
        payload.put("walls", room.getWalls().stream().map(this::wallPayload).toList());
        payload.put("openings", room.getOpenings().stream().map(this::openingPayload).toList());
        return payload;
    }

    private Map<String, Object> wallPayload(Wall wall) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", wall.getId());
        payload.put("start", Map.of("x", wall.getStart().getX(), "z", wall.getStart().getZ()));
        payload.put("end", Map.of("x", wall.getEnd().getX(), "z", wall.getEnd().getZ()));
        return payload;
    }

    private Map<String, Object> openingPayload(Opening opening) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", opening.getType());
        payload.put("wall", opening.getWall());
        payload.put("offset", opening.getOffset());
        payload.put("width", opening.getWidth());
        return payload;
    }

    private Map<String, Object> furniturePayload(Furniture item) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", item.getId());
        payload.put("type", item.getType());
        payload.put("label", item.getLabel());
        payload.put("width", item.getWidth());
        payload.put("depth", item.getDepth());
        payload.put("height", item.getHeight());
        payload.put("position", Map.of("x", item.getPosition().getX(), "z", item.getPosition().getZ()));
        payload.put("rotation", item.getRotation());
        payload.put("status", item.getStatus());
        payload.put("productId", item.getProductId());
        payload.put("variantId", item.getVariantId());
        payload.put("styleTags", item.getStyleTags());
        return payload;
    }

    private Map<String, Object> productPayload(MockProduct product) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("productId", product.getProductId());
        payload.put("variantId", product.getVariantId());
        payload.put("type", product.getType());
        payload.put("name", product.getName());
        payload.put("width", product.getWidth());
        payload.put("depth", product.getDepth());
        payload.put("height", product.getHeight());
        payload.put("styleTags", product.getStyleTags());
        return payload;
    }

    private JsonNode parseJson(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) {
            throw new CustomException(ErrorCode.RECOMMENDATION_FAILED);
        }
        try {
            JsonNode root = objectMapper.readTree(rawResponse);
            if (root == null || !root.isObject()) {
                throw new CustomException(ErrorCode.RECOMMENDATION_FAILED);
            }
            return root;
        } catch (JsonProcessingException e) {
            throw new CustomException(ErrorCode.RECOMMENDATION_FAILED);
        }
    }

    private List<Furniture> toFurnitureList(JsonNode root, List<Furniture> activeExisting) {
        Map<String, Furniture> existingById = activeExisting.stream()
                .collect(Collectors.toMap(Furniture::getId, item -> item, (first, ignored) -> first));
        JsonNode furnitureNode = root.path("furniture");

        if (!furnitureNode.isArray() || furnitureNode.isEmpty()) {
            return List.of();
        }

        List<Furniture> result = new ArrayList<>();
        Set<String> responseIds = new java.util.HashSet<>();
        for (JsonNode item : furnitureNode) {
            String id = text(item, "id");
            if (id.isBlank() || !responseIds.add(id)) {
                throw new CustomException(ErrorCode.RECOMMENDATION_FAILED);
            }
            result.add(toFurniture(item, existingById));
        }
        if (!responseIds.containsAll(existingById.keySet())) {
            throw new CustomException(ErrorCode.RECOMMENDATION_FAILED);
        }
        return result;
    }

    private Furniture toFurniture(JsonNode item, Map<String, Furniture> existingById) {
        String id = text(item, "id");
        JsonNode positionNode = item.path("position");

        if (id.isBlank() || !positionNode.has("x") || !positionNode.has("z")) {
            throw new CustomException(ErrorCode.RECOMMENDATION_FAILED);
        }

        Furniture existing = existingById.get(id);
        if (existing != null) {
            return toExistingFurniture(item, positionNode, existing);
        }

        return toNewFurniture(item, positionNode, id);
    }

    private Furniture toExistingFurniture(JsonNode item, JsonNode positionNode, Furniture existing) {
        verifyExistingFurnitureInvariant(item, existing);
        double rotation = RotationUtils.snapToRightAngle(item.path("rotation").asDouble(0));

        return new Furniture(
                existing.getId(), existing.getType(), existing.getLabel(),
                existing.getWidth(),
                existing.getDepth(),
                existing.getHeight(),
                new Position(positionNode.path("x").asDouble(), positionNode.path("z").asDouble()),
                rotation,
                existing.getStatus(),
                existing.getProductId(),
                existing.getStyleTags(),
                existing.getVariantId()
        ).copyMountFrom(existing);
    }

    // A catalog match (when the LLM names a real productId) is preferred —
    // its width/depth/height/productId/variantId/styleTags are trustworthy
    // catalog data. But requiring one unconditionally (throwing when the id
    // doesn't resolve) meant *any* newly recommended piece the LLM invents
    // for a type with no matching selectedProducts entry — the common case,
    // since the catalog only has one product for bed/chair/storage/rug/lamp
    // each — silently failed the whole recommendation and fell back to
    // rule-based. Falling back to the LLM's own proposed type/dimensions/
    // label/styleTags keeps this service actually able to add new furniture;
    // productId/variantId simply stay null for a piece with no real catalog
    // backing, since those are server-owned identifiers the LLM has no
    // authority to invent (see buildPrompt's own instruction on this).
    private Furniture toNewFurniture(JsonNode item, JsonNode positionNode, String id) {
        MockProduct product = findCatalogProduct(text(item, "productId"));
        double rotation = RotationUtils.snapToRightAngle(item.path("rotation").asDouble(0));
        Position position = new Position(positionNode.path("x").asDouble(), positionNode.path("z").asDouble());

        if (product != null) {
            String canonicalType = canonicalOrLegacyType(product.getType());
            if (canonicalType == null) {
                throw new CustomException(ErrorCode.RECOMMENDATION_FAILED);
            }
            return new Furniture(
                    id, canonicalType, product.getName(),
                    product.getWidth(),
                    product.getDepth(),
                    product.getHeight(),
                    position,
                    rotation,
                    FurnitureStatus.RECOMMENDED,
                    product.getProductId(),
                    product.getStyleTags(),
                    product.getVariantId()
            );
        }

        String type = canonicalOrLegacyType(text(item, "type"));
        if (type == null || !item.path("width").isNumber() || !item.path("depth").isNumber() || !item.path("height").isNumber()) {
            throw new CustomException(ErrorCode.RECOMMENDATION_FAILED);
        }
        String label = text(item, "label");

        return new Furniture(
                id, type, label.isBlank() ? type : label,
                item.path("width").asDouble(),
                item.path("depth").asDouble(),
                item.path("height").asDouble(),
                position,
                rotation,
                FurnitureStatus.RECOMMENDED,
                null,
                toStringList(item.path("styleTags")),
                null
        );
    }

    private MockProduct findCatalogProduct(String productId) {
        if (productId.isBlank()) {
            return null;
        }
        try {
            return mockProductService.findByProductId(productId);
        } catch (CustomException e) {
            return null;
        }
    }

    private FurnitureStatus parseStatus(String raw, FurnitureStatus fallback) {
        if (raw.isBlank()) {
            return fallback;
        }
        try {
            return FurnitureStatus.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private void verifyExistingFurnitureInvariant(JsonNode item, Furniture existing) {
        if (!existing.getType().equals(text(item, "type"))
                || !existing.getLabel().equals(text(item, "label"))
                || !sameNumber(existing.getWidth(), item.path("width"))
                || !sameNumber(existing.getDepth(), item.path("depth"))
                || !sameNumber(existing.getHeight(), item.path("height"))
                || !existing.getStatus().name().equals(text(item, "status"))
                || !sameNullableText(existing.getProductId(), item.get("productId"))
                || !sameNullableText(existing.getVariantId(), item.get("variantId"))
                || !existing.getStyleTags().equals(toStringList(item.get("styleTags")))) {
            throw new CustomException(ErrorCode.RECOMMENDATION_FAILED);
        }
    }

    private boolean sameNumber(double expected, JsonNode node) {
        return node != null && node.isNumber() && Double.compare(expected, node.asDouble()) == 0;
    }

    private boolean sameNullableText(String expected, JsonNode node) {
        return expected == null ? node != null && node.isNull()
                : node != null && node.isTextual() && expected.equals(node.asText());
    }

    private String canonicalOrLegacyType(String rawType) {
        String normalized = GeneratedFurnitureCatalog.get().normalizeType(rawType);
        if (normalized == null) {
            return null;
        }
        boolean canonical = GeneratedFurnitureCatalog.get().products().stream()
                .anyMatch(product -> product.getType().equals(normalized));
        return canonical || EXPLICIT_LEGACY_TYPES.contains(normalized) ? normalized : null;
    }

    private void verifyRequestedTypeCounts(AgentContext context, List<Furniture> existing,
                                           List<Furniture> candidate) {
        Set<String> existingIds = existing.stream().map(Furniture::getId).collect(Collectors.toSet());
        long preservedExistingCount = candidate.stream()
                .map(Furniture::getId)
                .filter(existingIds::contains)
                .count();
        if (preservedExistingCount != existingIds.size()) {
            throw new CustomException(ErrorCode.RECOMMENDATION_FAILED);
        }

        Map<String, Long> requestedCounts = new LinkedHashMap<>();
        List<String> requested = new ArrayList<>(context.getRequiredItems());
        requested.addAll(context.getOptionalItems());
        for (String requestedType : requested) {
            String normalized = canonicalOrLegacyType(requestedType);
            if (normalized == null) {
                throw new CustomException(ErrorCode.RECOMMENDATION_FAILED);
            }
            requestedCounts.merge(normalized, 1L, Long::sum);
        }

        Map<String, Long> candidateCounts = candidate.stream()
                .filter(item -> item.getStatus() != FurnitureStatus.DELETED)
                .filter(item -> !existingIds.contains(item.getId()))
                .map(item -> canonicalOrLegacyType(item.getType()))
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.groupingBy(type -> type, LinkedHashMap::new, Collectors.counting()));
        if (requestedCounts.entrySet().stream()
                .anyMatch(entry -> candidateCounts.getOrDefault(entry.getKey(), 0L) < entry.getValue())) {
            throw new CustomException(ErrorCode.RECOMMENDATION_FAILED);
        }
    }

    private List<String> toStringList(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        node.forEach(child -> {
            if (child.isTextual()) {
                values.add(child.asText());
            }
        });
        return values;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText().trim() : "";
    }
}
