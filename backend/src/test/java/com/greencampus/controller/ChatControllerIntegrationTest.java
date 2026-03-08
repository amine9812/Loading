package com.greencampus.controller;

import com.greencampus.service.chat.GroqClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ChatControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private GroqClient groqClient;

    private String adminToken;
    private String techToken;
    private String staffToken;

    @BeforeEach
    void loginUsers() throws Exception {
        adminToken = login("admin", "admin123");
        techToken = login("tech", "tech123");
        staffToken = login("staff", "staff123");
    }

    @Test
    void requiresAuth() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hello\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void staffWhoBookedQuestionReturnsFallback() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Who booked room A1 at 10:00?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("Information not available in the system."));
    }

    @Test
    void staffTicketQuestionReturnsFallback() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"How many open tickets for A1?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("Information not available in the system."));
    }

    @Test
    void staffIdleQuestionCanReturnSupportedAnswer() throws Exception {
        when(groqClient.complete(anyString(), anyString(), anyString()))
                .thenReturn("Room A1 is IDLE.");

        mockMvc.perform(post("/api/chat")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Is room A1 idle now?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("Room A1 is IDLE."));
    }

    @Test
    void technicianCanAskTickets() throws Exception {
        when(groqClient.complete(anyString(), anyString(), anyString()))
                .thenReturn("Room LAB-A1 has OPEN tickets.");

        mockMvc.perform(post("/api/chat")
                        .header("Authorization", "Bearer " + techToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Show tickets for LAB-A1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("Room LAB-A1 has OPEN tickets."));
    }

    @Test
    void adminAuditQuestionAllowedPath() throws Exception {
        when(groqClient.complete(anyString(), anyString(), anyString()))
                .thenReturn("Information not available in the system.");

        mockMvc.perform(post("/api/chat")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Show audit log for last 5 actions\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").isString());
    }

    @Test
    void adminRoomCountQuestionUsesContext() throws Exception {
                mockMvc.perform(post("/api/chat")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"How many rooms do we have?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value(matchesPattern("There are \\d+ rooms in the system\\.")));
    }

    @Test
    void verifierRejectsHallucination() throws Exception {
        when(groqClient.complete(anyString(), anyString(), anyString()))
                .thenReturn("Room A1 has 999 working PCs according to internet.");

        mockMvc.perform(post("/api/chat")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"How many working pcs in A1?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("Information not available in the system."));
    }

    private String login(String username, String password) throws Exception {
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return response.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
    }
}
