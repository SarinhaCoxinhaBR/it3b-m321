package ch.benedict.m321.chatservice.service;

import ch.benedict.m321.chatservice.dto.LiveMessage;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the rules of RoomSubscriptions without a web server and without a
 * broker: who gets a message, what goes over the wire, what a reconnect gets.
 * The buffer holds only three messages, so the limit is easy to reach.
 */
class RoomSubscriptionsTest {

    private static final int BUFFER_SIZE = 3;

    private final RoomSubscriptions roomSubscriptions = new RoomSubscriptions(BUFFER_SIZE);

    /** Scenario S3: a message reaches the clients of its room and nobody else. */
    @Test
    void deliversOnlyToClientsOfTheRoom() {
        UUID roomA = UUID.randomUUID();
        UUID roomB = UUID.randomUUID();
        RecordingClient clientInRoomA = new RecordingClient();
        RecordingClient clientInRoomB = new RecordingClient();
        roomSubscriptions.subscribe(roomA, null, clientInRoomA);
        roomSubscriptions.subscribe(roomB, null, clientInRoomB);
        LiveMessage message = messageFor(roomA);

        roomSubscriptions.deliver(message);

        assertEquals(List.of(message.id()), clientInRoomA.receivedIds());
        assertEquals(List.of(), clientInRoomB.receivedIds());
    }

    /** The contract on the wire: the id in the id field, the unchanged JSON in the data field. */
    @Test
    void sendsIdAndUnchangedJson() {
        UUID roomId = UUID.randomUUID();
        RecordingClient client = new RecordingClient();
        roomSubscriptions.subscribe(roomId, null, client);
        LiveMessage message = messageFor(roomId);

        roomSubscriptions.deliver(message);

        String expectedEvent = "id:" + message.id() + "\ndata:" + message.json() + "\n\n";
        assertEquals(expectedEvent, client.receivedText());
    }

    /** Scenario S7: a reconnect gets exactly the messages after Last-Event-ID, then the live ones. */
    @Test
    void replaysMessagesAfterLastEventIdThenLiveOnes() {
        UUID roomId = UUID.randomUUID();
        LiveMessage first = messageFor(roomId);
        LiveMessage second = messageFor(roomId);
        LiveMessage third = messageFor(roomId);
        roomSubscriptions.deliver(first);
        roomSubscriptions.deliver(second);
        roomSubscriptions.deliver(third);
        RecordingClient client = new RecordingClient();

        boolean subscribed = roomSubscriptions.subscribe(roomId, first.id(), client);
        LiveMessage live = messageFor(roomId);
        roomSubscriptions.deliver(live);

        assertTrue(subscribed);
        assertEquals(List.of(second.id(), third.id(), live.id()), client.receivedIds());
    }

    /**
     * A Last-Event-ID that has left the full buffer is unknown. The client is
     * not registered and gets nothing, so the controller can answer 410.
     */
    @Test
    void rejectsLastEventIdThatLeftTheBuffer() {
        UUID roomId = UUID.randomUUID();
        LiveMessage oldest = messageFor(roomId);
        roomSubscriptions.deliver(oldest);
        for (int i = 0; i < BUFFER_SIZE; i++) {
            LiveMessage newer = messageFor(roomId);
            roomSubscriptions.deliver(newer);
        }
        RecordingClient client = new RecordingClient();

        boolean subscribed = roomSubscriptions.subscribe(roomId, oldest.id(), client);
        LiveMessage live = messageFor(roomId);
        roomSubscriptions.deliver(live);

        assertFalse(subscribed);
        assertEquals(List.of(), client.receivedIds());
    }

    /** A client whose connection broke is removed after the first failed send. */
    @Test
    void removesClientAfterFailedSend() {
        UUID roomId = UUID.randomUUID();
        RecordingClient brokenClient = new RecordingClient();
        brokenClient.broken = true;
        roomSubscriptions.subscribe(roomId, null, brokenClient);
        LiveMessage first = messageFor(roomId);
        LiveMessage second = messageFor(roomId);

        roomSubscriptions.deliver(first);
        roomSubscriptions.deliver(second);

        assertEquals(1, brokenClient.sendAttempts);
    }

    /** A message for a room with a new id. The JSON holds only what the tests need. */
    private LiveMessage messageFor(UUID roomId) {
        UUID messageId = UUID.randomUUID();
        String json = "{\"id\":\"" + messageId + "\",\"roomId\":\"" + roomId + "\",\"content\":\"Grüezi\"}";
        return new LiveMessage(messageId, roomId, json);
    }

    /**
     * A client that records what would go over the wire instead of writing to
     * a connection. With broken set, every send fails like a closed connection.
     */
    private static class RecordingClient extends SseEmitter {

        private final StringBuilder wire = new StringBuilder();
        private boolean broken;
        private int sendAttempts;

        /** Records the parts of the event as text, or fails if the connection is broken. */
        @Override
        public void send(SseEventBuilder builder) throws IOException {
            sendAttempts = sendAttempts + 1;
            if (broken) {
                throw new IOException("Broken pipe");
            }
            Set<DataWithMediaType> parts = builder.build();
            for (DataWithMediaType part : parts) {
                Object data = part.getData();
                wire.append(data);
            }
        }

        /** Everything that went over the wire, as one text. */
        String receivedText() {
            return wire.toString();
        }

        /** The ids of all recorded events, in the order they were sent. */
        List<UUID> receivedIds() {
            List<UUID> ids = new ArrayList<>();
            String text = wire.toString();
            String[] lines = text.split("\n");
            for (String line : lines) {
                if (line.startsWith("id:")) {
                    String id = line.substring(3);
                    UUID messageId = UUID.fromString(id);
                    ids.add(messageId);
                }
            }
            return ids;
        }
    }
}
