package ch.benedict.m321.chatservice.listener;

import ch.benedict.m321.chatservice.dto.LiveMessage;
import ch.benedict.m321.chatservice.service.RoomSubscriptions;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks without a broker what the listener does with a message from the
 * delivery queue. The ObjectMapper comes from the same builder Spring Boot uses.
 */
class DeliveryQueueListenerTest {

    /** A message exactly as the chat-service publishes it. */
    private static final String JSON = """
            {"id":"0b9f6c2e-5d1a-4c55-9a3e-2f0e7d1c4b11",\
            "roomId":"3f2b1c4e-0000-0000-0000-000000000001",\
            "senderId":"anna",\
            "senderName":"Anna Muster",\
            "content":"Grüezi mitenand",\
            "sentAt":"2026-11-11T08:15:30.123456Z"}""";

    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
    private final RecordingRoomSubscriptions roomSubscriptions = new RecordingRoomSubscriptions();
    private final DeliveryQueueListener listener = new DeliveryQueueListener(objectMapper, roomSubscriptions);

    /** Id and room come from the JSON, and the JSON itself goes on unchanged. */
    @Test
    void passesMessageOnUnchanged() {
        Message message = amqpMessage(JSON);

        listener.onMessage(message);

        assertEquals(1, roomSubscriptions.delivered.size());
        LiveMessage delivered = roomSubscriptions.delivered.get(0);
        assertEquals(UUID.fromString("0b9f6c2e-5d1a-4c55-9a3e-2f0e7d1c4b11"), delivered.id());
        assertEquals(UUID.fromString("3f2b1c4e-0000-0000-0000-000000000001"), delivered.roomId());
        assertEquals(JSON, delivered.json());
    }

    /** A body that is no JSON is dropped, without an exception the container would answer. */
    @Test
    void dropsUnreadableMessage() {
        Message message = amqpMessage("kein json");

        listener.onMessage(message);

        assertEquals(0, roomSubscriptions.delivered.size());
    }

    /** Without roomId nobody knows who should get the message, so it is dropped. */
    @Test
    void dropsMessageWithoutRoom() {
        String json = JSON.replace("\"roomId\":\"3f2b1c4e-0000-0000-0000-000000000001\",", "");
        Message message = amqpMessage(json);

        listener.onMessage(message);

        assertEquals(0, roomSubscriptions.delivered.size());
    }

    /** Builds an AMQP message with the text as UTF-8 body, like the JSON converter does. */
    private Message amqpMessage(String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        return new Message(bytes, properties);
    }

    /**
     * A test double that records the messages instead of sending them.
     * Written by hand like the RecordingPublisher in MessageServiceTest.
     */
    private static class RecordingRoomSubscriptions extends RoomSubscriptions {

        private final List<LiveMessage> delivered = new ArrayList<>();

        /** The buffer size does not matter here, nothing is buffered. */
        RecordingRoomSubscriptions() {
            super(10);
        }

        /** Records the message. */
        @Override
        public synchronized void deliver(LiveMessage message) {
            delivered.add(message);
        }
    }
}
