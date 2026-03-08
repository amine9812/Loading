package com.greencampus.service.chat;

import com.greencampus.dto.FreeRoomDTO;
import com.greencampus.dto.RoomDetailDTO;
import com.greencampus.dto.RoomListDTO;
import com.greencampus.dto.SessionDTO;
import com.greencampus.dto.TicketDTO;
import com.greencampus.model.AuditLog;
import com.greencampus.model.enums.DayOfWeekEnum;
import com.greencampus.model.enums.RoomStatus;
import com.greencampus.repository.RoomRepository;
import com.greencampus.service.AuditLogService;
import com.greencampus.service.AbsenceService;
import com.greencampus.service.RoomService;
import com.greencampus.service.SessionService;
import com.greencampus.service.TicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class ChatDataAdapter {

    private final RoomService roomService;
    private final TicketService ticketService;
    private final SessionService sessionService;
    private final AbsenceService absenceService;
    private final AuditLogService auditLogService;
    private final RoomRepository roomRepository;

    private static final DateTimeFormatter SESSION_TIME_FMT = DateTimeFormatter.ofPattern("H:mm");

    public Optional<RoomDetailDTO> getRoomByCodeOrId(String codeOrId) {
        if (codeOrId == null || codeOrId.isBlank()) {
            return Optional.empty();
        }

        try {
            Long roomId = Long.parseLong(codeOrId);
            return Optional.ofNullable(roomService.getRoomDetail(roomId));
        } catch (Exception ignored) {
            // Try by code below
        }

        Optional<Long> exactCodeId = roomRepository.findByCode(codeOrId.toUpperCase()).map(r -> r.getId());
        if (exactCodeId.isPresent()) {
            return Optional.ofNullable(roomService.getRoomDetail(exactCodeId.get()));
        }

        return roomService.searchRooms(null, null, null, null, null).stream()
                .filter(r -> r.getCode().equalsIgnoreCase(codeOrId))
                .findFirst()
                .map(RoomListDTO::getId)
                .map(roomService::getRoomDetail);
    }

    public Map<String, Object> getRoomOperationalStatus(Long roomId) {
        RoomDetailDTO room = roomService.getRoomDetail(roomId);
        String operationalState = room.getStatus() == RoomStatus.CLOSED ? "CLOSED" : "OPEN";
        return Map.of(
                "roomCode", room.getCode(),
                "roomStatus", room.getStatus().name(),
                "operationalState", operationalState);
    }

    public Map<String, Object> getRoomEquipmentSummary(Long roomId) {
        RoomDetailDTO room = roomService.getRoomDetail(roomId);
        return Map.of(
                "roomCode", room.getCode(),
                "projectorStatus", room.getProjectorStatus() == null ? "UNKNOWN" : room.getProjectorStatus().name(),
                "teacherPcStatus", room.getTeacherPcStatus() == null ? "UNKNOWN" : room.getTeacherPcStatus().name(),
                "totalPcs", room.getTotalPcs(),
                "workingPcs", room.getWorkingPcs(),
                "brokenPcs", room.getBrokenPcs());
    }

    public Map<String, Object> getRoomAvailabilityOrIdle(Long roomId, LocalDateTime at) {
        RoomDetailDTO room = roomService.getRoomDetail(roomId);
        if (room.getStatus() == RoomStatus.CLOSED) {
            return Map.of(
                    "at", at.toString(),
                    "state", "CLOSED",
                    "reason", "room is closed");
        }

        DayOfWeekEnum targetDay = DayOfWeekEnum.valueOf(at.getDayOfWeek().name());
        LocalTime targetTime = at.toLocalTime();

        Optional<SessionDTO> activeSession = sessionService.getSessionsByRoom(roomId).stream()
                .filter(s -> s.getDayOfWeek() == targetDay)
                .filter(s -> {
                    LocalTime start = LocalTime.parse(s.getStartTime(), SESSION_TIME_FMT);
                    LocalTime end = LocalTime.parse(s.getEndTime(), SESSION_TIME_FMT);
                    return !targetTime.isBefore(start) && targetTime.isBefore(end);
                })
                .findFirst();

        if (activeSession.isPresent()) {
            SessionDTO s = activeSession.get();
            return Map.of(
                    "at", at.toString(),
                    "state", "OCCUPIED",
                    "reason", "active scheduled session",
                    "activeSession", Map.of(
                            "courseName", s.getCourseName(),
                            "teacherName", s.getTeacherName(),
                            "groupName", s.getGroupName(),
                            "startTime", s.getStartTime(),
                            "endTime", s.getEndTime()));
        }

        return Map.of(
                "at", at.toString(),
                "state", "IDLE",
                "reason", "no active booking/session");
    }

    public List<RoomListDTO> searchAvailableRooms(Integer minWorkingPcs, Boolean needsProjector) {
        return roomService.searchRooms(null, null, RoomStatus.OPEN, minWorkingPcs, needsProjector);
    }

    public List<RoomListDTO> listRooms(String q) {
        return roomService.searchRooms(q, null, null, null, null);
    }

    /**
     * Returns a summary of ALL rooms regardless of status, used for accurate
     * total-count queries (including CLOSED rooms).
     */
    public Map<String, Object> getRoomsSummary() {
        List<RoomListDTO> all = listRooms(null);
        List<Map<String, Object>> list = new ArrayList<>();
        for (RoomListDTO r : all) {
            list.add(Map.of(
                    "roomCode", r.getCode(),
                    "status", r.getStatus().name(),
                    "type", r.getType().name(),
                    "capacity", r.getCapacity(),
                    "workingPcs", r.getWorkingPcs(),
                    "projectorStatus", r.getProjectorStatus() == null ? "UNKNOWN" : r.getProjectorStatus().name()));
        }
        return Map.of(
                "totalCount", all.size(),
                "rooms", list);
    }

    public List<TicketDTO> getOpenTickets(Long roomId) {
        return ticketService.searchTickets(com.greencampus.model.enums.TicketStatus.OPEN, null, roomId);
    }

    public List<TicketDTO> getTicketsForRoom(Long roomId) {
        return ticketService.getTicketsForRoom(roomId);
    }

    public Optional<Map<String, Object>> getBookingOwnerForRoom(Long roomId, LocalDateTime at) {
        Map<String, Object> availability = getRoomAvailabilityOrIdle(roomId, at);
        Object activeSession = availability.get("activeSession");
        if (!(activeSession instanceof Map<?, ?> map)) {
            return Optional.empty();
        }
        return Optional.of(Map.of(
                "teacherName", map.get("teacherName"),
                "courseName", map.get("courseName"),
                "groupName", map.get("groupName")));
    }

    public List<AuditLog> getAuditLogs(LocalDateTime from, LocalDateTime to, String entityType, int limit) {
        return auditLogService.getFiltered(from, to, entityType).stream().limit(limit).toList();
    }

    public List<Map<String, Object>> searchRoomsAvailability(Integer minWorkingPcs, Boolean needsProjector) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (RoomListDTO room : searchAvailableRooms(minWorkingPcs, needsProjector)) {
            out.add(Map.of(
                    "roomId", room.getId(),
                    "roomCode", room.getCode(),
                    "status", room.getStatus().name(),
                    "capacity", room.getCapacity(),
                    "workingPcs", room.getWorkingPcs(),
                    "projectorStatus",
                    room.getProjectorStatus() == null ? "UNKNOWN" : room.getProjectorStatus().name()));
        }
        return out;
    }

    public List<TicketDTO> getTickets(Long roomId) {
        return ticketService.searchTickets(null, null, roomId);
    }

    /**
     * Returns all tickets across all rooms (no room filter), used for global
     * ticket queries like "how many open tickets are there?".
     */
    public List<Map<String, Object>> getAllTicketsSummary(int max) {
        List<TicketDTO> all = ticketService.searchTickets(null, null, null);
        List<Map<String, Object>> out = new ArrayList<>();
        for (TicketDTO t : all.stream().limit(max).toList()) {
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

    public List<SessionDTO> getSessions() {
        return sessionService.getAllSessions();
    }

    public List<SessionDTO> getRoomSessions(Long roomId) {
        return sessionService.getSessionsByRoom(roomId);
    }

    public List<FreeRoomDTO> suggestFreeRooms(DayOfWeekEnum day, LocalTime start, LocalTime end) {
        return absenceService.suggestFreeRooms(day, start, end);
    }

    public Map<String, Object> getPoliciesSummary() {
        return Map.of(
                "fallbackSentence", ChatService.FALLBACK,
                "ticketCreate", "TECHNICIAN only",
                "ticketDelete", "ADMIN only",
                "roomsWrite", "ADMIN only",
                "dataSource", "Internal GreenCampus services only");
    }
}
