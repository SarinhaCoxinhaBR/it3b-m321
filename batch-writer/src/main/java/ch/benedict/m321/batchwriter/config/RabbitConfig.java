package ch.benedict.m321.batchwriter.config;

import ch.benedict.m321.batchwriter.listener.PersistQueueListener;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Richtet beim Start die Queues und den Listener-Container ein.
 *
 * Hier stehen alle Zahlen, die das Bündeln ausmachen: Paketgrösse,
 * Zeitlimit, Prefetch und die Art der Bestätigung.
 */
@Configuration
public class RabbitConfig {

    /**
     * Die Queue, aus der gelesen wird, mit genau denselben Argumenten wie im
     * chat-service. Im frischen Stack kann der batch-writer vor der ersten
     * Nachricht starten, dann gäbe es die Queue sonst noch nicht.
     * Wichen die Argumente ab, lehnte RabbitMQ die zweite Deklaration ab:
     * ein abweichender Vertrag fällt also sofort beim Start auf.
     */
    @Bean
    public Queue persistQueue() {
        return QueueBuilder.durable(QueueNames.PERSIST_QUEUE)
                .deadLetterExchange("")
                .deadLetterRoutingKey(QueueNames.DEAD_LETTER_QUEUE)
                .build();
    }

    /**
     * Das Abstellgleis. Hierher legt RabbitMQ jede Nachricht, die der
     * batch-writer mit basicReject ohne Requeue ablehnt.
     */
    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(QueueNames.DEAD_LETTER_QUEUE).build();
    }

    /**
     * Der Container holt Nachrichten aus chat.persist und übergibt sie dem
     * Listener als Paket: 500 Stück oder nach 200 ms, was zuerst eintritt.
     *
     * Jede Einstellung ist in docs/spec-batch-writer.md 3.1 begründet.
     */
    @Bean
    public SimpleMessageListenerContainer persistQueueListenerContainer(
            ConnectionFactory connectionFactory,
            PersistQueueListener persistQueueListener,
            @Value("${batch-writer.batch-size}") int batchSize,
            @Value("${batch-writer.batch-timeout-ms}") long batchTimeoutMillis) {
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(connectionFactory);
        container.setQueueNames(QueueNames.PERSIST_QUEUE);
        container.setMessageListener(persistQueueListener);

        // MANUAL: der Container bestätigt nie selbst, auch nicht bei einer
        // Exception. Bestätigt wird im Listener, erst nach dem COMMIT.
        container.setAcknowledgeMode(AcknowledgeMode.MANUAL);

        // Der Listener bekommt eine Liste statt einzelner Nachrichten.
        container.setConsumerBatchEnabled(true);
        container.setBatchSize(batchSize);

        // Prefetch: so viele unbestätigte Nachrichten schickt RabbitMQ auf
        // Vorrat. Wäre er kleiner als die Paketgrösse, würde ein Paket nie
        // voll und jedes liefe ins Zeitlimit.
        container.setPrefetchCount(batchSize);

        // Das Zeitlimit: auch bei wenig Betrieb bleibt nichts liegen.
        container.setReceiveTimeout(batchTimeoutMillis);
        container.setBatchReceiveTimeout(batchTimeoutMillis);

        // Ein Konsument pro Instanz. Mehr Durchsatz gibt es mit
        // "docker compose up --scale batch-writer=2" (Szenario S6).
        container.setConcurrentConsumers(1);

        // Fehlt die Queue kurz, nicht abbrechen, sondern weiter versuchen.
        container.setMissingQueuesFatal(false);
        return container;
    }
}
