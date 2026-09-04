package com.roomfit.room;

import com.roomfit.RoomfitApplication;
import com.roomfit.agent.domain.AgentContext;
import com.roomfit.agent.domain.DesignStyle;
import com.roomfit.agent.domain.LifestyleGoal;
import com.roomfit.agent.domain.PreferredColorTone;
import com.roomfit.agent.repository.AgentContextRepository;
import com.roomfit.client.ClientPairingCodeService;
import com.roomfit.client.ClientScope;
import com.roomfit.client.ClientScopeContext;
import com.roomfit.placement.Layout;
import com.roomfit.placement.LayoutRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PersistenceRestartIntegrationTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void roomsConfirmedLayoutContextPairingAndCanonicalSampleSurviveRestart() {
        String browserClientId = UUID.randomUUID().toString();
        String appClientId = UUID.randomUUID().toString();
        StoredIds stored;

        try (ConfigurableApplicationContext first = startContext()) {
            RoomRepository rooms = first.getBean(RoomRepository.class);
            AgentContextRepository contexts = first.getBean(AgentContextRepository.class);
            LayoutRepository layouts = first.getBean(LayoutRepository.class);

            Room sample = canonicalSamples(rooms).getFirst();
            first.getBean(RoomSampleDataInitializer.class).run();
            assertThat(canonicalSamples(rooms)).singleElement().satisfies(reloaded -> {
                assertThat(reloaded.getId()).isEqualTo(sample.getId());
                assertThat(reloaded.getCreatedAt()).isEqualTo(sample.getCreatedAt());
            });
            Room browserRoomToSave = room("Browser Room", browserClientId, "browser-existing");
            browserRoomToSave.setImportMetadata(RoomImportStatus.ACCEPTED_WITH_WARNINGS, List.of(
                    new RoomImportWarning("FURNITURE_REPOSITIONED", "browser-existing", "desk",
                            "strict-safe 배치를 위해 위치를 조정했습니다.", 0.15,
                            new Position(1.0, 1.0), new Position(1.15, 1.0), 0.0, 0.0)));
            Room browserRoom = rooms.save(browserRoomToSave);
            Room appRoom = rooms.save(room("App Room", appClientId, "app-existing"));
            AgentContext context = contexts.save(new AgentContext(
                    browserRoom.getId(), LifestyleGoal.STUDY_FOCUSED,
                    List.of(DesignStyle.MINIMAL, DesignStyle.NATURAL),
                    List.of("desk"), List.of("plant"), List.of(1L),
                    List.of("desk-compact-01"), List.of("minimal", "natural"),
                    PreferredColorTone.BROWN_WOOD));
            Layout layout = new Layout(browserRoom.getId(), context.getId(), List.of(
                    furniture("persisted-layout-furniture", 1.8, 2.2, 35.0)));
            layout.confirm();
            layout = layouts.save(layout);

            ClientScopeContext scope = first.getBean(ClientScopeContext.class);
            scope.set(ClientScope.client(appClientId));
            String pairingCode;
            try {
                pairingCode = first.getBean(ClientPairingCodeService.class).issueOrGetCode();
            } finally {
                scope.clear();
            }

            stored = new StoredIds(browserRoom.getId(), appRoom.getId(), context.getId(), layout.getId(),
                    sample.getId(), sample.getCreatedAt(), pairingCode);
        }

        try (ConfigurableApplicationContext second = startContext()) {
            RoomRepository rooms = second.getBean(RoomRepository.class);
            AgentContextRepository contexts = second.getBean(AgentContextRepository.class);
            LayoutRepository layouts = second.getBean(LayoutRepository.class);

            Room browserRoom = rooms.findById(stored.browserRoomId()).orElseThrow();
            Room appRoom = rooms.findById(stored.appRoomId()).orElseThrow();
            assertThat(browserRoom.getClientScope()).isEqualTo(browserClientId);
            assertThat(appRoom.getClientScope()).isEqualTo(appClientId);
            assertThat(browserRoom.getOpenings()).singleElement().satisfies(opening -> {
                assertThat(opening.getId()).isEqualTo("door-persisted");
                assertThat(opening.getWall()).isEqualTo("south");
            });
            // Regression coverage for the originalx/originalz/normalizedx/normalizedz
            // @Column(name=...) overrides (see RoomImportWarning) — a real save+reload
            // across two separate contexts against a persisted file DB, not just a
            // fresh in-memory schema, so a future accidental removal of those
            // overrides fails this test instead of only surfacing against
            // production's already-existing, differently-named columns.
            assertThat(browserRoom.getImportStatus()).isEqualTo(RoomImportStatus.ACCEPTED_WITH_WARNINGS);
            assertThat(browserRoom.getImportWarnings()).singleElement().satisfies(warning -> {
                assertThat(warning.getCode()).isEqualTo("FURNITURE_REPOSITIONED");
                assertThat(warning.getOriginalX()).isEqualTo(1.0);
                assertThat(warning.getOriginalZ()).isEqualTo(1.0);
                assertThat(warning.getNormalizedX()).isEqualTo(1.15);
                assertThat(warning.getNormalizedZ()).isEqualTo(1.0);
            });
            assertThat(appRoom.getWalls()).singleElement().satisfies(wall -> {
                assertThat(wall.getId()).isEqualTo("wall-persisted");
                assertThat(wall.getEnd().getX()).isEqualTo(5.8);
            });

            AgentContext context = contexts.findById(stored.contextId()).orElseThrow();
            assertThat(context.getDesignStyle()).containsExactly(DesignStyle.MINIMAL, DesignStyle.NATURAL);
            assertThat(context.getRequiredItems()).containsExactly("desk");
            assertThat(context.getOptionalItems()).containsExactly("plant");
            assertThat(context.getSelectedProductIds()).containsExactly("desk-compact-01");
            assertThat(context.getPreferredColorTone()).isEqualTo(PreferredColorTone.BROWN_WOOD);

            Layout layout = layouts.findById(stored.layoutId()).orElseThrow();
            assertThat(layout.isConfirmed()).isTrue();
            assertThat(layout.getConfirmedAt()).isNotNull();
            assertThat(layout.getFurniture()).singleElement().satisfies(item -> {
                assertThat(item.getId()).isEqualTo("persisted-layout-furniture");
                assertThat(item.getProductId()).isEqualTo("desk-compact-01");
                assertThat(item.getVariantId()).isEqualTo("desk-compact");
                assertThat(item.getPosition().getX()).isEqualTo(1.8);
                assertThat(item.getPosition().getZ()).isEqualTo(2.2);
                assertThat(item.getRotation()).isEqualTo(35.0);
            });

            assertThat(second.getBean(ClientPairingCodeService.class).redeem(stored.pairingCode()))
                    .isEqualTo(appClientId);

            List<Room> samples = canonicalSamples(rooms);
            assertThat(samples).singleElement().satisfies(sample -> {
                assertThat(sample.getId()).isEqualTo(stored.sampleId());
                assertThat(sample.getCreatedAt()).isEqualTo(stored.sampleCreatedAt());
                assertThat(sample.getName()).isEqualTo(RoomSampleDataInitializer.CANONICAL_SAMPLE_NAME);
                assertThat(sample.getWidth()).isEqualTo(5.8);
                assertThat(sample.getDepth()).isEqualTo(5.4);
                assertThat(sample.getClientScope()).isNull();
            });
            // canonical Sample Room + the two seeded non-rectangular samples
            // (L Studio, Alcove Studio) — see RoomSampleDataInitializer.
            assertThat(rooms.findBySourceOrderByIdAsc(RoomSource.SAMPLE)).hasSize(3);
            assertThat(rooms.findBySourceOrderByIdAsc(RoomSource.ROOMPLAN))
                    .extracting(Room::getId)
                    .contains(stored.browserRoomId(), stored.appRoomId());
        }
    }

    private ConfigurableApplicationContext startContext() {
        String databasePath = temporaryDirectory.resolve("roomfit-restart").toAbsolutePath().toString();
        return new SpringApplicationBuilder(RoomfitApplication.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--spring.main.banner-mode=off",
                        "--spring.datasource.url=jdbc:h2:file:" + databasePath + ";DB_CLOSE_ON_EXIT=FALSE",
                        "--spring.datasource.username=sa",
                        "--spring.datasource.password=",
                        "--spring.jpa.hibernate.ddl-auto=update",
                        "--spring.jpa.open-in-view=false",
                        "--roomfit.llm.feedback.enabled=false",
                        "--roomfit.llm.placement.enabled=false");
    }

    private List<Room> canonicalSamples(RoomRepository rooms) {
        return rooms.findBySourceOrderByIdAsc(RoomSource.SAMPLE).stream()
                .filter(RoomSampleDataInitializer::isCanonicalSample)
                .toList();
    }

    private Room room(String name, String clientScope, String furnitureId) {
        return new Room(null, name, 5.8, 5.4, 2.7, "meter",
                List.of(new Wall("wall-persisted", new Position(0, 0), new Position(5.8, 0), 2.7, 0.12)),
                List.of(new Opening("door-persisted", "door", "south", 2.2, 0.8, 2.1, null)),
                List.of(furniture(furnitureId, 2.0, 2.0, 0)),
                RoomSource.ROOMPLAN, LocalDateTime.of(2026, 7, 19, 12, 0), clientScope);
    }

    private Furniture furniture(String id, double x, double z, double rotation) {
        return new Furniture(id, "desk", "Persisted Desk", 1.2, 0.6, 0.73,
                new Position(x, z), rotation, FurnitureStatus.EXISTING,
                "desk-compact-01", List.of("minimal"), "desk-compact");
    }

    private record StoredIds(Long browserRoomId, Long appRoomId, Long contextId, Long layoutId,
                             Long sampleId, LocalDateTime sampleCreatedAt, String pairingCode) {
    }
}
