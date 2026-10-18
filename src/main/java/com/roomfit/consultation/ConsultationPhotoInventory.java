package com.roomfit.consultation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** Every observed type becomes a reviewable preference, rather than disappearing behind style/color tags. */
final class ConsultationPhotoInventory {
    private ConsultationPhotoInventory() {}
    private static final Map<String,String> LABELS=Map.ofEntries(
            Map.entry("bed","침대"),Map.entry("sofa","소파"),Map.entry("sofa_bed","소파베드"),Map.entry("rug","러그"),
            Map.entry("curtain_blind","커튼·블라인드"),Map.entry("partition_shelf","파티션"),Map.entry("desk","책상"),
            Map.entry("desk_chair","책상 의자"),Map.entry("bookshelf","책장"),Map.entry("wardrobe","옷장"),
            Map.entry("drawer_chest","서랍장"),Map.entry("full_length_mirror","전신거울"),Map.entry("hanger","행거"),
            Map.entry("media_console","TV장"),Map.entry("monitor","모니터"),Map.entry("mood_lamp","무드 조명"),
            Map.entry("multi_table","다용도 테이블"),Map.entry("nightstand","협탁"),Map.entry("plant","화분"),
            Map.entry("side_table","사이드테이블"),Map.entry("tv","TV"));
    static final int MAX_EXPANDED_CONDITIONS=12+LABELS.size();
    static Map<String,Object> schema(){
        var string=Map.of("type","string");
        return Map.of("type","array","items",ConsultationPreparation.object(Map.of(
                "imageIndex",Map.of("type","integer"),"limitation",string,
                "furniture",Map.of("type","array","items",ConsultationPreparation.object(Map.of(
                        "type",Map.of("type","string","enum",LABELS.keySet().stream().sorted().toList()),
                        "count",Map.of("type","integer"),"evidence",string))))));
    }
    static ObjectNode expand(ObjectNode root,int imageCount){
        if(root.size()!=4||!root.has("summary")||!root.path("requirements").isArray()||!root.path("questions").isArray())invalid("PLAN_FIELDS");
        var inventory=root.get("photoInventory");
        if(inventory==null||!inventory.isArray()||inventory.size()!=imageCount)invalid("PHOTO_INVENTORY_COVERAGE");
        var indexes=new HashSet<Integer>();var observed=new LinkedHashMap<String,JsonNode>();var origins=new HashMap<String,Integer>();
        var questions=root.withArray("questions");
        for(var image:inventory){
            fields(image,Set.of("imageIndex","furniture","limitation"));var index=image.get("imageIndex");
            if(!index.isIntegralNumber()||!index.canConvertToInt()||index.intValue()<1||index.intValue()>imageCount||!indexes.add(index.intValue()))invalid("PHOTO_INVENTORY_COVERAGE");
            var items=image.get("furniture");var limitation=image.get("limitation");
            if(!items.isArray()||items.size()>12||!limitation.isTextual()||limitation.asText().length()>200||items.isEmpty()&&limitation.asText().isBlank())invalid("PHOTO_INVENTORY_ITEMS");
            if(items.isEmpty()&&questions.size()<4)questions.add("사진 "+index.intValue()+": "+limitation.asText()+" 원하는 가구를 설명에 보완해 주세요.");
            var types=new HashSet<String>();
            for(var item:items){
                fields(item,Set.of("type","count","evidence"));var type=item.get("type");var count=item.get("count");var evidence=item.get("evidence");
                if(!type.isTextual()||!LABELS.containsKey(type.asText())||!types.add(type.asText())||!count.isIntegralNumber()||!count.canConvertToInt()||count.intValue()<1||count.intValue()>24
                        ||!evidence.isTextual()||evidence.asText().isBlank()||evidence.asText().length()>200)invalid("PHOTO_INVENTORY_ITEM");
                // Multiple views of the same type are alternatives, never an additive shopping list.
                var previous=observed.get(type.asText());
                if(previous==null||previous.path("count").intValue()<count.intValue()){observed.put(type.asText(),item);origins.put(type.asText(),index.intValue());}
            }
        }
        var requirements=root.withArray("requirements");
        for(var entry:observed.entrySet()){
            var condition=requirements.addObject();condition.put("description",LABELS.get(entry.getKey())+" "+entry.getValue().path("count").intValue()+"개를 사진의 가구 구성으로 제안합니다.");
            condition.put("source","REFERENCE_IMAGE");condition.put("imageIndex",origins.get(entry.getKey()));condition.put("priority","PREFERRED");condition.put("evidence",entry.getValue().path("evidence").asText());
            condition.putArray("effects").addObject().put("kind","REQUIRE_TYPE").put("value",entry.getKey()).put("count",entry.getValue().path("count").intValue());condition.putArray("unresolved");
        }
        root.remove("photoInventory");return root;
    }
    private static void fields(JsonNode node,Set<String> fields){if(!node.isObject()||node.size()!=fields.size()||fields.stream().anyMatch(f->!node.has(f)))invalid("PHOTO_INVENTORY_FIELDS");}
    private static void invalid(String rule){throw new ConsultationValidationException(rule);}
    static final String INSTRUCTIONS="""
            Inspect EVERY attached reference image before summarizing. photoInventory must contain one entry for each supplied imageIndex.
            List the clearly visible major supported furniture types in that image, each with a proposed total count and concrete visual evidence.
            Check bed/sofa, desk/chair, tables, storage, rug, curtains/blinds, partition, lighting and plants; include ONLY actually visible items.
            A room photo with a bed, sofa, rug and curtains must list all four types, not just STYLE and PALETTE.
            Different views do not add counts; repeated copies of a photo must not increase furniture quantities.
            Distinguish a room door from a divider and a window frame from curtains. Do not guess hidden furniture, exact dimensions or product IDs.
            If no furniture can be reliably recognized, return an empty furniture array and explain the limitation; otherwise limitation may be empty.
            Furniture observations become separate editable REFERENCE_IMAGE/PREFERRED conditions on the server. Do not repeat their REQUIRE_TYPE effects
            in requirements: use requirements for style, palette, purpose, explicit text wishes and unsupported details. Explicit text exclusions/counts win.
            Budget the combined distinct conditions (requirements + observed furniture types) to 12. Prioritize explicit text and major visible furniture
            over minor decor; do not collapse major observed types into a generic style/color condition to shorten the answer.
            Example: beige room with bed, sofa, rug, curtain, coffee table and floor lamp -> six photoInventory furniture entries
            (bed,sofa,rug,curtain_blind,multi_table,mood_lamp) plus separate STYLE/PALETTE requirements, each with its own evidence.
            """;
}
