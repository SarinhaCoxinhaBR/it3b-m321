package ch.benedict.m321.batchwriter.repository;

import ch.benedict.m321.batchwriter.TestcontainersConfiguration;
import ch.benedict.m321.batchwriter.dto.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Prüft das INSERT gegen einen echten PostgreSQL. Ob ON CONFLICT wirkt,
 * entscheidet die Datenbank, nicht unser Code. Ein Mock würde hier also
 * nichts beweisen.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class MessageRepositoryIntegrationTest {

    private static final UUID ROOM_ID = UUID.fromString("3f2b1c4e-0000-0000-0000-000000000001");

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Jeder Test beginnt mit einer leeren Tabelle. */
    @BeforeEach
    void emptyTable() {
        jdbcTemplate.execute("TRUNCATE message");
    }

    /** Ein Paket mit drei Nachrichten ergibt drei Zeilen. */
    @Test
    void insertsEveryMessageOfBatch() {
        ChatMessage first = newMessage("eins");
        ChatMessage second = newMessage("zwei");
        ChatMessage third = newMessage("drei");

        messageRepository.insertAll(List.of(first, second, third));

        assertEquals(3, countRows());
    }

    /**
     * Dieselbe Nachricht zweimal im selben Paket, also im selben INSERT:
     * eine Zeile, kein Fehler.
     */
    @Test
    void ignoresDuplicateInSameBatch() {
        ChatMessage message = newMessage("doppelt");

        messageRepository.insertAll(List.of(message, message));

        assertEquals(1, countRows());
    }

    /**
     * Dieselbe Nachricht in zwei Paketen, wie nach einer erneuten Zustellung
     * durch RabbitMQ: eine Zeile, kein Fehler.
     */
    @Test
    void ignoresDuplicateAcrossBatches() {
        ChatMessage message = newMessage("nochmals");

        messageRepository.insertAll(List.of(message));
        messageRepository.insertAll(List.of(message));

        assertEquals(1, countRows());
    }

    /**
     * sent_at ist die Sendezeit aus dem chat-service, auf die Mikrosekunde
     * genau, und nicht der Moment des Schreibens.
     */
    @Test
    void keepsSentAtFromChatService() {
        Instant sentAt = Instant.parse("2026-10-02T08:15:30.123456Z");
        ChatMessage message = new ChatMessage(UUID.randomUUID(), ROOM_ID, "anna", "Anna Muster", "Zeit", sentAt);

        messageRepository.insertAll(List.of(message));

        String sql = "SELECT sent_at FROM message WHERE id = ?";
        OffsetDateTime stored = jdbcTemplate.queryForObject(sql, OffsetDateTime.class, message.id());
        Instant storedInstant = stored.toInstant();
        assertEquals(sentAt, storedInstant);
    }

    /** Zählt die Zeilen der Tabelle. */
    private int countRows() {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM message", Integer.class);
        return count;
    }

    /** Baut eine gültige Nachricht mit neuer id, wie sie der chat-service vergibt. */
    private ChatMessage newMessage(String content) {
        UUID id = UUID.randomUUID();
        Instant sentAt = Instant.now();
        return new ChatMessage(id, ROOM_ID, "anna", "Anna Muster", content, sentAt);
    }
}
