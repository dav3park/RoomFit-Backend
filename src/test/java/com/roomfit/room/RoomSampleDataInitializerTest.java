package com.roomfit.room;

import com.roomfit.placement.ValidationResult;
import com.roomfit.placement.ValidationService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class RoomSampleDataInitializerTest {

    @Test
    void repeatedRunsSeedExactlyOneOfEachSampleAndBecomeANoOp() throws Exception {
        RoomRepository repository = mock(RoomRepository.class);
        List<Room> savedRooms = new ArrayList<>();
        when(repository.findBySourceOrderByIdAsc(RoomSource.SAMPLE))
                .thenAnswer(invocation -> List.copyOf(savedRooms));
        when(repository.save(any(Room.class))).thenAnswer(invocation -> {
            Room room = invocation.getArgument(0);
            savedRooms.add(room);
            return room;
        });
        RoomSampleDataInitializer initializer = new RoomSampleDataInitializer(repository);

        initializer.run();
        // Second run must find all three already present (by name) and save nothing new.
        initializer.run();

        verify(repository, times(3)).save(any(Room.class));
        assertThat(savedRooms).hasSize(3);

        Room canonical = savedRooms.stream().filter(RoomSampleDataInitializer::isCanonicalSample)
                .findFirst().orElseThrow(() -> new AssertionError("canonical sample was not seeded"));
        assertThat(canonical.getFurniture()).extracting(Furniture::getId)
                .containsExactly("bed-1", "desk-1", "chair-1", "wardrobe-1");

        List<Room> extras = savedRooms.stream()
                .filter(RoomSampleDataInitializer::isSeededExtraSample)
                .toList();
        assertThat(extras).extracting(Room::getName)
                .containsExactlyInAnyOrder(
                        RoomSampleDataInitializer.L_STUDIO_SAMPLE_NAME,
                        RoomSampleDataInitializer.ALCOVE_STUDIO_SAMPLE_NAME);
        assertThat(extras).allSatisfy(room -> assertThat(room.getWalls()).hasSizeGreaterThan(4));
    }

    @Test
    void existingCanonicalSampleUpdatesOnlyAnOutdatedWardrobeAndThenBecomesANoOp() throws Exception {
        RoomRepository repository = mock(RoomRepository.class);
        Room canonical = canonicalSampleWithWardrobe(new Position(5.0, 3.85), 180);
        when(repository.findBySourceOrderByIdAsc(RoomSource.SAMPLE)).thenReturn(List.of(canonical));
        RoomSampleDataInitializer initializer = new RoomSampleDataInitializer(repository);

        initializer.run();
        initializer.run();

        Furniture wardrobe = canonical.getFurniture().stream()
                .filter(item -> "wardrobe-1".equals(item.getId())).findFirst().orElseThrow();
        assertThat(wardrobe.getPosition().getX()).isEqualTo(5.39);
        assertThat(wardrobe.getPosition().getZ()).isEqualTo(3.85);
        assertThat(wardrobe.getRotation()).isEqualTo(90);
        verify(repository, times(1)).save(canonical);
    }

    @Test
    void wardrobeUpdateLeavesEveryOtherFurniturePieceUntouched() throws Exception {
        RoomRepository repository = mock(RoomRepository.class);
        Room canonical = canonicalSampleWithWardrobe(new Position(5.0, 3.85), 180);
        when(repository.findBySourceOrderByIdAsc(RoomSource.SAMPLE)).thenReturn(List.of(canonical));

        new RoomSampleDataInitializer(repository).run();

        assertThat(furnitureById(canonical, "bed-1")).satisfies(bed -> {
            assertThat(bed.getPosition().getX()).isEqualTo(1.35);
            assertThat(bed.getPosition().getZ()).isEqualTo(1.55);
            assertThat(bed.getRotation()).isEqualTo(0);
        });
        assertThat(furnitureById(canonical, "desk-1")).satisfies(desk -> {
            assertThat(desk.getPosition().getX()).isEqualTo(3.0);
            assertThat(desk.getPosition().getZ()).isEqualTo(1.05);
            assertThat(desk.getRotation()).isEqualTo(0);
        });
        assertThat(furnitureById(canonical, "chair-1")).satisfies(chair -> {
            assertThat(chair.getPosition().getX()).isEqualTo(3.0);
            assertThat(chair.getPosition().getZ()).isEqualTo(1.85);
            assertThat(chair.getRotation()).isEqualTo(180);
        });
    }

    // A legacy/other SAMPLE row can carry its own "wardrobe-1" at the old
    // coordinates. Only the canonical sample may be rewritten — anything else,
    // including user-owned ROOMPLAN rooms (which this query never even
    // returns), has to come back untouched and unsaved.
    @Test
    void nonCanonicalSampleWithTheSameWardrobeIdIsNeitherUpdatedNorSaved() throws Exception {
        RoomRepository repository = mock(RoomRepository.class);
        Room canonical = canonicalSampleWithWardrobe(new Position(5.0, 3.85), 180);
        Room otherSample = new Room(null, 4.0, 4.0, 2.7, "meter", List.of(), List.of(
                new Furniture("wardrobe-1", "wardrobe", "옷장", 1.2, 0.65, 2.1,
                        new Position(5.0, 3.85), 180, FurnitureStatus.EXISTING)));
        when(repository.findBySourceOrderByIdAsc(RoomSource.SAMPLE))
                .thenReturn(List.of(canonical, otherSample));

        new RoomSampleDataInitializer(repository).run();

        Furniture untouched = furnitureById(otherSample, "wardrobe-1");
        assertThat(untouched.getPosition().getX()).isEqualTo(5.0);
        assertThat(untouched.getRotation()).isEqualTo(180);
        verify(repository, never()).save(otherSample);
        verify(repository, times(1)).save(canonical);
    }

    @Test
    void updatedWardrobeStillPassesValidationAgainstTheSampleRoom() throws Exception {
        RoomRepository repository = mock(RoomRepository.class);
        Room canonical = canonicalSampleWithWardrobe(new Position(5.0, 3.85), 180);
        when(repository.findBySourceOrderByIdAsc(RoomSource.SAMPLE)).thenReturn(List.of(canonical));

        new RoomSampleDataInitializer(repository).run();

        ValidationResult result = new ValidationService().validate(canonical, canonical.getFurniture());
        assertThat(result.isCollisionFree()).isTrue();
        assertThat(result.isBoundaryValid()).isTrue();
    }

    private Furniture furnitureById(Room room, String id) {
        return room.getFurniture().stream()
                .filter(item -> id.equals(item.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no furniture " + id + " in room"));
    }

    // Mirrors what RoomSampleDataInitializer actually seeds (dimensions and
    // positions included) so the validation assertion below exercises the real
    // canonical layout rather than a simplified stand-in that could be out of
    // bounds for reasons unrelated to the wardrobe.
    private Room canonicalSampleWithWardrobe(Position position, double rotation) {
        return new Room(null, 5.8, 5.4, 2.7, "meter", List.of(), List.of(
                new Furniture("bed-1", "bed", "우드 침대", 1.45, 2.1, 0.48,
                        new Position(1.35, 1.55), 0, FurnitureStatus.EXISTING),
                new Furniture("desk-1", "desk", "우드 책상", 1.35, 0.6, 0.72,
                        new Position(3.0, 1.05), 0, FurnitureStatus.EXISTING),
                new Furniture("chair-1", "chair", "우드 의자", 0.55, 0.55, 0.82,
                        new Position(3.0, 1.85), 180, FurnitureStatus.EXISTING),
                new Furniture("wardrobe-1", "wardrobe", "우드 옷장", 1.2, 0.65, 2.1, position, rotation,
                        FurnitureStatus.EXISTING)));
    }
}
