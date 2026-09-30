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
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S7: die Datenbank ist 15 s weg, in dieser Zeit kommen 300 Nachrichten.
 * Danach müssen alle 300 in der Tabelle stehen, nichts in der
 * Dead-Letter-Queue, und der Listener läuft ohne Neustart weiter.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DatabaseOutageIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private SimpleMessageListenerContainer listenerContainer;

    @Autowired
    private PostgreSQLContainer<?> postgresContainer;

    private ScenarioSupport scenario;

    /** Leere Tabelle und leere Queues vor jedem Test. */
    @BeforeEach
    void cleanStart() {
        scenario = new ScenarioSupport(jdbcTemplate, rabbitTemplate, amqpAdmin);
        scenario.reset();
    }

    /**
     * Während des Ausfalls hält DatabaseRetry das Paket fest, die Nachrichten
     * bleiben unbestätigt bei RabbitMQ. Nach dem Ende des Ausfalls gelingt der
     * nächste Versuch, erst dann folgt das ACK.
     */
    @Test
    void keepsMessagesWhileDatabaseIsDownAndWritesThemAfterwards() throws Exception {
        DatabaseOutage outage = new DatabaseOutage(postgresContainer);
        outage.begin();
        try {
            scenario.publishMessages(300);
            Thread.sleep(15_000);
        } finally {
            // Auch wenn der Test scheitert: die anderen Tests brauchen die Datenbank.
            outage.end();
        }

        scenario.waitForRowCount(300, Duration.ofSeconds(90));
        scenario.waitForAcknowledgements();

        assertEquals(300, scenario.countRows());
        assertEquals(0, scenario.messagesInQueue(QueueNames.DEAD_LETTER_QUEUE));
        assertTrue(listenerContainer.isRunning());
    }
}
