package com.roomfit.placement;

import com.roomfit.agent.domain.AgentContext;
import com.roomfit.agent.domain.LifestyleGoal;
import com.roomfit.product.catalog.GeneratedFurnitureCatalog;
import com.roomfit.room.Furniture;
import com.roomfit.room.FurnitureStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ScoreService {

    public ScoreSummary calculate(AgentContext context, List<Furniture> furniture,
                                   ValidationResult validationResult) {
        int collisionScore = validationResult.isCollisionFree() ? 100 : 60;
        int boundaryScore = validationResult.isBoundaryValid() ? 100 : 60;
        int doorWindowScore = validationResult.isDoorClearance() && validationResult.isWindowClearance() ? 100 : 70;
        int pathScore = validationResult.isPathSecured() ? 100 : 70;
        // A blank/direct-placement layout has no AgentContext (no lifestyle
        // goal or style preference was ever collected) — goal/style scoring
        // has nothing to compare against, so it falls back to the same
        // neutral default already used when a context carries no tags below.
        int goalScore = context == null ? 80 : calculateGoalScore(context, furniture);
        int styleScore = context == null ? 80 : calculateStyleScore(context, furniture);

        return new ScoreSummary(collisionScore, boundaryScore, doorWindowScore,
                pathScore, goalScore, styleScore);
    }

    private int calculateGoalScore(AgentContext context, List<Furniture> furniture) {
        if (context.getLifestyleGoal() != LifestyleGoal.STUDY_FOCUSED) {
            return 80;
        }

        Set<String> furnitureTypes = furniture.stream()
                .filter(this::active)
                .map(item -> GeneratedFurnitureCatalog.get().normalizeType(item.getType()))
                .collect(Collectors.toSet());

        return furnitureTypes.containsAll(Set.of("desk", "desk_chair", "mood_lamp")) ? 95 : 80;
    }

    private int calculateStyleScore(AgentContext context, List<Furniture> furniture) {
        Set<String> contextTags = Set.copyOf(context.getStyleTags());
        Set<String> furnitureTags = furniture.stream()
                .filter(this::active)
                .flatMap(item -> item.getStyleTags().stream())
                .collect(Collectors.toSet());

        if (contextTags.isEmpty() || furnitureTags.isEmpty()) {
            return 80;
        }

        long overlapCount = furnitureTags.stream()
                .filter(contextTags::contains)
                .count();

        if (overlapCount >= 3) {
            return 95;
        }
        if (overlapCount > 0) {
            return 90;
        }
        return 80;
    }

    private boolean active(Furniture furniture) {
        return furniture.getStatus() != FurnitureStatus.DELETED;
    }
}
