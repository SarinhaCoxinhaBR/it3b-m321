package ch.benedict.m321.batchwriter.repository;

import ch.benedict.m321.batchwriter.dto.ChatMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Die einzige Stelle im Dienst, die SQL schreibt.
 *
 * Bewusst JdbcTemplate und kein JPA: das INSERT steht hier im Klartext,
 * und jeder sieht, dass es ON CONFLICT enthält.
 */
@Repository
@RequiredArgsConstructor
public class MessageRepository {

    /**
     * ON CONFLICT (id) DO NOTHING verwirft eine Nachricht, deren id schon in
     * der Tabelle steht. Damit ist eine erneute Zustellung durch RabbitMQ
     * harmlos (Szenario S5), auch wenn beide Kopien im selben Paket stecken.
     */
    private static final String INSERT_SQL = """
            INSERT INTO message (id, room_id, sender_id, sender_name, content, sent_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO NOTHING
            """;

    private final JdbcTemplate jdbcTemplate;

    /**
     * Schreibt alle Nachrichten mit einem einzigen batchUpdate.
     *
     * Die Transaktion öffnet der Aufrufer. Ohne sie würde jede Anweisung des
     * Batch einzeln bestätigt, und aus einem Paket würden mehrere Commits.
     */
    public void insertAll(List<ChatMessage> messages) {
        List<Object[]> rows = new ArrayList<>();
        for (ChatMessage message : messages) {
            Object[] row = toRow(message);
            rows.add(row);
        }
        jdbcTemplate.batchUpdate(INSERT_SQL, rows);
    }

    /**
     * Bringt eine Nachricht in die Reihenfolge der Fragezeichen im INSERT.
     *
     * Instant wird zu OffsetDateTime in UTC. Diesen Typ bildet der
     * PostgreSQL-Treiber direkt auf timestamptz ab, ohne Umweg über die
     * Zeitzone des Servers.
     */
    private Object[] toRow(ChatMessage message) {
        Instant sentAtInstant = message.sentAt();
        OffsetDateTime sentAt = sentAtInstant.atOffset(ZoneOffset.UTC);
        return new Object[] {
                message.id(),
                message.roomId(),
                message.senderId(),
                message.senderName(),
                message.content(),
                sentAt
        };
    }
}
