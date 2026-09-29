package ch.benedict.m321.batchwriter.service;

import ch.benedict.m321.batchwriter.dto.ChatMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Service;

import java.io.IOException;

/**
 * Macht aus einer AMQP-Nachricht eine ChatMessage und prüft sie.
 *
 * Gelesen wird nur der Body. Der Header __TypeId__ des chat-service wird
 * absichtlich ignoriert: Szenario S5 legt Nachrichten nur mit content_type
 * in die Queue, und der Header nennt ohnehin eine Klasse aus einem fremden
 * Dienst.
 */
@Service
@RequiredArgsConstructor
public class MessageParser {

    /**
     * Der ObjectMapper von Spring Boot. Er liest Instant im ISO-Format und
     * ignoriert unbekannte Felder, damit ein neues Feld im chat-service
     * nicht jede Nachricht unlesbar macht.
     */
    private final ObjectMapper objectMapper;

    /**
     * Liest den Body als JSON und prüft die Pflichtfelder.
     *
     * @throws InvalidMessageException wenn das JSON kaputt ist oder ein Feld
     *                                 fehlt; die Nachricht kann dann nie
     *                                 geschrieben werden
     */
    public ChatMessage parse(Message message) throws InvalidMessageException {
        byte[] body = message.getBody();
        ChatMessage chatMessage = readJson(body);
        checkRequiredFields(chatMessage);
        return chatMessage;
    }

    /**
     * Übersetzt die Bytes in eine ChatMessage. Jeder Fehler von Jackson wird
     * zur InvalidMessageException, weil derselbe Body beim nächsten Versuch
     * wieder genauso kaputt wäre.
     *
     * getOriginalMessage statt getMessage: so steht im Log der Grund, aber
     * kein Ausschnitt aus dem Body und damit kein privater Chat-Text.
     */
    private ChatMessage readJson(byte[] body) throws InvalidMessageException {
        ChatMessage chatMessage;
        try {
            chatMessage = objectMapper.readValue(body, ChatMessage.class);
        } catch (JsonProcessingException exception) {
            throw new InvalidMessageException("Body is not valid JSON: " + exception.getOriginalMessage(), exception);
        } catch (IOException exception) {
            throw new InvalidMessageException("Body could not be read", exception);
        }
        if (chatMessage == null) {
            throw new InvalidMessageException("Body is JSON null");
        }
        return chatMessage;
    }

    /**
     * Dieselben Regeln wie beim Eingang im chat-service: IDs und Zeitstempel
     * müssen da sein, Texte dürfen nicht leer sein. Was hier durchfällt,
     * würde spätestens an NOT NULL in der Tabelle scheitern.
     */
    private void checkRequiredFields(ChatMessage chatMessage) throws InvalidMessageException {
        if (chatMessage.id() == null) {
            throw new InvalidMessageException("Field id is missing");
        }
        if (chatMessage.roomId() == null) {
            throw new InvalidMessageException("Field roomId is missing in message " + chatMessage.id());
        }
        if (chatMessage.sentAt() == null) {
            throw new InvalidMessageException("Field sentAt is missing in message " + chatMessage.id());
        }
        if (isBlank(chatMessage.senderId())) {
            throw new InvalidMessageException("Field senderId is empty in message " + chatMessage.id());
        }
        if (isBlank(chatMessage.senderName())) {
            throw new InvalidMessageException("Field senderName is empty in message " + chatMessage.id());
        }
        if (isBlank(chatMessage.content())) {
            throw new InvalidMessageException("Field content is empty in message " + chatMessage.id());
        }
    }

    /**
     * Fehlt ein Text oder besteht er nur aus Leerzeichen? Genau das prüft
     * auch @NotBlank im chat-service.
     */
    private boolean isBlank(String text) {
        if (text == null) {
            return true;
        }
        return text.isBlank();
    }
}
