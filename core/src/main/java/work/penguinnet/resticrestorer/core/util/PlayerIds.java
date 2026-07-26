package work.penguinnet.resticrestorer.core.util;

import java.util.Optional;
import java.util.UUID;

/** Parsing of the player identifiers a command argument may carry. */
public final class PlayerIds {
    private PlayerIds() {
    }

    /**
     * Accepts a dashed UUID or the bare 32-hex-digit form Mojang's APIs hand out; returns empty if the value
     * is neither (i.e. it should be treated as a username).
     */
    public static Optional<UUID> tryParse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException dashed) {
            if (value.length() == 32 && value.chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
                String withDashes = value.substring(0, 8) + "-" + value.substring(8, 12) + "-"
                        + value.substring(12, 16) + "-" + value.substring(16, 20) + "-" + value.substring(20);
                try {
                    return Optional.of(UUID.fromString(withDashes));
                } catch (IllegalArgumentException ignored) {
                    return Optional.empty();
                }
            }
            return Optional.empty();
        }
    }
}
