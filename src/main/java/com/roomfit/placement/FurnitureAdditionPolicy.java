package com.roomfit.placement;

import com.roomfit.common.CustomException;
import com.roomfit.common.ErrorCode;
import com.roomfit.room.Furniture;
import com.roomfit.room.FurnitureStatus;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class FurnitureAdditionPolicy {

    public static final int MAX_NEW_ADDITIONS = 8;
    public static final int MAX_ACTIVE_FURNITURE = 12;

    public void validate(List<Furniture> existingFurniture, List<String> requestedTypes) {
        int requestedCount = requestedTypes.size();
        if (requestedCount > MAX_NEW_ADDITIONS) {
            throw new CustomException(ErrorCode.FURNITURE_ADDITION_FAILED);
        }
    }

    // 고정설비는 방의 일부라서 가구 수 제한에 세지 않는다.
    static boolean active(Furniture furniture) {
        return furniture.getStatus() != FurnitureStatus.DELETED && !furniture.isImmovableFixture();
    }
}
