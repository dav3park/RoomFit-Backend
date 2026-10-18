package com.roomfit.consultation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ConsultationPreparationTest {
    final ObjectMapper mapper = new ObjectMapper();
    final ConsultationPreparation.Request request = new ConsultationPreparation.Request("r1", List.of(
            new ConsultationPreparation.Input("c1", "책상과 책장 한 개씩 필요해요", "REQUIRED"),
            new ConsultationPreparation.Input("c2", "옷장은 제외해요", "REQUIRED")), List.of(new DesignConsultationRequest.Item("bed-1", "침대", "bed")));
    Map<String,Object> effect(String kind, String value, int count) { return Map.of("kind",kind,"value",value,"count",count); }
    Map<String,Object> condition(String id, List<Map<String,Object>> effects, List<String> unresolved) {
        return Map.of("id",id,"effects",effects,"unresolved",unresolved);
    }
    String json(List<Map<String,Object>> conditions) throws Exception { return mapper.writeValueAsString(Map.of("conditions",conditions)); }
    @Test void preservesRequiredFurnitureAndExclusionsByCondition() throws Exception {
        var result = ConsultationPreparation.parse(json(List.of(
                condition("c1",List.of(effect("REQUIRE_TYPE","desk",1),effect("REQUIRE_TYPE","bookshelf",1)),List.of()),
                condition("c2",List.of(effect("EXCLUDE_TYPE","wardrobe",0)),List.of()))),request,mapper);
        assertThat(result.revision()).isEqualTo("r1");
        assertThat(result.conditions().getFirst().effects()).hasSize(2);
        assertThat(result.conditions().getLast().effects().getFirst().value()).isEqualTo("wardrobe");
    }
    @Test void rejectsMissingDuplicateOrInventedConditionIds() throws Exception {
        for(var conditions:List.of(List.of(condition("c1",List.of(effect("PURPOSE","업무 / 공부",0)),List.of())),
                List.of(condition("c1",List.of(),List.of("확인")),condition("c1",List.of(),List.of("확인"))),
                List.of(condition("c1",List.of(),List.of("확인")),condition("invented",List.of(),List.of("확인"))))) {
            assertThatThrownBy(()->ConsultationPreparation.parse(json(conditions),request,mapper)).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void rejectsUnknownFurnitureBadCountsAndFabricatedKeepIds() throws Exception {
        for(var bad:List.of(effect("REQUIRE_TYPE","unknown",1),effect("REQUIRE_TYPE","plant",0),
                effect("REQUIRE_TYPE","plant",25),effect("KEEP_ITEM","fake-id",0),effect("PALETTE","neon",0))) {
            assertThatThrownBy(()->ConsultationPreparation.parse(json(List.of(condition("c1",List.of(bad),List.of()),
                    condition("c2",List.of(),List.of("확인 필요")))),request,mapper)).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void retainsUnresolvedDetailsAndRejectsAnEmptySuccess() throws Exception {
        var result=ConsultationPreparation.parse(json(List.of(condition("c1",List.of(effect("REQUIRE_TYPE","desk",1)),List.of("책장의 개수를 알려주세요")),
                condition("c2",List.of(),List.of("대상을 확인해 주세요")))),request,mapper);
        assertThat(result.conditions().getFirst().unresolved()).containsExactly("책장의 개수를 알려주세요");
        assertThatThrownBy(()->ConsultationPreparation.parse(json(List.of(condition("c1",List.of(),List.of()),condition("c2",List.of(),List.of("확인")))),request,mapper))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
