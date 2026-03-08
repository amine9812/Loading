package com.greencampus.service.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.greencampus.dto.RoomDetailDTO;
import com.greencampus.dto.TicketDTO;
import com.greencampus.model.enums.RoomStatus;
import com.greencampus.model.enums.RoomType;
import com.greencampus.model.enums.TicketPriority;
import com.greencampus.model.enums.TicketStatus;
import com.greencampus.model.enums.UserRole;
import com.greencampus.security.AuthenticatedUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;

class ChatContextBuilderTest {

    private ChatDataAdapter adapter;
    private ChatContextBuilder builder;

    @BeforeEach
    void setUp() {
        adapter = Mockito.mock(ChatDataAdapter.class);
        builder = new ChatContextBuilder(adapter, new ObjectMapper(), new QueryClassifier());

        RoomDetailDTO room = RoomDetailDTO.builder()
                .id(1L)
                .code("A1")
                .type(RoomType.CLASS)
                .status(RoomStatus.OPEN)
                .capacity(40)
                .totalTables(8)
                .tablesHavePcs(true)
                .totalPcs(24)
                .workingPcs(20)
                .brokenPcs(4)
                .build();

        TicketDTO ticket = TicketDTO.builder()
                .id(10L)
                .roomId(1L)
                .roomCode("A1")
                .title("Projector broken")
                .priority(TicketPriority.P1)
                .status(TicketStatus.OPEN)
                .createdAt(LocalDateTime.now())
                .build();

        Mockito.when(adapter.getRoomByCodeOrId(eq("A1"))).thenReturn(Optional.of(room));
        Mockito.when(adapter.getRoomOperationalStatus(eq(1L)))
                .thenReturn(Map.of("roomCode", "A1", "roomStatus", "OPEN", "operationalState", "OPEN"));
        Mockito.when(adapter.getRoomEquipmentSummary(eq(1L)))
                .thenReturn(Map.of("roomCode", "A1", "workingPcs", 20, "brokenPcs", 4, "projectorStatus", "WORKING"));
        Mockito.when(adapter.getRoomAvailabilityOrIdle(eq(1L), any(LocalDateTime.class)))
                .thenReturn(Map.of("at", "2026-01-01T10:00:00", "state", "IDLE", "reason", "no active booking/session"));
        Mockito.when(adapter.getTicketsForRoom(eq(1L))).thenReturn(List.of(ticket));
        Mockito.when(adapter.searchRoomsAvailability(any(), any())).thenReturn(List.of(Map.of("roomCode", "A1")));
        Mockito.when(adapter.getAuditLogs(any(), any(), any(), anyInt())).thenReturn(List.of());
    }

    @Test
    void staffTicketIntentExcludesTicketsAndBookingOwner() {
        ChatContextResult result = builder.build(
                "How many open tickets for A1?",
                new AuthenticatedUser("staff", UserRole.STAFF),
                ChatIntent.TICKETS);

        assertFalse(result.context().containsKey("ticketsSummary"));
        assertFalse(result.context().containsKey("bookingOwner"));
    }

    @Test
    void technicianTicketIntentIncludesTickets() {
        ChatContextResult result = builder.build(
                "How many open tickets for A1?",
                new AuthenticatedUser("tech", UserRole.TECHNICIAN),
                ChatIntent.TICKETS);

        assertTrue(result.context().containsKey("ticketsSummary"));
    }

    @Test
    void roomAvailabilityContextContainsIdleForA1() {
        ChatContextResult result = builder.build(
                "Is room A1 idle now?",
                new AuthenticatedUser("staff", UserRole.STAFF),
                ChatIntent.ROOM_AVAILABILITY);

        String json = result.contextJson();
        assertTrue(result.hasFacts());
        assertTrue(json.contains("\"roomCode\" : \"A1\""));
        assertTrue(json.contains("\"state\" : \"IDLE\""));
    }
}
