package vn.trainingstats.security;

import java.util.List;

/**
 * Phạm vi dữ liệu người gọi được thấy (ARCHITECTURE mục 5.1).
 * <ul>
 *   <li>{@link Kind#ALL}: Super Admin, toàn bộ.</li>
 *   <li>{@link Kind#UNIT_TREE}: Admin, đơn vị của mình và mọi đơn vị con.</li>
 *   <li>{@link Kind#SELF}: User, chỉ bản thân.</li>
 * </ul>
 * Mọi truy vấn trong các gói user, participant, result, stats phải nhận một Scope và ghép điều kiện của nó.
 */
public record Scope(Kind kind, String unitCode, long userId) {

    public enum Kind { ALL, UNIT_TREE, SELF }

    public static Scope all(long userId) {
        return new Scope(Kind.ALL, null, userId);
    }

    public static Scope unitTree(String unitCode, long userId) {
        if (unitCode == null || unitCode.isBlank()) {
            throw new IllegalArgumentException("Phạm vi theo nhánh cần mã đơn vị");
        }
        return new Scope(Kind.UNIT_TREE, unitCode, userId);
    }

    public static Scope self(long userId) {
        return new Scope(Kind.SELF, null, userId);
    }

    /** Điều kiện trên dữ liệu gắn với đơn vị (đơn vị, thống kê theo đơn vị). User thường không thấy gì. */
    public SqlCondition unitFilter(String unitCodeColumn) {
        return switch (kind) {
            case ALL -> SqlCondition.ALWAYS;
            case UNIT_TREE -> UnitCodes.within(unitCodeColumn, unitCode);
            case SELF -> SqlCondition.NEVER;
        };
    }

    /** Điều kiện trên dữ liệu gắn với một người (user, điểm của người đó). */
    public SqlCondition userFilter(String userIdColumn, String unitCodeColumn) {
        return switch (kind) {
            case ALL -> SqlCondition.ALWAYS;
            case UNIT_TREE -> UnitCodes.within(unitCodeColumn, unitCode);
            case SELF -> new SqlCondition(userIdColumn + " = ?", List.of(userId));
        };
    }

    public boolean coversUnit(String code) {
        return switch (kind) {
            case ALL -> true;
            case UNIT_TREE -> UnitCodes.isWithin(code, unitCode);
            case SELF -> false;
        };
    }

    public boolean coversUser(long targetUserId, String targetUnitCode) {
        return switch (kind) {
            case ALL -> true;
            case UNIT_TREE -> UnitCodes.isWithin(targetUnitCode, unitCode);
            case SELF -> targetUserId == userId;
        };
    }
}
