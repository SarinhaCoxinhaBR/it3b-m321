package ch.benedict.m321.chatservice.dto;

import java.util.UUID;

/**
 * One message on its way to the clients of a room.
 *
 * The JSON is kept exactly as it came out of the delivery queue and goes out
 * unchanged in the data field of the event. That is how the stream carries
 * the same format as chat.persist without a second serialization.
 *
 * @param id     the message id, sent as the id field of the event and used
 *               as Last-Event-ID when a client reconnects
 * @param roomId the room of the message; only clients of this room get it
 * @param json   the message as JSON, exactly as the chat-service published it
 */
public record LiveMessage(UUID id, UUID roomId, String json) {
}
