package ch.benedict.m321.batchwriter.listener;

import ch.benedict.m321.batchwriter.TestcontainersConfiguration;
import ch.benedict.m321.batchwriter.config.QueueNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Der ganze Weg von chat.persist bis in die Tabelle, mit echtem RabbitMQ und
 * echtem PostgreSQL. Die Tests entsprechen S3 und S5 aus dem Auftrag und den
 * beiden Wegen in die Dead-Letter-Queue aus der Spezifikation (Z1, Z2).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PersistFlowIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    private ScenarioSupport scenario;

    /** Leere Tabelle und leere Queues vor jedem Test. */
    @BeforeEach
    void cleanStart() {
        scenario = new ScenarioSupport(jdbcTemplate, rabbitTemplate, amqpAdmin);
        scenario.reset();
    }

    /** S3: 1000 Nachrichten stehen nach höchstens 60 s in der Tabelle, die Queue ist leer. */
    @Test
    void storesAllMessagesAndEmptiesQueue() throws InterruptedException {
        scenario.publishMessages(1000);

        scenario.waitForRowCount(1000, Duration.ofSeconds(60));
        scenario.waitUntil(() -> scenario.messagesInQueue(QueueNames.PERSIST_QUEUE) == 0,
                Duration.ofSeconds(10), "queue chat.persist empty");

        assertEquals(1000, scenario.countRows());
    }

    /**
     * S5: dieselbe Nachricht zweimal, nur mit content_type. Genau eine Zeile,
     * nichts in chat.dlq. Die Markierungsnachricht danach zeigt, dass beide
     * Kopien verarbeitet sind: der Listener arbeitet die Queue der Reihe nach ab.
     */
    @Test
    void storesDuplicateOnlyOnce() throws InterruptedException {
        UUID duplicateId = UUID.randomUUID();
        String duplicate = scenario.messageJson(duplicateId, "Anna Muster", "Zweimal gesendet");
        UUID markerId = UUID.randomUUID();
        String marker = scenario.messageJson(markerId, "Anna Muster", "Markierung");

        scenario.publishRaw(duplicate);
        scenario.publishRaw(duplicate);
        scenario.publishRaw(marker);

        scenario.waitUntil(() -> scenario.countRowsWithId(markerId) == 1,
                Duration.ofSeconds(30), "marker message stored");
        scenario.waitForAcknowledgements();
        assertEquals(1, scenario.countRowsWithId(duplicateId));
        assertEquals(0, scenario.messagesInQueue(QueueNames.DEAD_LETTER_QUEUE));
    }

    /**
     * Z1: kaputtes JSON landet unverändert in chat.dlq. Die gültige Nachricht
     * aus demselben Paket steht trotzdem in der Tabelle.
     */
    @Test
    void movesUnreadableMessageToDeadLetterQueue() throws InterruptedException {
        UUID validId = UUID.randomUUID();
        String valid = scenario.messageJson(validId, "Anna Muster", "Gültig");

        scenario.publishRaw("{kein json");
        scenario.publishRaw(valid);

        scenario.waitUntil(() -> scenario.countRowsWithId(validId) == 1,
                Duration.ofSeconds(30), "valid message stored");
        scenario.waitUntil(() -> scenario.messagesInQueue(QueueNames.DEAD_LETTER_QUEUE) == 1,
                Duration.ofSeconds(10), "one message in chat.dlq");
        String deadBody = scenario.receiveBodyFromDeadLetterQueue();
        assertEquals("{kein json", deadBody);
    }

    /**
     * Z2: die Datenbank lehnt eine einzelne Zeile ab (sender_name zu lang).
     * Nur diese Nachricht landet in chat.dlq, die drei anderen in der Tabelle.
     */
    @Test
    void movesRowRejectedByDatabaseToDeadLetterQueue() throws InterruptedException {
        UUID brokenId = UUID.randomUUID();
        String tooLongName = "x".repeat(300);
        String broken = scenario.messageJson(brokenId, tooLongName, "Zu langer Name");

        scenario.publishMessages(1);
        scenario.publishRaw(broken);
        scenario.publishMessages(2);

        scenario.waitForRowCount(3, Duration.ofSeconds(30));
        scenario.waitUntil(() -> scenario.messagesInQueue(QueueNames.DEAD_LETTER_QUEUE) == 1,
                Duration.ofSeconds(10), "one message in chat.dlq");
        assertEquals(3, scenario.countRows());
        assertEquals(0, scenario.countRowsWithId(brokenId));
    }
}
