package com.roomfit.room.controller;

import com.roomfit.client.ClientScopeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class RoomSamplesControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void getSampleRooms_returnsSampleRoomList() throws Exception {
        mockMvc.perform(get("/api/rooms/samples"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.error").value(nullValue()))
                .andExpect(jsonPath("$.data", notNullValue()))
                // canonical Sample Room + L Studio + Alcove Studio — see
                // RoomSampleDataInitializer. Seeded in that order, so the
                // canonical room keeps the lowest id and stays data[0].
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].roomId").value(1))
                .andExpect(jsonPath("$.data[0].room.width").value(5.8))
                .andExpect(jsonPath("$.data[0].room.depth").value(5.4))
                .andExpect(jsonPath("$.data[0].room.height").value(2.7))
                .andExpect(jsonPath("$.data[0].room.unit").value("meter"))
                .andExpect(jsonPath("$.data[0].openings[*].type").value(hasItems("door", "window")))
                .andExpect(jsonPath("$.data[0].furniture[?(@.id == 'wardrobe-1')].rotation").value(hasItems(90.0)))
                .andExpect(jsonPath("$.data[0].furniture[?(@.id == 'wardrobe-1')].position.x").value(hasItems(5.39)))
                .andExpect(jsonPath("$.data[0].furniture[*].status").value(hasItems("EXISTING")))
                // The two non-rectangular samples must carry a real wall
                // polygon (more than the 4 walls a plain rectangle has),
                // not an empty/collapsed walls[] array.
                .andExpect(jsonPath("$.data[1].name").value("Sample Room - L Studio"))
                .andExpect(jsonPath("$.data[1].walls.length()").value(6))
                .andExpect(jsonPath("$.data[2].name").value("Sample Room - Alcove Studio"))
                .andExpect(jsonPath("$.data[2].walls.length()").value(8));

        mockMvc.perform(get("/api/rooms/samples")
                        .header(ClientScopeService.HEADER_NAME, "11111111-1111-4111-8111-111111111111"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].name").value("Sample Room"));
    }
}
