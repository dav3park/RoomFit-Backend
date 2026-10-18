package com.roomfit.consultation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ConsultationPhotoInventoryTest {
    final ObjectMapper mapper=new ObjectMapper();
    final DesignConsultationService service=new DesignConsultationService((com.roomfit.llm.OpenAiCompatibleLlmClient)null,mapper);
    Map<String,Object> furniture(String type,int count){return Map.of("type",type,"count",count,"evidence","사진에 보이는 "+type);}
    Map<String,Object> image(int index,List<Map<String,Object>> furniture){return Map.of("imageIndex",index,"furniture",furniture,"limitation",furniture.isEmpty()?"가구를 식별하기 어려운 사진입니다.":"");}
    Map<String,Object> condition(String description,String source,String evidence,String kind,String value,int count){
        var item=new LinkedHashMap<String,Object>();item.put("description",description);item.put("source",source);item.put("evidence",evidence);
        item.put("imageIndex",source.equals("REFERENCE_IMAGE")?1:null);item.put("priority",source.equals("USER_TEXT")?"REQUIRED":"PREFERRED");
        item.put("effects",List.of(Map.of("kind",kind,"value",value,"count",count)));item.put("unresolved",List.of());return item;
    }
    DesignConsultationResult parse(String text,int images,List<Map<String,Object>> requirements,List<Map<String,Object>> inventory)throws Exception{
        return ConsultationAnalysisPlan.parse(mapper.writeValueAsString(Map.of("summary","사진의 취향입니다.","questions",List.of(),"requirements",requirements,"photoInventory",inventory)),
                new DesignConsultationRequest("r",text,List.of(),true),images,service,mapper);
    }
    @Test void turnsEveryObservedFurnitureTypeIntoAnEditableImagePreference()throws Exception{
        var result=parse("사진처럼 꾸며줘",1,List.of(condition("모던 스타일","REFERENCE_IMAGE","간결한 형태","STYLE","modern",0),
                condition("베이지 색감","REFERENCE_IMAGE","베이지 침구","PALETTE","beige",0)),
                List.of(image(1,List.of(furniture("bed",1),furniture("sofa",1),furniture("rug",1),furniture("curtain_blind",1),furniture("multi_table",1),furniture("mood_lamp",1)))));
        assertThat(result.requirements()).hasSize(8);
        assertThat(result.preparation().conditions().stream().flatMap(c->c.effects().stream()).filter(e->e.kind().equals("REQUIRE_TYPE")).map(ConsultationPreparation.Effect::value))
                .containsExactlyInAnyOrder("bed","sofa","rug","curtain_blind","multi_table","mood_lamp");
        assertThat(result.requirements()).allMatch(r->r.source().equals("REFERENCE_IMAGE")&&r.priority().equals("PREFERRED")&&r.imageIndex()==1);
    }
    @Test void repeatedViewsDoNotAddCountsAndExplicitTextWins()throws Exception{
        var result=parse("옷장은 빼고 소파는 1개만",2,List.of(condition("옷장 제외","USER_TEXT","옷장은 빼고","EXCLUDE_TYPE","wardrobe",0),
                condition("소파 1개","USER_TEXT","소파는 1개만","REQUIRE_TYPE","sofa",1)),
                List.of(image(1,List.of(furniture("sofa",2),furniture("wardrobe",1),furniture("bed",1))),image(2,List.of(furniture("bed",1)))));
        assertThat(result.requirements()).hasSize(3);
        var effects=result.preparation().conditions().stream().flatMap(c->c.effects().stream()).toList();
        assertThat(effects).containsExactlyInAnyOrder(new ConsultationPreparation.Effect("EXCLUDE_TYPE","wardrobe",0),
                new ConsultationPreparation.Effect("REQUIRE_TYPE","sofa",1),new ConsultationPreparation.Effect("REQUIRE_TYPE","bed",1));
    }
    @Test void appliesExplicitOverridesBeforeEnforcingTheFinalConditionLimit()throws Exception{
        var types=List.of("bed","sofa","rug","curtain_blind","plant","multi_table","mood_lamp");
        var requirements=types.stream().map(t->condition(t+" 1개","USER_TEXT",t,"REQUIRE_TYPE",t,1)).toList();
        var result=parse(String.join(" ",types),1,requirements,List.of(image(1,types.stream().map(t->furniture(t,1)).toList())));
        assertThat(result.requirements()).hasSize(7).allMatch(r->r.source().equals("USER_TEXT"));
    }
    @Test void preservesAnExplicitCountEvenWhenItsMaterialDetailIsUnresolved()throws Exception{
        var explicit=condition("소파 1개와 리넨 소재","USER_TEXT","소파 1개","REQUIRE_TYPE","sofa",1);explicit.put("unresolved",List.of("리넨 소재는 별도 확인이 필요합니다."));
        var result=parse("소파 1개와 리넨 소재",1,List.of(explicit),List.of(image(1,List.of(furniture("sofa",2)))));
        assertThat(result.requirements()).hasSize(1);
        assertThat(result.preparation().conditions().getFirst().effects().getFirst().count()).isEqualTo(1);
        assertThat(result.preparation().conditions().getFirst().unresolved()).isNotEmpty();
    }
    @Test void equivalentGeneratedFurnitureRetainsItsPhotoEvidence()throws Exception{
        var result=parse("사진처럼",1,List.of(condition("침대 1개","INFERENCE","원룸 제안","REQUIRE_TYPE","bed",1)),List.of(image(1,List.of(furniture("bed",1)))));
        assertThat(result.requirements()).hasSize(1);
        assertThat(result.requirements().getFirst().source()).isEqualTo("REFERENCE_IMAGE");
        assertThat(result.requirements().getFirst().imageIndex()).isEqualTo(1);
    }
    @Test void stillRejectsMoreThanTwelveDistinctFinalConditions(){
        var types=List.of("bed","sofa","rug","curtain_blind","plant","multi_table","mood_lamp","desk","desk_chair","tv","monitor","nightstand","wardrobe");
        assertThatThrownBy(()->parse(String.join(" ",types),1,types.subList(0,7).stream().map(t->condition(t,"USER_TEXT",t,"REQUIRE_TYPE",t,1)).toList(),
                List.of(image(1,types.subList(7,13).stream().map(t->furniture(t,1)).toList()))))
                .isInstanceOf(ConsultationValidationException.class).hasMessage("PHOTO_CONDITION_LIMIT");
    }
    @Test void refusesMissingImageCoverageRatherThanAcceptingOnlyStyleAndColor(){
        assertThatThrownBy(()->parse("사진처럼",2,List.of(condition("모던","REFERENCE_IMAGE","간결한 형태","STYLE","modern",0)),List.of(image(1,List.of(furniture("bed",1))))))
                .isInstanceOf(ConsultationValidationException.class).hasMessage("PHOTO_INVENTORY_COVERAGE");
    }
    @Test void permitsAnUnrecognizableImageWithoutInventingFurniture()throws Exception{
        var result=parse("사진처럼",1,List.of(condition("모던","REFERENCE_IMAGE","간결한 형태","STYLE","modern",0)),List.of(image(1,List.of())));
        assertThat(result.requirements()).hasSize(1);
        assertThat(result.questions()).anyMatch(q->q.contains("사진 1"));
    }
    @Test void rejectsUnknownTypesInvalidCountsAndInventedImageIndexes(){
        for(var inventory:List.of(image(1,List.of(furniture("spaceship",1))),image(1,List.of(furniture("bed",0))),image(2,List.of(furniture("bed",1)))))
            assertThatThrownBy(()->parse("사진처럼",1,List.of(),List.of(inventory))).isInstanceOf(ConsultationValidationException.class);
    }
    @Test void repairsMissingPhotoInventoryAndReturnsTheObservedFurniture()throws Exception{
        var client=org.mockito.Mockito.mock(com.roomfit.llm.OpenAiCompatibleLlmClient.class);
        var style=condition("모던","REFERENCE_IMAGE","간결한 형태","STYLE","modern",0);
        var answer=Map.of("summary","사진 취향","requirements",List.of(style),"questions",List.of());
        var complete=new LinkedHashMap<String,Object>(answer);complete.put("photoInventory",List.of(image(1,List.of(furniture("bed",1),furniture("curtain_blind",1)))));
        org.mockito.Mockito.when(client.completeStructuredContent(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyMap(),org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(mapper.writeValueAsString(answer),mapper.writeValueAsString(complete));
        var result=new DesignConsultationService(client,mapper).analyze(new DesignConsultationRequest("r","사진처럼",List.of(),true),
                List.of(DesignConsultationServiceTest.photo("room.png")));
        assertThat(result.preparation().conditions().stream().flatMap(c->c.effects().stream()).map(ConsultationPreparation.Effect::value))
                .containsExactlyInAnyOrder("modern","bed","curtain_blind");
        org.mockito.Mockito.verify(client,org.mockito.Mockito.times(2)).completeStructuredContent(org.mockito.ArgumentMatchers.contains("photoInventory"),org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(ConsultationAnalysisPlan.schema(1)),org.mockito.ArgumentMatchers.anyString());
    }
}
