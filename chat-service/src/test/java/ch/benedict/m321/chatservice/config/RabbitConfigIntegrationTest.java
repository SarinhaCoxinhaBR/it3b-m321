package ch.benedict.m321.chatservice.config;

import ch.benedict.m321.chatservice.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Prüft gegen einen ECHTEN RabbitMQ, dass unsere Queues beim Start
 * tatsächlich angelegt werden. Ein Mock würde hier nichts beweisen:
 * die Deklaration passiert im Broker, nicht in unserem Code.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RabbitConfigIntegrationTest {

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Test
    void declaresPersistQueue() {
        Properties properties = rabbitAdmin.getQueueProperties(QueueNames.PERSIST_QUEUE);

        assertNotNull(properties);
    }

    @Test
    void declaresDeadLetterQueue() {
        Properties properties = rabbitAdmin.getQueueProperties(QueueNames.DEAD_LETTER_QUEUE);

        assertNotNull(properties);
    }
}
