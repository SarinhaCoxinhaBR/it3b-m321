package ch.benedict.m321.chatservice.controller;

import ch.benedict.m321.chatservice.TestcontainersConfiguration;
import ch.benedict.m321.chatservice.config.QueueNames;
import ch.benedict.m321.chatservice.dto.ChatMessage;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Der Weg von aussen nach innen, einmal ganz durch: HTTP rein,
 * Nachricht in der Queue raus.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class MessageControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    /** POST /messages antwortet mit 202 und legt die Nachricht in chat.persist. */
    @Test
    void acceptsMessageAndPutsItOnTheQueue() throws Exception {
        String requestBody = """
                {
                  "roomId": "3f2b1c4e-0000-0000-0000-000000000001",
                  "senderId": "anna",
                  "senderName": "Anna Muster",
                  "content": "Hallo zusammen"
                }
                """;

        mockMvc.perform(post("/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.sentAt").exists());

        ParameterizedTypeReference<ChatMessage> targetType = new ParameterizedTypeReference<>() {
        };
        ChatMessage message = rabbitTemplate.receiveAndConvert(
                QueueNames.PERSIST_QUEUE, 5000, targetType);

        assertNotNull(message);
        assertEquals("Hallo zusammen", message.content());
        assertEquals("Anna Muster", message.senderName());
    }

    /** Ein leerer Text wird mit 400 abgelehnt. */
    @Test
    void rejectsEmptyContent() throws Exception {
        String requestBody = """
                {
                  "roomId": "3f2b1c4e-0000-0000-0000-000000000001",
                  "senderId": "anna",
                  "senderName": "Anna Muster",
                  "content": "   "
                }
                """;

        mockMvc.perform(post("/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest());
    }
}
