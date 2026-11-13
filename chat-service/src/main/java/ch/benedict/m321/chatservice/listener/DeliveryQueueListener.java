package ch.benedict.m321.chatservice.listener;

import ch.benedict.m321.chatservice.dto.ChatMessage;
import ch.benedict.m321.chatservice.dto.LiveMessage;
import ch.benedict.m321.chatservice.service.RoomSubscriptions;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Takes every message from the delivery queue of this instance and hands it
 * to RoomSubscriptions, which sends it to the clients of its room.
 *
 * The container acknowledges a message after this method has returned. An
 * unreadable message is logged and dropped: a second try with the same bytes
 * would fail the same way.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeliveryQueueListener implements MessageListener {

    private final ObjectMapper objectMapper;
    private final RoomSubscriptions roomSubscriptions;

    /**
     * Reads id and room from the JSON and passes the body on unchanged, so the
     * stream carries exactly the format the chat-service published.
     */
    @Override
    public void onMessage(Message message) {
        byte[] body = message.getBody();
        ChatMessage chatMessage = readChatMessage(body);
        if (chatMessage == null) {
            return;
        }
        String json = new String(body, StandardCharsets.UTF_8);
        LiveMessage liveMessage = new LiveMessage(chatMessage.id(), chatMessage.roomId(), json);
        roomSubscriptions.deliver(liveMessage);
    }

    /**
     * Turns the body into a ChatMessage.
     *
     * @return null if the body is no JSON or has no id or roomId. The log gets
     *         only the type of the error, never the body: it is a private chat text.
     */
    private ChatMessage readChatMessage(byte[] body) {
        ChatMessage chatMessage;
        try {
            chatMessage = objectMapper.readValue(body, ChatMessage.class);
        } catch (IOException exception) {
            String errorType = exception.getClass().getSimpleName();
            log.warn("Unreadable message dropped ({})", errorType);
            return null;
        }
        if (chatMessage == null || chatMessage.id() == null || chatMessage.roomId() == null) {
            log.warn("Message without id or roomId dropped");
            return null;
        }
        return chatMessage;
    }
}
