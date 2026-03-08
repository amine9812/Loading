package com.greencampus.service.chat;

import com.greencampus.model.enums.UserRole;
import org.springframework.stereotype.Service;

@Service
public class ChatPermissionService {

    public boolean isAllowed(UserRole role, ChatIntent intent) {
        if (role == null || intent == null) {
            return false;
        }
        return switch (role) {
            case ADMIN -> true;
            case TECHNICIAN -> intent != ChatIntent.AUDIT_LOGS && intent != ChatIntent.BOOKING_OWNER;
            case STAFF -> switch (intent) {
                case ROOM_STATUS, ROOM_AVAILABILITY, ROOM_EQUIPMENT, ROOM_CAPACITY, ROOM_SEARCH, UNKNOWN -> true;
                default -> false;
            };
        };
    }
}
