package su.prismaworld.logify.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

class RegistrationCodesTest {

    @Test
    void generatesCodesInExpectedFormat() {
        RegistrationCodes codes = new RegistrationCodes(new Random(42));
        for (int i = 0; i < 1000; i++) {
            String code = codes.generate();
            assertTrue(code.matches("[A-Z]{3}-[A-Z]{3}"), code);
            assertEquals(Optional.of(code), RegistrationCodes.normalize(code));
        }
    }

    @Test
    void normalizesUserInput() {
        assertEquals(Optional.of("AZA-AXF"), RegistrationCodes.normalize("AZA-AXF"));
        assertEquals(Optional.of("AZA-AXF"), RegistrationCodes.normalize("aza-axf"));
        assertEquals(Optional.of("AZA-AXF"), RegistrationCodes.normalize(" azaaxf "));
    }

    @Test
    void rejectsInvalidCodes() {
        assertEquals(Optional.empty(), RegistrationCodes.normalize(""));
        assertEquals(Optional.empty(), RegistrationCodes.normalize("AZA-AX"));
        assertEquals(Optional.empty(), RegistrationCodes.normalize("AZA-AXFF"));
        assertEquals(Optional.empty(), RegistrationCodes.normalize("AZ1-AXF"));
        assertEquals(Optional.empty(), RegistrationCodes.normalize("АЗА-АХФ"));
    }
}
