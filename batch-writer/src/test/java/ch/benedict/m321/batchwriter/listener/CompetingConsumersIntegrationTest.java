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

/**
 * S6: zwei Konsumenten an derselben Queue (Competing Consumers).
 *
 * Im Stack sind das zwei Prozesse (--scale batch-writer=2), das prüft
 * scripts/scenarios.sh. Hier sind es zwei Konsumenten in einem Prozess.
 * Für RabbitMQ ist das dieselbe Lage: zwei Konsumenten, jede Nachricht geht
 * an genau einen, und keiner weiss vom anderen.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CompetingConsumersIntegrationTest {

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
     * Beide Konsumenten hängen an chat.persist, alle 1000 Nachrichten kommen
     * an, keine doppelt und keine in der Dead-Letter-Queue.
     */
    @Test
    void twoConsumersShareQueueWithoutDuplicates() throws InterruptedException {
        listenerContainer.setConcurrentConsumers(2);
        try {
            scenario.waitUntil(() -> scenario.consumersOnQueue(QueueNames.PERSIST_QUEUE) == 2,
                    Duration.ofSeconds(30), "two consumers on chat.persist");

            scenario.publishMessages(1000);
            scenario.waitForRowCount(1000, Duration.ofSeconds(60));
            scenario.waitForAcknowledgements();

            assertEquals(1000, scenario.countRows());
            assertEquals(0, scenario.messagesInQueue(QueueNames.DEAD_LETTER_QUEUE));
        } finally {
            // Zurück auf einen Konsumenten, wie in application.yml.
            listenerContainer.setConcurrentConsumers(1);
            scenario.waitUntil(() -> scenario.consumersOnQueue(QueueNames.PERSIST_QUEUE) == 1,
                    Duration.ofSeconds(30), "one consumer on chat.persist");
        }
    }
}
