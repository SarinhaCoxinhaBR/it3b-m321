package ch.benedict.m321.chatservice.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A small test client that talks to a running chat-service over real HTTP,
 * the way the scenario script does with curl: it opens GET /stream and
 * collects the events in a background thread, and it sends POST /messages.
 */
class SseTestClient implements AutoCloseable {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final long POLL_INTERVAL_MILLIS = 50;

    private final HttpResponse<InputStream> response;
    private final List<ReceivedEvent> events = new CopyOnWriteArrayList<>();

    /**
     * Opens the event stream of a room. Returns as soon as the response
     * headers are in; by then the server has registered the client.
     *
     * @param lastEventId sent as header Last-Event-ID, or null for none
     */
    SseTestClient(String baseUrl, UUID roomId, UUID lastEventId) throws IOException, InterruptedException {
        URI uri = URI.create(baseUrl + "/stream?roomId=" + roomId);
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri).GET();
        if (lastEventId != null) {
            requestBuilder.header("Last-Event-ID", lastEventId.toString());
        }
        HttpRequest request = requestBuilder.build();
        response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
        Thread readerThread = new Thread(this::readEvents, "sse-test-client");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    /**
     * Sends a message with POST /messages and returns the id from the 202
     * answer. The sender id is also used as display name.
     */
    static UUID postMessage(String baseUrl, UUID roomId, String senderId, String content)
            throws IOException, InterruptedException {
        String json = """
                {"roomId":"%s","senderId":"%s","senderName":"%s","content":"%s"}"""
                .formatted(roomId, senderId, senderId, content);
        URI uri = URI.create(baseUrl + "/messages");
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> answer = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (answer.statusCode() != 202) {
            throw new IllegalStateException("POST /messages answered " + answer.statusCode());
        }
        JsonNode body = OBJECT_MAPPER.readTree(answer.body());
        JsonNode idNode = body.get("id");
        String id = idNode.asText();
        return UUID.fromString(id);
    }

    /** The HTTP status of the stream request, 200 or for example 410. */
    int statusCode() {
        return response.statusCode();
    }

    /** The Content-Type header of the stream response, or an empty text. */
    String contentType() {
        HttpHeaders headers = response.headers();
        return headers.firstValue("Content-Type").orElse("");
    }

    /** A copy of all events received so far, in order. */
    List<ReceivedEvent> events() {
        return new ArrayList<>(events);
    }

    /** The ids of all events received so far, in order. */
    List<String> receivedIds() {
        List<String> ids = new ArrayList<>();
        for (ReceivedEvent event : events) {
            ids.add(event.id());
        }
        return ids;
    }

    /** Waits until at least the given number of events has arrived, or fails. */
    void waitForEvents(int expectedCount, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (events.size() < expectedCount) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("Expected " + expectedCount + " events, got " + events.size());
            }
            Thread.sleep(POLL_INTERVAL_MILLIS);
        }
    }

    /** Closes the connection, like a client that goes away. */
    @Override
    public void close() throws IOException {
        InputStream body = response.body();
        body.close();
    }

    /**
     * Reads the body line by line. "id:" and "data:" lines are collected, an
     * empty line ends an event. Comment lines such as the keep-alive are skipped.
     */
    private void readEvents() {
        InputStream body = response.body();
        InputStreamReader bodyReader = new InputStreamReader(body, StandardCharsets.UTF_8);
        try (BufferedReader reader = new BufferedReader(bodyReader)) {
            String id = null;
            String data = null;
            String line = reader.readLine();
            while (line != null) {
                if (line.startsWith("id:")) {
                    id = line.substring(3);
                } else if (line.startsWith("data:")) {
                    data = line.substring(5);
                } else if (line.isEmpty() && data != null) {
                    ReceivedEvent event = new ReceivedEvent(id, data);
                    events.add(event);
                    id = null;
                    data = null;
                }
                line = reader.readLine();
            }
        } catch (IOException exception) {
            // The test has closed the connection: nothing more to read.
        }
    }

    /**
     * One event as it arrived.
     *
     * @param id   the content of the id field
     * @param data the content of the data field, the message as JSON
     */
    record ReceivedEvent(String id, String data) {
    }
}
