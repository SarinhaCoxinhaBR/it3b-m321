package ch.benedict.m321.batchwriter.service;

import ch.benedict.m321.batchwriter.TestcontainersConfiguration;
import ch.benedict.m321.batchwriter.dto.ChatMessage;
import ch.benedict.m321.batchwriter.dto.ReceivedMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prüft Transaktion und Einzelweg gegen einen echten PostgreSQL.
 *
 * Die zu lange Zeile ist der einfachste Weg, die Datenbank eine einzelne
 * Nachricht ablehnen zu lassen: sender_name ist VARCHAR(255), der
 * chat-service begrenzt die Länge aber nicht.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class MessageBatchWriterIntegrationTest {

    private static final UUID ROOM_ID = UUID.fromString("3f2b1c4e-0000-0000-0000-000000000001");

    @Autowired
    private MessageBatchWriter messageBatchWriter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Jeder Test beginnt mit einer leeren Tabelle. */
    @BeforeEach
    void emptyTable() {
        jdbcTemplate.execute("TRUNCATE message");
    }

    /** Ein gültiges Paket: alles geschrieben, nichts abgelehnt. */
    @Test
    void writesValidBatchCompletely() {
        ReceivedMessage first = received(1, "Anna Muster");
        ReceivedMessage second = received(2, "Ben Beispiel");
        ReceivedMessage third = received(3, "Cleo Test");

        List<ReceivedMessage> rejected = messageBatchWriter.write(List.of(first, second, third));

        assertTrue(rejected.isEmpty());
        assertEquals(3, countRows());
    }

    /**
     * Eine einzige zu lange Zeile im Paket: nur sie wird abgelehnt, die
     * anderen drei stehen trotzdem in der Tabelle.
     */
    @Test
    void rejectsOnlyRowTooLongForDatabase() {
        String tooLongName = "x".repeat(300);
        ReceivedMessage first = received(1, "Anna Muster");
        ReceivedMessage broken = received(2, tooLongName);
        ReceivedMessage third = received(3, "Cleo Test");
        ReceivedMessage fourth = received(4, "Dario Probe");

        List<ReceivedMessage> rejected = messageBatchWriter.write(List.of(first, broken, third, fourth));

        assertEquals(List.of(broken), rejected);
        assertEquals(3, countRows());
    }

    /** Zählt die Zeilen der Tabelle. */
    private int countRows() {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM message", Integer.class);
        return count;
    }

    /** Baut eine empfangene Nachricht mit frei gewählter Zustellnummer. */
    private ReceivedMessage received(long deliveryTag, String senderName) {
        UUID id = UUID.randomUUID();
        Instant sentAt = Instant.now();
        ChatMessage chatMessage = new ChatMessage(id, ROOM_ID, "user-" + deliveryTag, senderName, "Hallo", sentAt);
        return new ReceivedMessage(deliveryTag, chatMessage);
    }
}
