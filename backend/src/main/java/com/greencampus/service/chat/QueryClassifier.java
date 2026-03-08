package com.greencampus.service.chat;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class QueryClassifier {

    private static final Pattern ROOM_CODE_PATTERN = Pattern.compile(
            "\\b([A-Z]{1,6}-?[A-Z]?\\d{1,3}|AMPHI-?\\d{1,3}|LAB-?[A-Z]?\\d{1,3}|[A-Z]\\d{1,3}|\\d{1,4})\\b");

    public ChatIntent classify(String question) {
        String q = question == null ? "" : question.toLowerCase(Locale.ROOT);

        if (containsAny(q, "audit", "audit log", "journal")) {
            return ChatIntent.AUDIT_LOGS;
        }
        // "Who booked" / "booked by" → explicit booking-owner query.
        // NOTE: "teacher", "prof", "enseignant" alone are NOT booking-owner queries —
        // a question like "which teacher uses A1?" is an availability/status query.
        if (containsAny(q, "who booked", "booked by")) {
            return ChatIntent.BOOKING_OWNER;
        }
        if (containsAny(q, "ticket", "incident", "maintenance", "panne")) {
            return ChatIntent.TICKETS;
        }
        if (containsAny(q, "projector", "projecteur", "equipment", "broken", "cassé", "casse")) {
            return ChatIntent.ROOM_EQUIPMENT;
        }
        // "pcs" / "pc" may appear in room-count questions ("how many pcs work?") so
        // keep equipment intent only when combined with clear equipment terms.
        if (containsAny(q, "working pc", "broken pc", "how many pc")) {
            return ChatIntent.ROOM_EQUIPMENT;
        }
        if (containsAny(q, "capacity", "tables", "amenities", "features", "seats")) {
            return ChatIntent.ROOM_CAPACITY;
        }
        if (containsAny(q, "idle", "occupied", "available", "availability", "free", "disponible")) {
            return ChatIntent.ROOM_AVAILABILITY;
        }
        // Explicit room-count / room-list queries go to ROOM_SEARCH for full-list
        // context.
        if (containsAny(q, "how many room", "list all room", "list room", "show all room",
                "find room", "search room", "which room", "rooms available",
                "combien de salle", "all rooms")) {
            return ChatIntent.ROOM_SEARCH;
        }
        if (extractRoomHint(question) != null) {
            return ChatIntent.ROOM_STATUS;
        }
        return ChatIntent.UNKNOWN;
    }

    public String extractRoomHint(String question) {
        if (question == null || question.isBlank()) {
            return null;
        }
        Matcher matcher = ROOM_CODE_PATTERN.matcher(question.toUpperCase(Locale.ROOT));
        while (matcher.find()) {
            String token = matcher.group(1);
            if (token != null && !token.isBlank()) {
                return token;
            }
        }
        return null;
    }

    private boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
