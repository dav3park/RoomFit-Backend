package com.roomfit.room;

import com.roomfit.common.CustomException;
import com.roomfit.common.ErrorCode;
import com.roomfit.client.ClientScope;
import com.roomfit.product.catalog.GeneratedFurnitureCatalog;
import com.roomfit.room.dto.FurnitureUpdateRequest;
import com.roomfit.room.dto.RoomResponse;
import com.roomfit.room.dto.RoomUploadRequest;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class RoomService {

    private final RoomRepository roomRepository;
    private final RoomAccessService roomAccessService;
    private final RoomPlanImportValidator roomPlanImportValidator;

    public RoomService(RoomRepository roomRepository, RoomAccessService roomAccessService,
                       RoomPlanImportValidator roomPlanImportValidator) {
        this.roomRepository = roomRepository;
        this.roomAccessService = roomAccessService;
        this.roomPlanImportValidator = roomPlanImportValidator;
    }

    public RoomResponse getRoom(Long roomId) {
        Room room = roomAccessService.findReadableRoom(roomId);
        return RoomResponse.from(room);
    }

    public List<RoomResponse> getSampleRooms() {
        return roomRepository.findBySourceOrderByIdAsc(RoomSource.SAMPLE).stream()
                .filter(room -> RoomSampleDataInitializer.isCanonicalSample(room)
                        || RoomSampleDataInitializer.isSeededExtraSample(room))
                .map(RoomResponse::from)
                .toList();
    }

    public List<RoomResponse> getRecentUploadedRooms(int limit) {
        int normalizedLimit = Math.max(0, Math.min(limit, 100));
        if (normalizedLimit == 0) {
            return List.of();
        }
        ClientScope scope = roomAccessService.currentScope();
        if (!scope.enabled()) {
            return roomRepository.findBySourceOrderByCreatedAtDescIdDesc(RoomSource.ROOMPLAN, PageRequest.of(0, normalizedLimit)).stream()
                    .map(RoomResponse::from)
                    .toList();
        }
        return roomRepository.findAccessibleBySourceOrderByCreatedAtDescIdDesc(
                        RoomSource.ROOMPLAN, scope.id(), scope.legacy(), PageRequest.of(0, normalizedLimit)).stream()
                .map(RoomResponse::from)
                .toList();
    }

    public void deleteUploadedRoom(Long roomId) {
        Room room = roomAccessService.findWritableRoom(roomId);
        if (room.getSource() != RoomSource.ROOMPLAN) {
            throw new CustomException(ErrorCode.ROOM_DELETE_NOT_ALLOWED);
        }
        roomRepository.deleteById(roomId);
    }

    public RoomResponse uploadRoom(RoomUploadRequest request) {
        validateUploadRequest(request);

        RoomUploadRequest.RoomData roomData = request.getRoom();
        String name = defaultIfBlank(request.getName(), "Uploaded Room");
        String unit = defaultIfBlank(roomData.getUnit(), "meter");
        List<Wall> walls = nullToEmpty(request.getWalls()).stream()
                .map(this::toWall)
                .toList();
        Set<String> wallIds = walls.stream().map(Wall::getId).collect(Collectors.toSet());
        List<Opening> openings = nullToEmpty(request.getOpenings()).stream()
                .map(opening -> toOpening(opening, wallIds))
                .toList();
        List<Furniture> furniture = nullToEmpty(request.getFurniture()).stream()
                .map(this::toFurniture)
                .toList();

        ClientScope scope = roomAccessService.currentScope();
        Room room = new Room(null, name, roomData.getWidth(), roomData.getDepth(), roomData.getHeight(),
                unit, walls, openings, furniture, RoomSource.ROOMPLAN, LocalDateTime.now(),
                scope.enabled() ? scope.id() : null);
        validateUniqueIds(room);
        roomPlanImportValidator.validateAndNormalize(room);

        return RoomResponse.from(roomRepository.save(room));
    }

    // manage-furniture 단계(아직 Layout이 생성되기 전)의 가구 추가/이동/삭제/
    // 회전을 통째로 반영한다. 기존 PUT /{roomId}/furniture는 상태 변경만
    // 다루므로(그 문서에 명시된 경계를 지키기 위해) 별도 엔드포인트로 추가함 —
    // uploadRoom과 동일한 검증(toFurniture, validateFurnitureWithinRoom)을 재사용.
    public RoomResponse replaceFurniture(Long roomId, List<RoomUploadRequest.FurnitureData> furnitureData) {
        Room room = roomAccessService.findWritableRoom(roomId);
        List<Furniture> furniture = nullToEmpty(furnitureData).stream()
                .map(this::toFurniture)
                .toList();

        room.setFurniture(furniture);
        validateFurnitureWithinRoom(room);
        roomRepository.save(room);
        return RoomResponse.from(room);
    }

    public RoomResponse updateFurnitureStatus(Long roomId, FurnitureUpdateRequest request) {
        Room room = roomAccessService.findWritableRoom(roomId);
        validateFurnitureIds(room, request);

        Map<String, String> statusById = request.getFurnitureUpdates().stream()
                .collect(Collectors.toMap(FurnitureUpdateRequest.Item::getId, FurnitureUpdateRequest.Item::getStatus));

        room.getFurniture().forEach(furniture -> {
            String rawStatus = statusById.get(furniture.getId());
            if (rawStatus == null) {
                return;
            }
            furniture.setStatus(parseStatus(rawStatus));
        });

        roomRepository.save(room);
        return RoomResponse.from(room);
    }

    public RoomResponse copySampleRoom(Long roomId) {
        Room sample = roomAccessService.findReadableRoom(roomId);
        if (sample.getSource() != RoomSource.SAMPLE) {
            throw new CustomException(ErrorCode.ROOM_COPY_NOT_ALLOWED);
        }
        ClientScope scope = roomAccessService.currentScope();
        Room copy = new Room(null, sample.getName() + " 복사본", sample.getWidth(), sample.getDepth(),
                sample.getHeight(), sample.getUnit(), sample.getWalls(), sample.getOpenings(),
                copyFurniture(sample.getFurniture()),
                RoomSource.ROOMPLAN, LocalDateTime.now(), scope.enabled() ? scope.id() : null);
        return RoomResponse.from(roomRepository.save(copy));
    }

    private List<Furniture> copyFurniture(List<Furniture> source) {
        return source.stream().map(item -> new Furniture(item.getId(), item.getType(), item.getLabel(),
                item.getWidth(), item.getDepth(), item.getHeight(),
                new Position(item.getPosition().getX(), item.getPosition().getZ()), item.getRotation(),
                item.getStatus(), item.getProductId(), item.getStyleTags(), item.getVariantId())).toList();
    }

    private void validateUploadRequest(RoomUploadRequest request) {
        if (request == null || request.getRoom() == null) {
            throw new CustomException(ErrorCode.INVALID_ROOM_DIMENSION);
        }

        RoomUploadRequest.RoomData room = request.getRoom();
        if (!positive(room.getWidth()) || !positive(room.getDepth()) || !positive(room.getHeight())) {
            throw new CustomException(ErrorCode.INVALID_ROOM_DIMENSION);
        }
    }

    private Wall toWall(RoomUploadRequest.WallData wall) {
        if (wall == null || isBlank(wall.getId()) || wall.getStart() == null || wall.getEnd() == null
                || wall.getStart().getX() == null || wall.getStart().getZ() == null
                || wall.getEnd().getX() == null || wall.getEnd().getZ() == null
                || !finite(wall.getStart().getX()) || !finite(wall.getStart().getZ())
                || !finite(wall.getEnd().getX()) || !finite(wall.getEnd().getZ())) {
            throw new CustomException(ErrorCode.INVALID_REQUEST_BODY);
        }

        double height = wall.getHeight() == null ? 0 : wall.getHeight();
        double thickness = wall.getThickness() == null ? 0 : wall.getThickness();
        if (!finite(height) || !finite(thickness) || height < 0 || thickness < 0) {
            throw new CustomException(ErrorCode.INVALID_REQUEST_BODY);
        }

        return new Wall(wall.getId(),
                new Position(wall.getStart().getX(), wall.getStart().getZ()),
                new Position(wall.getEnd().getX(), wall.getEnd().getZ()),
                height, thickness);
    }

    // "wall" historically had to be one of the 4 cardinal literals (a room was
    // always a rectangle, so every wall mapped onto exactly one side). Now
    // that `walls[]` can describe an arbitrary (non-rectangular) polygon, a
    // door/window may sit on a 5th+ wall that has no cardinal side at all —
    // so "wall" is primarily a `walls[].id` reference, with the 4 legacy
    // literals kept valid for old uploads/samples that never sent a walls array.
    private static final Set<String> LEGACY_CARDINAL_WALLS = Set.of("north", "south", "east", "west");

    private Opening toOpening(RoomUploadRequest.OpeningData opening, Set<String> wallIds) {
        if (opening == null || isBlank(opening.getId()) || isBlank(opening.getType()) || isBlank(opening.getWall())
                || !nonNegative(opening.getOffset()) || !positive(opening.getWidth()) || !positive(opening.getHeight())
                || !Set.of("door", "window").contains(opening.getType())
                || !isValidWallReference(opening.getWall(), wallIds)) {
            throw new CustomException(ErrorCode.INVALID_OPENING_DATA);
        }

        return new Opening(opening.getId(), opening.getType(), opening.getWall(), opening.getOffset(),
                opening.getWidth(), opening.getHeight(), opening.getSillHeight());
    }

    private boolean isValidWallReference(String wall, Set<String> wallIds) {
        return LEGACY_CARDINAL_WALLS.contains(wall) || wallIds.contains(wall);
    }

    private Furniture toFurniture(RoomUploadRequest.FurnitureData furniture) {
        if (furniture == null || isBlank(furniture.getId()) || isBlank(furniture.getType())
                || !positive(furniture.getWidth()) || !positive(furniture.getDepth()) || !positive(furniture.getHeight())
                || furniture.getPosition() == null || furniture.getPosition().getX() == null
                || furniture.getPosition().getZ() == null || !finite(furniture.getPosition().getX())
                || !finite(furniture.getPosition().getZ()) || !finite(furniture.getRotation() == null ? 0 : furniture.getRotation())) {
            throw new CustomException(ErrorCode.INVALID_REQUEST_BODY);
        }

        String canonicalType = GeneratedFurnitureCatalog.get().normalizeType(furniture.getType());
        boolean supportedType = GeneratedFurnitureCatalog.get().products().stream()
                .map(com.roomfit.product.domain.MockProduct::getType)
                .anyMatch(canonicalType::equals) || "storage".equals(canonicalType);
        if (!supportedType) {
            throw new CustomException(ErrorCode.INVALID_FURNITURE_TYPE);
        }

        FurnitureStatus status = parseStatus(defaultIfBlank(furniture.getStatus(), FurnitureStatus.EXISTING.name()));
        double rotation = RotationUtils.snapToRightAngle(furniture.getRotation() == null ? 0 : furniture.getRotation());
        String label = defaultIfBlank(furniture.getLabel(), furniture.getType());

        return new Furniture(furniture.getId(), furniture.getType(), label, furniture.getWidth(),
                furniture.getDepth(), furniture.getHeight(),
                new Position(furniture.getPosition().getX(), furniture.getPosition().getZ()),
                rotation, status);
    }

    private void validateFurnitureWithinRoom(Room room) {
        for (Furniture furniture : room.getFurniture()) {
            if (!FurnitureBoundary.isInside(room, furniture)) {
                throw new CustomException(ErrorCode.INVALID_FURNITURE_POSITION);
            }
        }
    }

    private FurnitureStatus parseStatus(String rawStatus) {
        try {
            return FurnitureStatus.valueOf(rawStatus);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new CustomException(ErrorCode.INVALID_FURNITURE_STATUS);
        }
    }

    private void validateFurnitureIds(Room room, FurnitureUpdateRequest request) {
        if (request == null || request.getFurnitureUpdates() == null || request.getFurnitureUpdates().isEmpty()) {
            throw new CustomException(ErrorCode.INVALID_REQUEST_BODY);
        }
        boolean hasInvalidItem = request.getFurnitureUpdates().stream()
                .anyMatch(item -> item == null || isBlank(item.getId()) || isBlank(item.getStatus()));
        if (hasInvalidItem) {
            throw new CustomException(ErrorCode.INVALID_REQUEST_BODY);
        }

        Set<String> roomFurnitureIds = room.getFurniture().stream()
                .map(Furniture::getId)
                .collect(Collectors.toSet());

        boolean hasUnknownId = request.getFurnitureUpdates().stream()
                .map(FurnitureUpdateRequest.Item::getId)
                .anyMatch(id -> !roomFurnitureIds.contains(id));

        if (hasUnknownId) {
            throw new CustomException(ErrorCode.FURNITURE_NOT_FOUND);
        }
    }

    private <T> List<T> nullToEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }

    private boolean positive(Double value) {
        return value != null && Double.isFinite(value) && value > 0;
    }

    private boolean nonNegative(Double value) {
        return value != null && Double.isFinite(value) && value >= 0;
    }

    private boolean finite(Double value) {
        return value != null && Double.isFinite(value);
    }

    private void validateUniqueIds(Room room) {
        Set<String> ids = new java.util.HashSet<>();
        for (Wall wall : room.getWalls()) {
            if (!ids.add("wall:" + wall.getId())) throw new CustomException(ErrorCode.INVALID_REQUEST_BODY);
        }
        for (Opening opening : room.getOpenings()) {
            if (!ids.add("opening:" + opening.getId())) throw new CustomException(ErrorCode.INVALID_REQUEST_BODY);
        }
        Set<String> furnitureIds = new java.util.HashSet<>();
        for (Furniture furniture : room.getFurniture()) {
            if (!furnitureIds.add(furniture.getId())) throw new CustomException(ErrorCode.INVALID_REQUEST_BODY);
        }
    }

    private String defaultIfBlank(String value, String fallback) {
        return isBlank(value) ? fallback : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
