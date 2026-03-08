package com.greencampus.service.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.greencampus.dto.RoomDetailDTO;
import com.greencampus.dto.TicketDTO;
import com.greencampus.model.AuditLog;
import com.greencampus.model.enums.UserRole;
import com.greencampus.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class ChatContextBuilder {

    private final ChatDataAdapter chatDataAdapter;
    private final ObjectMapper objectMapper;
    private final QueryClassifier queryClassifier;

    public ChatContextResult build(String question, AuthenticatedUser user) {
        return build(question, user, queryClassifier.classify(question));
    }

    public ChatContextResult build(String question, AuthenticatedUser user, ChatIntent intent) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("now", LocalDateTime.now().toString());
        context.put("role", user != null ? user.role().name() : null);
        context.put("intent", intent.name());

        boolean hasFacts = false;
        String roomHint = queryClassifier.extractRoomHint(question);
        Long roomId = null;

        if (roomHint != null) {
            context.put("requestedRoom", roomHint);
            Optional<RoomDetailDTO> resolved = chatDataAdapter.getRoomByCodeOrId(roomHint);
            if (resolved.isPresent()) {
                RoomDetailDTO room = resolved.get();
                roomId = room.getId();
                context.put("resolvedRoom", summarizeResolvedRoom(room));
                hasFacts = true;
            }
        }

        if (roomId != null) {
            if (intent == ChatIntent.ROOM_STATUS || intent == ChatIntent.ROOM_AVAILABILITY
                    || intent == ChatIntent.UNKNOWN) {
                Map<String, Object> status = chatDataAdapter.getRoomOperationalStatus(roomId);
                Map<String, Object> availability = stripBookingOwnerForNonAdmin(
                        chatDataAdapter.getRoomAvailabilityOrIdle(roomId, LocalDateTime.now()),
                        user);
                context.put("roomOperationalStatus", status);
                context.put("roomAvailability", availability);
                hasFacts = true;
            }

            if (intent == ChatIntent.ROOM_EQUIPMENT || intent == ChatIntent.UNKNOWN) {
                context.put("equipmentSummary", chatDataAdapter.getRoomEquipmentSummary(roomId));
                hasFacts = true;
            }

            if (intent == ChatIntent.ROOM_CAPACITY || intent == ChatIntent.UNKNOWN) {
                Object resolvedRoom = context.get("resolvedRoom");
                if (resolvedRoom != null) {
                    context.put("capacitySummary", resolvedRoom);
                    hasFacts = true;
                }
            }
        }

        if (intent == ChatIntent.ROOM_SEARCH) {
            Map<String, Object> summary = chatDataAdapter.getRoomsSummary();
            context.put("roomsSummary", summary);
            context.put("roomCount", summary.get("totalCount"));
            context.put("roomsTotalCount", summary.get("totalCount"));
            context.put("roomSearchResults", summary.get("rooms"));
            hasFacts = true;
        }

        if (intent == ChatIntent.UNKNOWN) {
            Map<String, Object> summary = chatDataAdapter.getRoomsSummary();
            context.put("roomsSummary", summary);
            context.put("roomCount", summary.get("totalCount"));
            context.put("roomsTotalCount", summary.get("totalCount"));
            context.put("roomSearchResults", summary.get("rooms"));
            hasFacts = true;
        }

        if (intent == ChatIntent.TICKETS && user != null && canSeeTickets(user.role())) {
            if (roomId != null) {
                // room-specific tickets
                List<TicketDTO> tickets = chatDataAdapter.getTicketsForRoom(roomId);
                context.put("ticketsSummary", summarizeTickets(tickets, 25));
                hasFacts = hasFacts || !tickets.isEmpty();
            } else {
                // global ticket query (e.g. "how many open tickets?")
                List<Map<String, Object>> all = chatDataAdapter.getAllTicketsSummary(50);
                context.put("ticketsSummary", all);
                context.put("ticketsTotalCount", all.size());
                hasFacts = hasFacts || !all.isEmpty();
            }
        }

        if (intent == ChatIntent.BOOKING_OWNER && user != null && canSeeBookingOwner(user.role()) && roomId != null) {
            Optional<Map<String, Object>> owner = chatDataAdapter.getBookingOwnerForRoom(roomId, LocalDateTime.now());
            owner.ifPresent(o -> context.put("bookingOwner", o));
            hasFacts = hasFacts || owner.isPresent();
        }

        if (intent == ChatIntent.AUDIT_LOGS && user != null && user.role() == UserRole.ADMIN) {
            List<AuditLog> logs = chatDataAdapter.getAuditLogs(null, null, null, 5);
            List<Map<String, Object>> summary = summarizeAuditLogs(logs);
            context.put("auditLogs", summary);
            hasFacts = hasFacts || !summary.isEmpty();
        }

        String contextJson;
        try {
            contextJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(context);
        } catch (JsonProcessingException e) {
            contextJson = "{}";
        }

        return new ChatContextResult(context, contextJson, hasFacts);
    }

    private boolean canSeeTickets(UserRole role) {
        return role == UserRole.ADMIN || role == UserRole.TECHNICIAN;
    }

    private boolean canSeeBookingOwner(UserRole role) {
        return role == UserRole.ADMIN;
    }

    private Map<String, Object> stripBookingOwnerForNonAdmin(Map<String, Object> availability, AuthenticatedUser user) {
        if (user != null && user.role() == UserRole.ADMIN) {
            return availability;
        }
        if (!availability.containsKey("activeSession")) {
            return availability;
        }
        Map<String, Object> filtered = new LinkedHashMap<>(availability);
        filtered.remove("activeSession");
        return filtered;
    }

    private Map<String, Object> summarizeResolvedRoom(RoomDetailDTO room) {
        return Map.of(
                "id", room.getId(),
                "code", room.getCode(),
                "type", room.getType().name(),
                "status", room.getStatus().name(),
                "capacity", room.getCapacity(),
                "totalTables", room.getTotalTables(),
                "tablesHavePcs", room.isTablesHavePcs());
    }

    private List<Map<String, Object>> summarizeTickets(List<TicketDTO> tickets, int max) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (TicketDTO t : tickets.stream().limit(max).toList()) {
            out.add(Map.of(
                    "id", t.getId(),
                    "roomCode", t.getRoomCode(),
                    "title", t.getTitle(),
                    "priority", t.getPriority().name(),
                    "status", t.getStatus().name(),
                    "createdAt", t.getCreatedAt().toString()));
        }
        return out;
    }

    private List<Map<String, Object>> summarizeAuditLogs(List<AuditLog> logs) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AuditLog log : logs) {
            out.add(Map.of(
                    "timestamp", log.getEventTimestamp().toString(),
                    "actor", log.getActorUsername(),
                    "action", log.getActionType(),
                    "entityType", log.getEntityType(),
                    "entityId", log.getEntityId() == null ? 0 : log.getEntityId(),
                    "summary", log.getSummary() == null ? "" : log.getSummary()));
        }
        return out;
    }
}
