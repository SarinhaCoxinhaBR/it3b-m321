package ch.benedict.m321.chatservice.service;

import ch.benedict.m321.chatservice.dto.LiveMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Knows which clients of this instance listen to which room, and keeps the
 * latest messages of every room for clients that reconnect.
 *
 * Every instance receives every message through its own delivery queue, but
 * only this instance knows its open connections. So the decision "who gets
 * this message" is made here, by room.
 *
 * All public methods are synchronized on this object. The delivery thread,
 * the HTTP threads and the keep-alive timer use the same rooms, and one lock
 * guarantees that a reconnecting client gets the missed messages and then the
 * live ones without a gap and without a duplicate (scenario S7).
 */
@Slf4j
@Component
public class RoomSubscriptions {

    private static final long KEEP_ALIVE_INTERVAL_MILLIS = 15_000;

    private final int replayBufferSize;
    private final Map<UUID, Room> rooms = new HashMap<>();

    /**
     * The buffer size comes from the configuration, so a unit test can use a
     * small one instead of 5000.
     */
    public RoomSubscriptions(@Value("${chat-service.replay-buffer-size}") int replayBufferSize) {
        this.replayBufferSize = replayBufferSize;
    }

    /**
     * Registers a client for a room.
     *
     * Without lastEventId the client only gets new messages. With lastEventId
     * it first gets every message of the room that came after that one, then
     * the live ones. Both happen under the same lock as deliver, so no live
     * message can slip in between.
     *
     * @return false if lastEventId is not in the buffer of the room (too old,
     *         or the instance has restarted). The client is then not registered,
     *         because it would otherwise miss messages without noticing.
     */
    public synchronized boolean subscribe(UUID roomId, UUID lastEventId, SseEmitter client) {
        Room room = findOrCreateRoom(roomId);
        List<LiveMessage> missedMessages = new ArrayList<>();
        if (lastEventId != null) {
            int lastPosition = positionOf(room.recentMessages, lastEventId);
            if (lastPosition < 0) {
                log.info("Last-Event-ID {} not in the buffer of room {}, client rejected", lastEventId, roomId);
                return false;
            }
            missedMessages = messagesAfter(room.recentMessages, lastPosition);
        }
        for (LiveMessage missedMessage : missedMessages) {
            send(roomId, client, missedMessage);
        }
        room.clients.add(client);
        // Runs when the connection ends for any reason: closed, timed out or broken.
        client.onCompletion(() -> removeClient(roomId, client));
        log.info("Client subscribed to room {}, {} missed messages sent", roomId, missedMessages.size());
        return true;
    }

    /**
     * Remembers a message for reconnecting clients and sends it to every client
     * of its room. Called by the delivery queue listener, one message at a time.
     */
    public synchronized void deliver(LiveMessage message) {
        UUID roomId = message.roomId();
        Room room = findOrCreateRoom(roomId);
        remember(room, message);
        for (SseEmitter client : room.clients) {
            send(roomId, client, message);
        }
    }

    /**
     * Sends a comment line to every client every 15 seconds. Clients ignore
     * it, but writing it shows whether the connection still exists: a client
     * that is gone fails here and is removed, even in a room without traffic.
     */
    @Scheduled(fixedRate = KEEP_ALIVE_INTERVAL_MILLIS)
    public synchronized void sendKeepAlive() {
        for (Map.Entry<UUID, Room> entry : rooms.entrySet()) {
            UUID roomId = entry.getKey();
            Room room = entry.getValue();
            for (SseEmitter client : room.clients) {
                SseEmitter.SseEventBuilder keepAlive = SseEmitter.event().comment("keep-alive");
                sendEvent(roomId, client, keepAlive);
            }
        }
    }

    /**
     * Ends every open connection when the instance shuts down. Without this,
     * the graceful shutdown of the web server would wait for connections that
     * never end on their own. The clients reconnect with their Last-Event-ID,
     * to another instance if there is one.
     */
    @EventListener(ContextClosedEvent.class)
    public synchronized void closeAllClients() {
        for (Room room : rooms.values()) {
            for (SseEmitter client : room.clients) {
                client.complete();
            }
        }
        log.info("All client connections closed for shutdown");
    }

    /**
     * Sends one message as an event: the message id in the id field, the
     * unchanged JSON in the data field. A new event per client, because an
     * event builder must not be sent twice.
     */
    private void send(UUID roomId, SseEmitter client, LiveMessage message) {
        UUID messageId = message.id();
        SseEmitter.SseEventBuilder event = SseEmitter.event()
                .id(messageId.toString())
                .data(message.json());
        sendEvent(roomId, client, event);
    }

    /**
     * Writes an event to a client. If that fails, the client is gone and is
     * removed. Only the type of the error is logged: the message of a failed
     * send can contain the event and with it the chat text.
     */
    private void sendEvent(UUID roomId, SseEmitter client, SseEmitter.SseEventBuilder event) {
        try {
            client.send(event);
        } catch (IOException | IllegalStateException exception) {
            String errorType = exception.getClass().getSimpleName();
            log.info("Client of room {} is gone ({}), removed", roomId, errorType);
            removeClient(roomId, client);
        }
    }

    /**
     * Removes a client from its room. Called after a failed send and by the
     * completion callback, which runs on a thread of the web server.
     */
    private synchronized void removeClient(UUID roomId, SseEmitter client) {
        Room room = rooms.get(roomId);
        room.clients.remove(client);
    }

    /**
     * Adds a message to the buffer of its room and drops the oldest one once
     * the buffer is full.
     */
    private void remember(Room room, LiveMessage message) {
        room.recentMessages.addLast(message);
        if (room.recentMessages.size() > replayBufferSize) {
            room.recentMessages.removeFirst();
        }
    }

    /**
     * Returns the room, and creates it on its first message or first client.
     * Rooms are never removed: their buffer must survive while nobody listens.
     */
    private Room findOrCreateRoom(UUID roomId) {
        Room room = rooms.get(roomId);
        if (room == null) {
            room = new Room();
            rooms.put(roomId, room);
        }
        return room;
    }

    /**
     * Position of a message in the buffer, counted from the oldest one.
     *
     * @return -1 if the message is not in the buffer
     */
    private int positionOf(Deque<LiveMessage> messages, UUID messageId) {
        int position = 0;
        for (LiveMessage message : messages) {
            if (message.id().equals(messageId)) {
                return position;
            }
            position = position + 1;
        }
        return -1;
    }

    /** All messages of the buffer that come after the given position, oldest first. */
    private List<LiveMessage> messagesAfter(Deque<LiveMessage> messages, int position) {
        List<LiveMessage> laterMessages = new ArrayList<>();
        int currentPosition = 0;
        for (LiveMessage message : messages) {
            if (currentPosition > position) {
                laterMessages.add(message);
            }
            currentPosition = currentPosition + 1;
        }
        return laterMessages;
    }

    /**
     * The open connections and the latest messages of one room.
     *
     * The client list is a CopyOnWriteArrayList because a failed send removes
     * a client while deliver is still walking through the list.
     */
    private static class Room {
        private final List<SseEmitter> clients = new CopyOnWriteArrayList<>();
        private final Deque<LiveMessage> recentMessages = new ArrayDeque<>();
    }
}
