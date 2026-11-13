package ch.benedict.m321.chatservice.config;

import ch.benedict.m321.chatservice.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Scenario S6 against a real RabbitMQ: while the instance does not read its
 * delivery queue, the queue keeps at most 950 ready messages. Together with
 * the prefetch of 50 that is never more than 1000.
 *
 * The test also proves the binding: the messages go to the fanout exchange,
 * and only the binding brings them into the queue of this instance.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DeliveryQueueLimitIntegrationTest {

    private static final int SENT_MESSAGES = 1100;
    private static final int POLL_INTERVAL_MILLIS = 200;
    private static final int MAX_POLLS = 50;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    @Qualifier("deliveryQueue")
    private Queue deliveryQueue;

    @Autowired
    private SimpleMessageListenerContainer deliveryListenerContainer;

    /**
     * Stops the listener like a paused instance, sends 1100 messages and checks
     * that exactly 950 are left. The listener runs again afterwards, because
     * the other tests share this context.
     */
    @Test
    void stuckInstanceKeepsAtMostTheLimit() throws InterruptedException {
        String queueName = deliveryQueue.getName();
        deliveryListenerContainer.stop();
        try {
            for (int i = 0; i < SENT_MESSAGES; i++) {
                rabbitTemplate.convertAndSend(QueueNames.DELIVERY_EXCHANGE, "", "Nachricht " + i);
            }

            int messageCount = waitForMessageCount(queueName, RabbitConfig.DELIVERY_QUEUE_MAX_LENGTH);

            assertEquals(RabbitConfig.DELIVERY_QUEUE_MAX_LENGTH, messageCount);
        } finally {
            rabbitAdmin.purgeQueue(queueName);
            deliveryListenerContainer.start();
        }
    }

    /**
     * Waits until the queue holds at least the expected number of messages, at
     * most ten seconds. Then waits one more second and reads again: a broken
     * limit would show more by then, because RabbitMQ is still taking messages.
     */
    private int waitForMessageCount(String queueName, int expectedCount) throws InterruptedException {
        int messageCount = 0;
        int polls = 0;
        while (messageCount < expectedCount && polls < MAX_POLLS) {
            Thread.sleep(POLL_INTERVAL_MILLIS);
            messageCount = readMessageCount(queueName);
            polls = polls + 1;
        }
        Thread.sleep(1000);
        return readMessageCount(queueName);
    }

    /** Ready messages in the queue, as RabbitMQ reports them. */
    private int readMessageCount(String queueName) {
        QueueInformation queueInformation = rabbitAdmin.getQueueInfo(queueName);
        return queueInformation.getMessageCount();
    }
}
