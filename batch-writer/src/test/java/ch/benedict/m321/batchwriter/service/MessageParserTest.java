package ch.benedict.m321.batchwriter.service;

import ch.benedict.m321.batchwriter.dto.ChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Prüft den Vertrag mit dem chat-service ohne Container.
 *
 * Der ObjectMapper kommt aus demselben Builder, den Spring Boot benutzt.
 * So verhält er sich im Test wie im laufenden Dienst.
 */
class MessageParserTest {

    /** Eine Nachricht genau so, wie der chat-service sie schreibt. */
    private static final String VALID_JSON = """
            {"id":"0b9f6c2e-5d1a-4c55-9a3e-2f0e7d1c4b11",\
            "roomId":"3f2b1c4e-0000-0000-0000-000000000001",\
            "senderId":"anna",\
            "senderName":"Anna Muster",\
            "content":"Hallo zusammen",\
            "sentAt":"2026-10-02T08:15:30.123456Z"}""";

    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
    private final MessageParser messageParser = new MessageParser(objectMapper);

    /**
     * Jedes Feld kommt unverändert an, auch die Mikrosekunden von sentAt.
     * Die Nachricht trägt nur content_type, wie in Szenario S5.
     */
    @Test
    void readsMessageInChatServiceFormat() throws InvalidMessageException {
        Message message = amqpMessage(VALID_JSON);

        ChatMessage chatMessage = messageParser.parse(message);

        assertEquals(UUID.fromString("0b9f6c2e-5d1a-4c55-9a3e-2f0e7d1c4b11"), chatMessage.id());
        assertEquals(UUID.fromString("3f2b1c4e-0000-0000-0000-000000000001"), chatMessage.roomId());
        assertEquals("anna", chatMessage.senderId());
        assertEquals("Anna Muster", chatMessage.senderName());
        assertEquals("Hallo zusammen", chatMessage.content());
        assertEquals(Instant.parse("2026-10-02T08:15:30.123456Z"), chatMessage.sentAt());
    }

    /**
     * Ein zusätzliches Feld, das der chat-service später vielleicht
     * mitschickt, macht die Nachricht nicht unlesbar.
     */
    @Test
    void ignoresUnknownFields() throws InvalidMessageException {
        String json = VALID_JSON.replace("}", ",\"editedAt\":null}");
        Message message = amqpMessage(json);

        ChatMessage chatMessage = messageParser.parse(message);

        assertEquals("Hallo zusammen", chatMessage.content());
    }

    /** Kaputtes JSON ist unlesbar und gehört in die Dead-Letter-Queue. */
    @Test
    void rejectsBrokenJson() {
        Message message = amqpMessage("{kein json");

        assertThrows(InvalidMessageException.class, () -> messageParser.parse(message));
    }

    /** Ein leerer Body ist unlesbar. */
    @Test
    void rejectsEmptyBody() {
        Message message = amqpMessage("");

        assertThrows(InvalidMessageException.class, () -> messageParser.parse(message));
    }

    /** Ohne id gäbe es keinen Primärschlüssel und kein ON CONFLICT. */
    @Test
    void rejectsMissingId() {
        String json = VALID_JSON.replace("\"id\":\"0b9f6c2e-5d1a-4c55-9a3e-2f0e7d1c4b11\",", "");
        Message message = amqpMessage(json);

        assertThrows(InvalidMessageException.class, () -> messageParser.parse(message));
    }

    /** Eine id, die keine UUID ist, ist unlesbar. */
    @Test
    void rejectsIdThatIsNoUuid() {
        String json = VALID_JSON.replace("0b9f6c2e-5d1a-4c55-9a3e-2f0e7d1c4b11", "abc");
        Message message = amqpMessage(json);

        assertThrows(InvalidMessageException.class, () -> messageParser.parse(message));
    }

    /** Ein Text aus Leerzeichen gilt als leer, wie bei @NotBlank im chat-service. */
    @Test
    void rejectsBlankContent() {
        String json = VALID_JSON.replace("Hallo zusammen", "   ");
        Message message = amqpMessage(json);

        assertThrows(InvalidMessageException.class, () -> messageParser.parse(message));
    }

    /**
     * Baut eine AMQP-Nachricht mit dem JSON als Body und nur dem Header
     * content_type, so wie Szenario S5 sie in die Queue legt.
     */
    private Message amqpMessage(String json) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        return new Message(body, properties);
    }
}
