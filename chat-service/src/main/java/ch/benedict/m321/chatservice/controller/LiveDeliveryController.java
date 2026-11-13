package ch.benedict.m321.chatservice.controller;

import ch.benedict.m321.chatservice.service.RoomSubscriptions;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

/**
 * The way out to the clients: GET /stream keeps the connection open and
 * pushes every message of the room as a Server-Sent Event.
 *
 * Like POST /messages it is only reachable inside the Docker network, so it
 * checks no token either.
 */
@RestController
@RequiredArgsConstructor
public class LiveDeliveryController {

    /**
     * No server-side time limit for the open connection. Dead connections are
     * found by the keep-alive in RoomSubscriptions.
     */
    private static final long NO_TIMEOUT = 0L;

    private final RoomSubscriptions roomSubscriptions;

    /**
     * Opens the event stream of a room. SseEmitter sets the content type
     * text/event-stream.
     *
     * A browser's EventSource sends Last-Event-ID by itself when it reconnects.
     * If that message is no longer known, the answer is 410 Gone: the client
     * has a gap and has to reload, instead of silently missing messages.
     */
    @GetMapping("/stream")
    public ResponseEntity<SseEmitter> subscribe(
            @RequestParam UUID roomId,
            @RequestHeader(name = "Last-Event-ID", required = false) UUID lastEventId) {
        SseEmitter client = new SseEmitter(NO_TIMEOUT);
        boolean subscribed = roomSubscriptions.subscribe(roomId, lastEventId, client);
        if (!subscribed) {
            return ResponseEntity.status(HttpStatus.GONE).build();
        }
        return ResponseEntity.ok(client);
    }
}
