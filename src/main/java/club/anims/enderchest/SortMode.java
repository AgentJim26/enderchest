package club.anims.enderchest;

public enum SortMode {
    CUSTOM("Custom"),
    ASCENDING("Ascending"),
    DESCENDING("Descending");

    private final String pretty;

    SortMode(String pretty) {
        this.pretty = pretty;
    }

    public String pretty() {
        return pretty;
    }

    public SortMode next() {
        return switch (this) {
            case CUSTOM -> ASCENDING;
            case ASCENDING -> DESCENDING;
            case DESCENDING -> CUSTOM;
        };
    }

    public static SortMode parse(String value) {
        if (value == null) return CUSTOM;
        try {
            return valueOf(value.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return CUSTOM;
        }
    }
}
