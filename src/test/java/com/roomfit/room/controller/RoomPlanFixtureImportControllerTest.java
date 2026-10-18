package com.roomfit.room.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class RoomPlanFixtureImportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void kitchenFixturesAndWallCabinet_areImportedInPlace() throws Exception {
        mockMvc.perform(post("/api/rooms/upload")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(kitchenScan("""
                                {"id":"cabinet-1","type":"upper_cabinet","width":1.2,"depth":0.35,"height":0.7,
                                 "position":{"x":1.0,"z":0.2},"rotation":0,"mount":"WALL","elevation":1.45}
                                """)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.furniture.length()").value(4))
                // 싱크대·냉장고는 스캔 위치 그대로(재배치 없음)
                .andExpect(jsonPath("$.data.furniture[?(@.id == 'sink-1')].position.x").value(1.0))
                .andExpect(jsonPath("$.data.furniture[?(@.id == 'sink-1')].position.z").value(0.31))
                .andExpect(jsonPath("$.data.furniture[?(@.id == 'sink-1')].label").value("싱크대"))
                .andExpect(jsonPath("$.data.furniture[?(@.id == 'fridge-1')].type").value("refrigerator"))
                // 싱크대 위에 겹친 상부장도 높이가 달라 충돌 없이 그대로 남는다
                .andExpect(jsonPath("$.data.furniture[?(@.id == 'cabinet-1')].mount").value("WALL"))
                .andExpect(jsonPath("$.data.furniture[?(@.id == 'cabinet-1')].elevation").value(1.45))
                .andExpect(jsonPath("$.data.furniture[?(@.id == 'cabinet-1')].position.z").value(0.2))
                // 일반 가구(식탁)는 기존대로 업로드된다
                .andExpect(jsonPath("$.data.furniture[?(@.id == 'table-1')].type").value("multi_table"));
    }

    @Test
    void scannedWallTvWithoutConsole_isKeptAsWallMounted() throws Exception {
        // legacy policy상 tv는 media_console 위(SUPPORT)여야 해서, mount가 없으면 업로드에서 빠졌다.
        mockMvc.perform(post("/api/rooms/upload")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(kitchenScan("""
                                {"id":"tv-1","type":"tv","width":1.2,"depth":0.08,"height":0.7,
                                 "position":{"x":1.75,"z":3.39},"rotation":180,"mount":"WALL","elevation":1.0}
                                """)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.furniture[?(@.id == 'tv-1')].mount").value("WALL"))
                .andExpect(jsonPath("$.data.importWarnings[?(@.entityId == 'tv-1')]").isEmpty());
    }

    @Test
    void wallMountWithoutElevation_isRejected() throws Exception {
        mockMvc.perform(post("/api/rooms/upload")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(kitchenScan("""
                                {"id":"cabinet-1","type":"upper_cabinet","width":1.2,"depth":0.35,"height":0.7,
                                 "position":{"x":1.0,"z":0.2},"rotation":0,"mount":"WALL"}
                                """)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST_BODY"));
    }

    @Test
    void unknownMount_isRejected() throws Exception {
        mockMvc.perform(post("/api/rooms/upload")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(kitchenScan("""
                                {"id":"cabinet-1","type":"upper_cabinet","width":1.2,"depth":0.35,"height":0.7,
                                 "position":{"x":1.0,"z":0.2},"rotation":0,"mount":"CEILING","elevation":1.4}
                                """)))
                .andExpect(status().isBadRequest());
    }

    private String kitchenScan(String extraFurniture) {
        return """
                {
                  "name":"kitchen fixture scan",
                  "room":{"width":3.5,"depth":3.5,"height":2.4,"unit":"meter"},
                  "walls":[
                    {"id":"w1","start":{"x":0,"z":0},"end":{"x":3.5,"z":0},"height":2.4,"thickness":0.1},
                    {"id":"w2","start":{"x":3.5,"z":0},"end":{"x":3.5,"z":3.5},"height":2.4,"thickness":0.1},
                    {"id":"w3","start":{"x":3.5,"z":3.5},"end":{"x":0,"z":3.5},"height":2.4,"thickness":0.1},
                    {"id":"w4","start":{"x":0,"z":3.5},"end":{"x":0,"z":0},"height":2.4,"thickness":0.1}
                  ],
                  "openings":[],
                  "furniture":[
                    {"id":"sink-1","type":"sink","width":1.2,"depth":0.6,"height":0.9,"position":{"x":1.0,"z":0.31},"rotation":0},
                    {"id":"fridge-1","type":"refrigerator","width":0.7,"depth":0.7,"height":1.8,"position":{"x":3.14,"z":0.36},"rotation":0},
                    {"id":"table-1","type":"multi_table","width":0.8,"depth":0.8,"height":0.45,"position":{"x":1.0,"z":2.6},"rotation":0},
                """ + extraFurniture + """
                  ]
                }
                """;
    }
}
