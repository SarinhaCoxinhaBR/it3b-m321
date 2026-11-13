package ch.benedict.m321.chatservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Legt beim Start alles an, was der Broker braucht.
 *
 * Spring meldet diese Beans beim Verbindungsaufbau an RabbitMQ. Existiert
 * eine Queue schon, passiert nichts — das Anlegen ist wiederholbar.
 */
@Configuration
public class RabbitConfig {

    // Scenario S6: a stuck instance's queue never holds more than 1000 messages.
    // RabbitMQ counts only ready messages against x-max-length, rabbitmqctl also shows
    // the unacknowledged ones. 950 ready plus a prefetch of 50 make at most 1000.
    static final int DELIVERY_QUEUE_MAX_LENGTH = 950;
    static final int DELIVERY_PREFETCH = 50;

    /**
     * Der Schreibweg. "durable" heisst: die Queue überlebt einen Neustart
     * des Brokers. Was der Verbraucher endgültig ablehnt, landet über den
     * Standard-Exchange ("") in der Dead-Letter-Queue.
     */
    @Bean
    public Queue persistQueue() {
        return QueueBuilder.durable(QueueNames.PERSIST_QUEUE)
                .deadLetterExchange("")
                .deadLetterRoutingKey(QueueNames.DEAD_LETTER_QUEUE)
                .build();
    }

    /** Das Abstellgleis für Nachrichten, die niemand verarbeiten konnte. */
    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(QueueNames.DEAD_LETTER_QUEUE).build();
    }

    /**
     * Der Zustellweg. Fanout heisst: JEDE gebundene Queue bekommt eine
     * Kopie. Das ist genau richtig, weil jede chat-service-Instanz jede
     * Nachricht sehen muss: nur sie weiss, welche ihrer offenen
     * Verbindungen im betroffenen Raum sitzen.
     */
    @Bean
    public FanoutExchange deliveryExchange() {
        return new FanoutExchange(QueueNames.DELIVERY_EXCHANGE, true, false);
    }

    /**
     * The delivery queue of this instance (scenarios S4 and S6).
     *
     * Exclusive: only the connection of this instance may use it, and RabbitMQ
     * deletes it as soon as that connection closes, for example when the
     * instance stops or misses its heartbeats. Not durable, because live
     * delivery has no value after a broker restart.
     *
     * x-max-length with drop-head: a stuck instance keeps at most 950 ready
     * messages, RabbitMQ throws away the oldest one for every new one.
     */
    @Bean
    public Queue deliveryQueue(@Value("${chat-service.instance-id}") String instanceId) {
        String queueName = QueueNames.DELIVERY_QUEUE_PREFIX + instanceId;
        return QueueBuilder.nonDurable(queueName)
                .exclusive()
                .withArgument("x-max-length", DELIVERY_QUEUE_MAX_LENGTH)
                .withArgument("x-overflow", "drop-head")
                .build();
    }

    /**
     * Binds the delivery queue of this instance to the fanout exchange, so it
     * gets a copy of every message, no matter which instance accepted it.
     */
    @Bean
    public Binding deliveryBinding(@Qualifier("deliveryQueue") Queue deliveryQueue,
                                   FanoutExchange deliveryExchange) {
        return BindingBuilder.bind(deliveryQueue).to(deliveryExchange);
    }

    /**
     * Nachrichten gehen als JSON über die Leitung, nicht als serialisiertes
     * Java-Objekt. Nur so kann später ein Dienst in einer anderen Sprache
     * mitlesen.
     *
     * Der ObjectMapper kommt von Spring Boot und kann bereits Instant im
     * ISO-8601-Format schreiben.
     */
    @Bean
    public MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}
