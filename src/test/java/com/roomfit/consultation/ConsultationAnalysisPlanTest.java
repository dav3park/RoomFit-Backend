package com.roomfit.consultation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ConsultationAnalysisPlanTest {
    final ObjectMapper mapper=new ObjectMapper();
    final DesignConsultationService service=new DesignConsultationService((com.roomfit.llm.OpenAiCompatibleLlmClient)null,mapper);
    final DesignConsultationRequest request=new DesignConsultationRequest("r","식물이 많은 따뜻한 분위기에서 공부를 하고 싶어. 책상하고 책장도 필요해. 옷장은 필요 없어",List.of());
    Map<String,Object> effect(String kind,String value,int count){return Map.of("kind",kind,"value",value,"count",count);}
    Map<String,Object> item(String description,String source,String evidence,List<Map<String,Object>> effects){
        var item=new LinkedHashMap<String,Object>();item.put("description",description);item.put("source",source);item.put("evidence",evidence);item.put("imageIndex",null);item.put("priority",source.equals("USER_TEXT")?"REQUIRED":"PREFERRED");item.put("effects",effects);item.put("unresolved",List.of());return item;
    }
    String response(List<Map<String,Object>> items)throws Exception{return mapper.writeValueAsString(Map.of("summary","공부할 내추럴 공간과 화분 3개를 제안합니다.","requirements",items,"questions",List.of()));}
    @Test void returnsSeparatedCanonicalIntentAndEffectsInOneResponse()throws Exception{
        var result=ConsultationAnalysisPlan.parse(response(List.of(
                item("화분 3개(원룸 기준 제안)","INFERENCE","식물이 많은 것을 화분 3개로 제안",List.of(effect("REQUIRE_TYPE","plant",3))),
                item("내추럴 스타일(따뜻한 분위기로 해석)","INFERENCE","따뜻한 분위기를 내추럴로 해석",List.of(effect("STYLE","natural",0))),
                item("책상 1개","USER_TEXT","책상",List.of(effect("REQUIRE_TYPE","desk",1))),
                item("책장 1개","USER_TEXT","책장",List.of(effect("REQUIRE_TYPE","bookshelf",1))),
                item("옷장 제외","USER_TEXT","옷장은 필요 없어",List.of(effect("EXCLUDE_TYPE","wardrobe",0))))),request,0,service,mapper);
        assertThat(result.requirements()).hasSize(5);
        assertThat(result.preparation().conditions()).hasSize(5);
        assertThat(result.preparation().conditions().getFirst().effects().getFirst().count()).isEqualTo(3);
        assertThat(result.requirements().get(1).value()).isEqualTo("natural");
        assertThat(result.preparation().conditions().getLast().effects().getFirst().kind()).isEqualTo("EXCLUDE_TYPE");
    }
    @Test void deduplicatesEquivalentEffectsWithoutLosingDistinctRequests()throws Exception{
        var first=item("내추럴 스타일","INFERENCE","따뜻한 분위기에서 추정",List.of(effect("STYLE","natural",0)));
        var result=ConsultationAnalysisPlan.parse(response(List.of(first,first,item("화분 3개","INFERENCE","식물이 많은 것을 해석",List.of(effect("REQUIRE_TYPE","plant",3))))),request,0,service,mapper);
        assertThat(result.requirements()).hasSize(2);
        assertThat(result.preparation().conditions()).extracting(ConsultationPreparation.Condition::id).containsExactly("0","1");
    }
    @Test void explicitStyleAndPlantCountOverrideInferredDefaults()throws Exception{
        var explicit=new DesignConsultationRequest("r","모던 스타일로 화분 1개만 필요해",List.of());
        var result=ConsultationAnalysisPlan.parse(response(List.of(
                item("내추럴 제안","INFERENCE","따뜻함으로 추정",List.of(effect("STYLE","natural",0))),
                item("화분 3개 제안","INFERENCE","원룸 기준",List.of(effect("REQUIRE_TYPE","plant",3))),
                item("모던 스타일","USER_TEXT","모던 스타일",List.of(effect("STYLE","modern",0))),
                item("화분 1개","USER_TEXT","화분 1개",List.of(effect("REQUIRE_TYPE","plant",1))))),explicit,0,service,mapper);
        assertThat(result.requirements()).hasSize(2);
        assertThat(result.preparation().conditions().getFirst().effects().getFirst().value()).isEqualTo("modern");
        assertThat(result.preparation().conditions().getLast().effects().getFirst().count()).isEqualTo(1);
    }
    @Test void rejectsFabricatedEvidenceAndUntrustedKeepIds()throws Exception{
        for(var bad:List.of(item("책상","USER_TEXT","실제로 없는 근거",List.of(effect("REQUIRE_TYPE","desk",1))),
                item("가구 유지","INFERENCE","사진을 보고 추정",List.of(effect("KEEP_ITEM","invented",0)))))
            assertThatThrownBy(()->ConsultationAnalysisPlan.parse(response(List.of(bad)),request,0,service,mapper)).isInstanceOf(IllegalArgumentException.class);
    }
}
