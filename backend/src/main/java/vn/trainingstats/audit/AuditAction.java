package vn.trainingstats.audit;

/** Tên hành động ghi vào audit_log. Thêm hằng mới ở đây thay vì viết chuỗi rải rác. */
public final class AuditAction {

    public static final String LOGIN_SUCCESS = "LOGIN_SUCCESS";
    public static final String LOGIN_FAILED = "LOGIN_FAILED";
    public static final String LOGOUT = "LOGOUT";
    public static final String PASSWORD_CHANGED = "PASSWORD_CHANGED";
    public static final String SUPER_ADMIN_CREATED = "SUPER_ADMIN_CREATED";
    public static final String SUPER_ADMIN_RESET = "SUPER_ADMIN_RESET";
    public static final String UNIT_CREATED = "UNIT_CREATED";
    public static final String UNIT_UPDATED = "UNIT_UPDATED";
    public static final String UNIT_STATUS_CHANGED = "UNIT_STATUS_CHANGED";
    public static final String CATALOG_CREATED = "CATALOG_CREATED";
    public static final String CATALOG_UPDATED = "CATALOG_UPDATED";
    public static final String CATALOG_STATUS_CHANGED = "CATALOG_STATUS_CHANGED";
    public static final String USER_CREATED = "USER_CREATED";
    public static final String USER_UPDATED = "USER_UPDATED";
    public static final String USER_STATUS_CHANGED = "USER_STATUS_CHANGED";
    public static final String USER_ROLE_CHANGED = "USER_ROLE_CHANGED";
    public static final String USER_MOVED = "USER_MOVED";
    public static final String PASSWORD_RESET = "PASSWORD_RESET";
    public static final String PERIOD_CREATED = "PERIOD_CREATED";
    public static final String PERIOD_UPDATED = "PERIOD_UPDATED";
    public static final String PERIOD_STATUS_CHANGED = "PERIOD_STATUS_CHANGED";
    public static final String PERIOD_DELETED = "PERIOD_DELETED";
    public static final String CLASS_CREATED = "CLASS_CREATED";
    public static final String CLASS_UPDATED = "CLASS_UPDATED";
    public static final String CLASS_STATUS_CHANGED = "CLASS_STATUS_CHANGED";
    public static final String CLASS_DELETED = "CLASS_DELETED";
    public static final String CLASS_TEACHERS_CHANGED = "CLASS_TEACHERS_CHANGED";
    public static final String PARTICIPANT_ADDED = "PARTICIPANT_ADDED";
    public static final String PARTICIPANT_REMOVED = "PARTICIPANT_REMOVED";
    public static final String PARTICIPANT_ABSENCE_SET = "PARTICIPANT_ABSENCE_SET";
    public static final String UNIT_RECORD_CREATED = "UNIT_RECORD_CREATED";
    public static final String REQUEST_CREATED = "REQUEST_CREATED";
    public static final String REQUEST_APPROVED = "REQUEST_APPROVED";
    public static final String REQUEST_REJECTED = "REQUEST_REJECTED";
    public static final String REQUEST_CANCELLED = "REQUEST_CANCELLED";
    public static final String RESULT_CREATED = "RESULT_CREATED";
    public static final String RESULT_UPDATED = "RESULT_UPDATED";
    public static final String USERS_IMPORTED = "USERS_IMPORTED";
    public static final String CLASSES_IMPORTED = "CLASSES_IMPORTED";
    public static final String RESULTS_IMPORTED = "RESULTS_IMPORTED";
    public static final String ASSIGNMENTS_IMPORTED = "ASSIGNMENTS_IMPORTED";
    public static final String BACKUP_CREATED = "BACKUP_CREATED";
    public static final String BACKUP_AUTO_CREATED = "BACKUP_AUTO_CREATED";
    public static final String BACKUP_FAILED = "BACKUP_FAILED";
    public static final String BACKUP_DOWNLOADED = "BACKUP_DOWNLOADED";
    public static final String SETTINGS_UPDATED = "SETTINGS_UPDATED";

    private AuditAction() {
    }
}
