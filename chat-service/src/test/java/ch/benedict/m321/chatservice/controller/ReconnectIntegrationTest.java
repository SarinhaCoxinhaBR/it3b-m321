package ch.benedict.m321.chatservice.controller;

import ch.benedict.m321.chatservice.TestcontainersConfiguration;
import ch.benedict.m321.chatservice.controller.SseTestClient.ReceivedEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scenario S7 over real HTTP and a real RabbitMQ: a client loses its
 * connection, misses 50 messages and reconnects with Last-Event-ID.
 *
 * The test also checks the contract of GET /stream on the first event:
 * status 200, content type text/event-stream, the message id in the id field
 * and the message as JSON in the data field.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ReconnectIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final int MISSED_MESSAGES = 50;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @LocalServerPort
    private int port;

    /**
     * After the reconnect the client gets exactly the 50 missed messages, in
     * their order and none twice, and then the next live message.
     */
    @Test
    void reconnectGetsExactlyTheMissedMessagesThenLiveOnes() throws Exception {
        String baseUrl = "http://localhost:" + port;
        UUID roomId = UUID.randomUUID();
        ReceivedEvent lastReceived = receiveOneMessageAndDisconnect(baseUrl, roomId);

        List<String> expectedIds = new ArrayList<>();
        for (int i = 1; i <= MISSED_MESSAGES; i++) {
            UUID missedId = SseTestClient.postMessage(baseUrl, roomId, "anna", "Verpasst " + i);
            expectedIds.add(missedId.toString());
        }

        UUID lastEventId = UUID.fromString(lastReceived.id());
        try (SseTestClient reconnected = new SseTestClient(baseUrl, roomId, lastEventId)) {
            reconnected.waitForEvents(MISSED_MESSAGES, TIMEOUT);
            UUID liveId = SseTestClient.postMessage(baseUrl, roomId, "anna", "Wieder live");
            expectedIds.add(liveId.toString());
            reconnected.waitForEvents(MISSED_MESSAGES + 1, TIMEOUT);
            // A duplicate would arrive right behind the others: give it time to show up.
            Thread.sleep(500);

            assertEquals(200, reconnected.statusCode());
            assertEquals(expectedIds, reconnected.receivedIds());
        }
    }

    /** A Last-Event-ID that this instance does not know is answered with 410 Gone. */
    @Test
    void unknownLastEventIdIsAnsweredWithGone() throws Exception {
        String baseUrl = "http://localhost:" + port;
        UUID roomId = UUID.randomUUID();
        UUID unknownId = UUID.randomUUID();

        try (SseTestClient client = new SseTestClient(baseUrl, roomId, unknownId)) {
            assertEquals(410, client.statusCode());
        }
    }

    /**
     * Opens the stream, sends one message and checks its event against the
     * contract. Then closes the connection, like a client that loses its network.
     */
    private ReceivedEvent receiveOneMessageAndDisconnect(String baseUrl, UUID roomId) throws Exception {
        try (SseTestClient client = new SseTestClient(baseUrl, roomId, null)) {
            UUID messageId = SseTestClient.postMessage(baseUrl, roomId, "anna", "Grüezi");
            client.waitForEvents(1, TIMEOUT);
            List<ReceivedEvent> events = client.events();
            ReceivedEvent event = events.get(0);
            JsonNode message = OBJECT_MAPPER.readTree(event.data());
            JsonNode idField = message.get("id");
            JsonNode roomIdField = message.get("roomId");
            JsonNode contentField = message.get("content");
            String contentType = client.contentType();

            assertEquals(200, client.statusCode());
            assertTrue(contentType.startsWith("text/event-stream"), contentType);
            assertEquals(messageId.toString(), event.id());
            assertEquals(messageId.toString(), idField.asText());
            assertEquals(roomId.toString(), roomIdField.asText());
            assertEquals("Grüezi", contentField.asText());
            return event;
        }
    }
}
