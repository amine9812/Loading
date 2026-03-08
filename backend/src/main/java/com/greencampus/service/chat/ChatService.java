package com.greencampus.service.chat;

import com.greencampus.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    public static final String FALLBACK = "Information not available in the system.";
    private static final String SYSTEM_MESSAGE = "Answer ONLY from the provided CONTEXT block. If missing, reply 'Information not available in the system.'";

    private final ChatContextBuilder contextBuilder;
    private final GroqClient groqClient;
    private final AnswerVerifier answerVerifier;
    private final ChatRateLimiter chatRateLimiter;
    private final QueryClassifier queryClassifier;
    private final ChatPermissionService chatPermissionService;

    public String answer(String rawMessage, AuthenticatedUser user, String ipAddress) {
        if (user == null) {
            throw new InvalidChatInputException("Authentication required");
        }
        if (!chatRateLimiter.allowUser(user.username()) || !chatRateLimiter.allowIp(ipAddress)) {
            throw new ChatRateLimitException("Rate limit exceeded");
        }

        String message = sanitize(rawMessage);
        validate(message);
        boolean suspiciousPrompt = looksLikePromptInjection(message);
        ChatIntent intent = queryClassifier.classify(message);

        log.info("Chat request user={} role={} ip={} q='{}'",
                user.username(), user.role().name(), ipAddress, truncateForLog(message));
        if (suspiciousPrompt) {
            log.warn("Potential prompt injection pattern detected for user={}", user.username());
        }
        if (!chatPermissionService.isAllowed(user.role(), intent)) {
            return FALLBACK;
        }

        ChatContextResult context;
        try {
            context = contextBuilder.build(message, user, intent);
        } catch (Exception ex) {
            log.warn("Chat context build failed: {}", ex.getMessage());
            return FALLBACK;
        }
        if (!context.hasFacts()) {
            return FALLBACK;
        }

        String deterministic = deterministicAnswer(message, intent, context.context());
        if (deterministic != null) {
            return deterministic;
        }

        String developerMessage = buildDeveloperMessage(context.context(), context.contextJson());

        String answer;
        try {
            answer = groqClient.complete(SYSTEM_MESSAGE, developerMessage, message);
        } catch (Exception ex) {
            log.warn("Chat LLM unavailable: {}", ex.getMessage());
            return FALLBACK;
        }

        if (answer == null || answer.isBlank()) {
            return FALLBACK;
        }

        if (suspiciousPrompt && !context.hasFacts()) {
            return FALLBACK;
        }

        boolean verified = answerVerifier.isSupportedByContext(answer, context.contextJson(), FALLBACK);
        log.debug("LLM answer='{}' verified={}", answer.substring(0, Math.min(answer.length(), 200)), verified);
        if (!verified) {
            log.warn("AnswerVerifier rejected LLM answer (len={}): '{}'",
                    answer.length(),
                    answer.substring(0, Math.min(answer.length(), 300)));
            return FALLBACK;
        }

        return answer.trim();
    }

    String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        String stripped = raw.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", " ");
        return stripped.trim();
    }

    void validate(String message) {
        if (message.isBlank()) {
            throw new InvalidChatInputException("Message is required");
        }
        if (message.length() > 500) {
            throw new InvalidChatInputException("Message too long (max 500 chars)");
        }
    }

    private String truncateForLog(String message) {
        if (message.length() <= 120) {
            return message;
        }
        return message.substring(0, 120) + "...";
    }

    public boolean looksLikePromptInjection(String message) {
        String q = message.toLowerCase(Locale.ROOT);
        return q.contains("ignore previous")
                || q.contains("system prompt")
                || q.contains("browse")
                || q.contains("tool")
                || q.contains("web");
    }

    @SuppressWarnings("unchecked")
    private String deterministicAnswer(String message, ChatIntent intent, Map<String, Object> context) {
        if (intent == ChatIntent.ROOM_AVAILABILITY || intent == ChatIntent.ROOM_STATUS) {
            Object resolvedRoomObj = context.get("resolvedRoom");
            Object availabilityObj = context.get("roomAvailability");
            if (resolvedRoomObj instanceof Map<?, ?> resolvedRoom
                    && availabilityObj instanceof Map<?, ?> availability) {
                Object code = resolvedRoom.get("code");
                Object state = availability.get("state");
                if (code instanceof String roomCode && state instanceof String roomState) {
                    return "Room " + roomCode + " is " + roomState + ".";
                }
            }
        }

        String q = message.toLowerCase(Locale.ROOT);
        if ((intent == ChatIntent.UNKNOWN || intent == ChatIntent.ROOM_SEARCH)
                && q.contains("how many")
                && q.contains("room")) {
            Object roomCount = context.get("roomCount");
            if (roomCount instanceof Number n) {
                return "There are " + n.intValue() + " rooms in the system.";
            }
        }

        return null;
    }

    /**
     * Builds a clear, structured developer message so the LLM knows
     * exactly what data is available in the CONTEXT block.
     */
    @SuppressWarnings("unchecked")
    private String buildDeveloperMessage(Map<String, Object> ctx, String contextJson) {
        StringBuilder hint = new StringBuilder();

        if (ctx.containsKey("roomSearchResults") || ctx.containsKey("roomsTotalCount")) {
            Object count = ctx.get("roomsTotalCount");
            if (count instanceof Number n) {
                hint.append("The system has ").append(n.intValue()).append(" room(s) total. ");
            }
            Object results = ctx.get("roomSearchResults");
            if (results instanceof java.util.List<?> list && !list.isEmpty()) {
                hint.append("The list of rooms is in 'roomSearchResults'. ");
                hint.append(
                        "Each room has: roomCode, status (OPEN/CLOSED), type, capacity, workingPcs, projectorStatus. ");
            }
        }
        if (ctx.containsKey("resolvedRoom")) {
            hint.append("The requested room details are in 'resolvedRoom'. ");
        }
        if (ctx.containsKey("roomAvailability")) {
            hint.append("Current room availability is in 'roomAvailability' (state: IDLE/OCCUPIED/CLOSED). ");
        }
        if (ctx.containsKey("equipmentSummary")) {
            hint.append("Room equipment details are in 'equipmentSummary'. ");
        }
        if (ctx.containsKey("ticketsSummary")) {
            hint.append("Maintenance ticket data is in 'ticketsSummary'. ");
        }
        if (ctx.containsKey("auditLogs")) {
            hint.append("Audit log entries are in 'auditLogs'. ");
        }
        if (ctx.containsKey("bookingOwner")) {
            hint.append("Current booking owner is in 'bookingOwner'. ");
        }

        return """
                CONTEXT (use ONLY this data to answer):
                %s

                Data guide:
                %s

                Rules:
                - Answer using facts from CONTEXT only.
                - Be concise and factual.
                - If the user's question cannot be answered from CONTEXT, reply EXACTLY: Information not available in the system.
                - Do not guess or add external knowledge.
                """
                .formatted(contextJson, hint.toString().isBlank() ? "See CONTEXT above." : hint.toString().trim());
    }
}
