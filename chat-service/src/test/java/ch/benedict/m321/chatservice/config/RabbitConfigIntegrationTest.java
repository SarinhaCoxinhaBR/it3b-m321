package ch.benedict.m321.chatservice.config;

import ch.benedict.m321.chatservice.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Autowired
    @Qualifier("deliveryQueue")
    private Queue deliveryQueue;

    /** Die Queue des Schreibwegs existiert nach dem Start im Broker. */
    @Test
    void declaresPersistQueue() {
        Properties properties = rabbitAdmin.getQueueProperties(QueueNames.PERSIST_QUEUE);

        assertNotNull(properties);
    }

    /** Die Dead-Letter-Queue existiert nach dem Start im Broker. */
    @Test
    void declaresDeadLetterQueue() {
        Properties properties = rabbitAdmin.getQueueProperties(QueueNames.DEAD_LETTER_QUEUE);

        assertNotNull(properties);
    }

    /**
     * The delivery queue of this instance exists on the broker, belongs to this
     * instance alone (S4) and carries the limit for a stuck instance (S6).
     */
    @Test
    void declaresDeliveryQueueOfThisInstance() {
        String queueName = deliveryQueue.getName();
        Properties properties = rabbitAdmin.getQueueProperties(queueName);
        Map<String, Object> arguments = deliveryQueue.getArguments();

        assertNotNull(properties);
        assertTrue(queueName.startsWith(QueueNames.DELIVERY_QUEUE_PREFIX));
        assertTrue(deliveryQueue.isExclusive());
        assertFalse(deliveryQueue.isDurable());
        assertEquals(950, arguments.get("x-max-length"));
        assertEquals("drop-head", arguments.get("x-overflow"));
    }
}
