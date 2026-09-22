package com.roomfit.placement;

import com.roomfit.room.Furniture;
import com.roomfit.room.FurnitureStatus;
import com.roomfit.room.Position;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A ZONE_INTRUSION issue on a wardrobe (its door-swing space blocked by
 * another item) halves collisionScore even though collisionFree stays true —
 * see ScoreService#calculate. Scoped to wardrobes specifically so routine,
 * expected closeness (e.g. a chair at the foot of a bed) doesn't also ding
 * the score — see the "unrelated" test below.
 */
class ScoreServiceZoneIntrusionTest {

    private final ScoreService scoreService = new ScoreService();

    @Test
    void wardrobeZoneIntrusionHalvesCollisionScoreOfAFullyValidLayout() {
        ValidationResult validation = validationResultWithZoneIntrusion(true, "wardrobe-1");

        ScoreSummary score = scoreService.calculate(null, List.of(furniture("wardrobe", "wardrobe-1")), validation);

        assertThat(score.getCollisionScore()).isEqualTo(50);
    }

    @Test
    void wardrobeZoneIntrusionHalvesCollisionScoreEvenWhenAlreadyLowFromACollision() {
        ValidationResult validation = validationResultWithZoneIntrusion(false, "wardrobe-1");

        ScoreSummary score = scoreService.calculate(null, List.of(furniture("wardrobe", "wardrobe-1")), validation);

        assertThat(score.getCollisionScore()).isEqualTo(30);
    }

    @Test
    void zoneIntrusionOnNonWardrobeFurnitureDoesNotAffectCollisionScore() {
        ValidationResult validation = validationResultWithZoneIntrusion(true, "bed-1");

        ScoreSummary score = scoreService.calculate(null, List.of(furniture("bed", "bed-1")), validation);

        assertThat(score.getCollisionScore()).isEqualTo(100);
    }

    @Test
    void noZoneIntrusionLeavesCollisionScoreUntouched() {
        ValidationResult validation = new ValidationResult(true, true, true, true, true, List.of(), List.of());

        ScoreSummary score = scoreService.calculate(null, List.of(furniture("wardrobe", "wardrobe-1")), validation);

        assertThat(score.getCollisionScore()).isEqualTo(100);
    }

    private ValidationResult validationResultWithZoneIntrusion(boolean collisionFree, String furnitureId) {
        ValidationIssue issue = new ValidationIssue(furnitureId, "chair-1", ValidationIssue.Type.ZONE_INTRUSION,
                ValidationIssue.Severity.WARNING, "가구 작동 공간을 다른 가구가 막고 있습니다.");
        return new ValidationResult(collisionFree, true, true, true, true, List.of(), List.of(), List.of(issue));
    }

    private Furniture furniture(String type, String id) {
        return new Furniture(id, type, type, 1.0, 0.6, 2.0, new Position(1, 1), 0, FurnitureStatus.EXISTING);
    }
}
