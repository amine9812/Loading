package com.greencampus.service.chat;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Verifies that the LLM answer is grounded in the context JSON.
 *
 * Safety strategy (lightweight – primary safety is RBAC in
 * ChatPermissionService):
 * 1. Block answers referencing external sources (http, www., …).
 * 2. Block uncertain/hedging language ("i think", "maybe i", …).
 * 3. Block ROOM CODES in the answer that don't appear in contextJson.
 * 4. Block sensitive-term leakage (booking-owner, audit) if not in context.
 * 5. Allow the fallback sentence unconditionally.
 *
 * NOTE: Number grounding was intentionally removed — it produced too many
 * false positives because JSON formatting and LLM phrasing can legitimately
 * differ (e.g. "30 seats" vs "\"capacity\":30"). RBAC is the primary safety
 * layer.
 */
@Component
public class AnswerVerifier {

    private static final Pattern ROOM_CODE_PATTERN = Pattern.compile("\\b([A-Z]{1,6}-?[A-Z]?\\d{1,3})\\b");

    private static final Set<String> BLOCKED_MARKERS = Set.of(
            "http", "www.", "internet", "wikipedia", "google.com");

    private static final Set<String> UNCERTAIN_MARKERS = Set.of(
            "not sure", "i think", "i believe", "maybe i", "probably not");

    public boolean isSupportedByContext(String answer, String contextJson, String fallbackSentence) {
        if (answer == null || answer.isBlank()) {
            return false;
        }
        // The fallback itself is always allowed.
        if (fallbackSentence.equals(answer.trim())) {
            return true;
        }

        String answerNorm = answer.toLowerCase(Locale.ROOT);
        String contextNorm = contextJson == null ? "" : contextJson.toLowerCase(Locale.ROOT);

        // 1. Block external-source references.
        for (String marker : BLOCKED_MARKERS) {
            if (answerNorm.contains(marker)) {
                return false;
            }
        }

        // 2. Block uncertain/hedging language.
        for (String marker : UNCERTAIN_MARKERS) {
            if (answerNorm.contains(marker)) {
                return false;
            }
        }

        // 3. Every ROOM CODE in the answer must appear in contextJson.
        if (!roomCodesGroundedInContext(answer, contextNorm)) {
            return false;
        }

        // 4. Sensitive-term check.
        if (!canReferenceSensitiveTerms(answerNorm, contextNorm)) {
            return false;
        }

        return true;
    }

    // ─────────────────────────────────────────────────────────────────────────

    private boolean roomCodesGroundedInContext(String answer, String contextNorm) {
        Matcher m = ROOM_CODE_PATTERN.matcher(answer.toUpperCase(Locale.ROOT));
        while (m.find()) {
            String code = m.group(1).toLowerCase(Locale.ROOT);
            if (!contextNorm.contains(code)) {
                return false;
            }
        }
        return true;
    }

    private boolean canReferenceSensitiveTerms(String answerNorm, String contextNorm) {
        // Block booking-owner details unless they're in the context.
        if ((answerNorm.contains("booked by") || answerNorm.contains("booking owner"))
                && !(contextNorm.contains("bookingowner") || contextNorm.contains("booking_owner"))) {
            return false;
        }
        // Block audit log details unless they're in the context.
        if (answerNorm.contains("audit") && !contextNorm.contains("audit")) {
            return false;
        }
        return true;
    }
}
