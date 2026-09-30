package ch.benedict.m321.batchwriter.listener;

import ch.benedict.m321.batchwriter.TestcontainersConfiguration;
import ch.benedict.m321.batchwriter.config.QueueNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S4: Rückstau. Der batch-writer war weg, 1000 Nachrichten warten in der
 * Queue. Nach dem Start müssen alle in der Tabelle stehen, und die Datenbank
 * darf dafür höchstens 100 Transaktionen ausführen.
 *
 * Statt den Prozess zu stoppen, stoppt der Test den Listener-Container. Für
 * RabbitMQ ist das dasselbe: der Konsument verschwindet von der Queue.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class BacklogIntegrationTest {

    /** Obergrenze aus dem Auftrag für 1000 Nachrichten. */
    private static final long MAX_TRANSACTIONS = 100;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private SimpleMessageListenerContainer listenerContainer;

    private ScenarioSupport scenario;

    /** Leere Tabelle und leere Queues vor jedem Test. */
    @BeforeEach
    void cleanStart() {
        scenario = new ScenarioSupport(jdbcTemplate, rabbitTemplate, amqpAdmin);
        scenario.reset();
    }

    /**
     * Misst xact_commit vor dem Start des Listeners und nach dem Schreiben.
     * Erwartet sind zwei volle Pakete zu 500, dazu die Abfragen des Tests
     * selbst. Das liegt weit unter der Grenze.
     */
    @Test
    void writesBacklogWithFewTransactions() throws InterruptedException {
        listenerContainer.stop();
        try {
            scenario.publishMessages(1000);
            scenario.waitUntil(() -> scenario.messagesInQueue(QueueNames.PERSIST_QUEUE) == 1000,
                    Duration.ofSeconds(30), "1000 messages waiting in chat.persist");
            scenario.waitForStatistics();
            long transactionsBefore = scenario.committedTransactions();

            listenerContainer.start();
            scenario.waitForRowCount(1000, Duration.ofSeconds(60));
            scenario.waitForStatistics();
            long transactionsAfter = scenario.committedTransactions();

            long transactions = transactionsAfter - transactionsBefore;
            assertEquals(1000, scenario.countRows());
            assertTrue(transactions <= MAX_TRANSACTIONS, "Transactions for 1000 messages: " + transactions);
        } finally {
            // Läuft er schon, tut start() nichts. Die anderen Tests brauchen ihn.
            listenerContainer.start();
        }
    }
}
