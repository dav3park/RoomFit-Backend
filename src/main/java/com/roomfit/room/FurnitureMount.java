package com.roomfit.room;

/**
 * 가구가 어디에 고정되어 있는지. null은 FLOOR와 같다(기존 데이터 호환).
 * WALL이면 elevation(바닥에서 가구 밑면까지 meter)만큼 떠 있으므로 바닥 충돌·동선 계산에서
 * 높이가 겹치지 않는 가구와는 충돌하지 않는다.
 */
public enum FurnitureMount {
    FLOOR,
    WALL
}
