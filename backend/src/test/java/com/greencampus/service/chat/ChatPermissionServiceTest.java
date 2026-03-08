package com.greencampus.service.chat;

import com.greencampus.model.enums.UserRole;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChatPermissionServiceTest {

    private final ChatPermissionService permissionService = new ChatPermissionService();

    @Test
    void staffCannotAccessTicketsBookingOwnerOrAudit() {
        assertFalse(permissionService.isAllowed(UserRole.STAFF, ChatIntent.TICKETS));
        assertFalse(permissionService.isAllowed(UserRole.STAFF, ChatIntent.BOOKING_OWNER));
        assertFalse(permissionService.isAllowed(UserRole.STAFF, ChatIntent.AUDIT_LOGS));
        assertTrue(permissionService.isAllowed(UserRole.STAFF, ChatIntent.ROOM_AVAILABILITY));
    }

    @Test
    void technicianCanAccessTicketsButNotAuditOrBookingOwner() {
        assertTrue(permissionService.isAllowed(UserRole.TECHNICIAN, ChatIntent.TICKETS));
        assertFalse(permissionService.isAllowed(UserRole.TECHNICIAN, ChatIntent.BOOKING_OWNER));
        assertFalse(permissionService.isAllowed(UserRole.TECHNICIAN, ChatIntent.AUDIT_LOGS));
    }

    @Test
    void adminCanAccessAllIntents() {
        for (ChatIntent intent : ChatIntent.values()) {
            assertTrue(permissionService.isAllowed(UserRole.ADMIN, intent));
        }
    }
}
