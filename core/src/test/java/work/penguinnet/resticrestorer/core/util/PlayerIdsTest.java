package work.penguinnet.resticrestorer.core.util;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerIdsTest {
    private static final UUID ID = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

    @Test
    void parsesDashedForm() {
        assertEquals(Optional.of(ID), PlayerIds.tryParse("069a79f4-44e9-4726-a5be-fca90e38aaf5"));
    }

    @Test
    void parsesBareThirtyTwoHexDigitForm() {
        assertEquals(Optional.of(ID), PlayerIds.tryParse("069a79f444e94726a5befca90e38aaf5"));
    }

    @Test
    void treatsUsernamesAsNotAUuid() {
        assertTrue(PlayerIds.tryParse("Notch").isEmpty());
        assertTrue(PlayerIds.tryParse("").isEmpty());
        assertTrue(PlayerIds.tryParse(null).isEmpty());
    }

    @Test
    void rejectsWrongLengthAndNonHexBareForms() {
        assertTrue(PlayerIds.tryParse("069a79f444e94726a5befca90e38aaf").isEmpty());   // 31 digits
        assertTrue(PlayerIds.tryParse("069a79f444e94726a5befca90e38aaf55").isEmpty()); // 33 digits
        assertTrue(PlayerIds.tryParse("069a79f444e94726a5befca90e38aazz").isEmpty());  // non-hex
    }
}
