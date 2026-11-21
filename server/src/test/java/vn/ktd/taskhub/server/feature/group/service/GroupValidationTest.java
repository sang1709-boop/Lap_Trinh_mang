package vn.ktd.taskhub.server.feature.group.service;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class GroupValidationTest {
    @Test void trimsValidName() { assertEquals("Nhóm KTD",GroupValidation.name("  Nhóm KTD  ")); }
    @Test void rejectsShortAndLongNames() {
        assertEquals("BAD_REQUEST",assertThrows(GroupException.class,()->GroupValidation.name("ab")).code());
        assertThrows(GroupException.class,()->GroupValidation.name("x".repeat(51)));
    }
    @Test void descriptionIsOptionalAndLimited() {
        assertEquals("",GroupValidation.description(null));
        assertThrows(GroupException.class,()->GroupValidation.description("x".repeat(256)));
    }
    @Test void requiredFieldRejectsBlank() {
        assertThrows(GroupException.class,()->GroupValidation.required("  ",20,"Lý do"));
    }
}
