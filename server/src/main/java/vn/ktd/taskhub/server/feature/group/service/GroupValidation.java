package vn.ktd.taskhub.server.feature.group.service;

public final class GroupValidation {
    private GroupValidation() {}

    public static String name(String value) {
        String result = value == null ? "" : value.trim();
        if (result.length() < 3 || result.length() > 50) throw new GroupException("BAD_REQUEST", "Tên nhóm phải có 3-50 ký tự");
        return result;
    }

    public static String description(String value) {
        String result = value == null ? "" : value.trim();
        if (result.length() > 255) throw new GroupException("BAD_REQUEST", "Mô tả tối đa 255 ký tự");
        return result;
    }

    public static String required(String value, int max, String field) {
        String result = value == null ? "" : value.trim();
        if (result.isEmpty() || result.length() > max) throw new GroupException("BAD_REQUEST", field + " không hợp lệ");
        return result;
    }
}
