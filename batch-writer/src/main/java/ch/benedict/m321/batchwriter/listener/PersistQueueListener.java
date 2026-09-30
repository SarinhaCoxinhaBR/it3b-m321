package ch.benedict.m321.batchwriter.listener;

import ch.benedict.m321.batchwriter.config.QueueNames;
import ch.benedict.m321.batchwriter.dto.ChatMessage;
import ch.benedict.m321.batchwriter.dto.ReceivedMessage;
import ch.benedict.m321.batchwriter.service.InvalidMessageException;
import ch.benedict.m321.batchwriter.service.MessageBatchWriter;
import ch.benedict.m321.batchwriter.service.MessageParser;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpIOException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareBatchMessageListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Nimmt ein Paket aus chat.persist entgegen, lässt es schreiben und
 * beantwortet danach jede Nachricht bei RabbitMQ.
 *
 * Die Regel dieser Klasse: jede Nachricht bekommt genau eine Antwort.
 * basicAck, wenn sie gespeichert ist. basicReject ohne Requeue, wenn sie nie
 * gespeichert werden kann; RabbitMQ legt sie dann in chat.dlq. basicNack mit
 * Requeue bei einem unerwarteten Fehler. Weil der Container im Modus MANUAL
 * läuft, bestätigt sonst niemand.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PersistQueueListener implements ChannelAwareBatchMessageListener {

    private final MessageParser messageParser;
    private final MessageBatchWriter messageBatchWriter;

    /**
     * Wird vom Container mit jedem fertigen Paket aufgerufen.
     *
     * Die Bestätigung kommt erst NACH dem Schreiben. Stürzt der Dienst
     * dazwischen ab, stellt RabbitMQ das Paket erneut zu, und ON CONFLICT
     * verwirft, was schon in der Tabelle steht (At-least-once).
     */
    @Override
    public void onMessageBatch(List<Message> messages, Channel channel) {
        List<ReceivedMessage> readableMessages = readAll(messages, channel);
        if (readableMessages.isEmpty()) {
            return;
        }

        List<ReceivedMessage> rejectedMessages;
        try {
            rejectedMessages = messageBatchWriter.write(readableMessages);
        } catch (RuntimeException exception) {
            log.error("Unexpected error while writing {} messages, returning them to the queue",
                    readableMessages.size(), exception);
            requeueAll(readableMessages, channel);
            return;
        }

        answerAll(readableMessages, rejectedMessages, channel);
    }

    /**
     * Liest jede Nachricht des Pakets. Eine unlesbare wird sofort abgelehnt
     * und blockiert die anderen nicht.
     *
     * @return die lesbaren Nachrichten mit ihrer Zustellnummer
     */
    private List<ReceivedMessage> readAll(List<Message> messages, Channel channel) {
        List<ReceivedMessage> readableMessages = new ArrayList<>();
        for (Message message : messages) {
            MessageProperties properties = message.getMessageProperties();
            long deliveryTag = properties.getDeliveryTag();
            try {
                ChatMessage chatMessage = messageParser.parse(message);
                ReceivedMessage receivedMessage = new ReceivedMessage(deliveryTag, chatMessage);
                readableMessages.add(receivedMessage);
            } catch (InvalidMessageException exception) {
                log.warn("Unreadable message moved to {}: {}", QueueNames.DEAD_LETTER_QUEUE, exception.getMessage());
                reject(deliveryTag, channel);
            }
        }
        return readableMessages;
    }

    /**
     * Beantwortet jede geschriebene Nachricht einzeln: gespeichert heisst
     * basicAck, von der Datenbank abgelehnt heisst basicReject.
     *
     * Einzeln und nicht mit einem Sammel-ACK (multiple=true), weil im selben
     * Paket abgelehnte Nachrichten stecken können. Ein Sammel-ACK über eine
     * schon abgelehnte Nummer beantwortet RabbitMQ mit einem Kanalfehler.
     */
    private void answerAll(List<ReceivedMessage> writtenMessages, List<ReceivedMessage> rejectedMessages,
                           Channel channel) {
        for (ReceivedMessage receivedMessage : writtenMessages) {
            long deliveryTag = receivedMessage.deliveryTag();
            if (rejectedMessages.contains(receivedMessage)) {
                reject(deliveryTag, channel);
            } else {
                acknowledge(deliveryTag, channel);
            }
        }
    }

    /**
     * Gibt alle Nachrichten an die Queue zurück. Nur für unerwartete Fehler:
     * ohne diese Antwort blieben sie unbestätigt liegen, bis der Prefetch
     * voll ist, und der Konsument stünde still.
     */
    private void requeueAll(List<ReceivedMessage> receivedMessages, Channel channel) {
        for (ReceivedMessage receivedMessage : receivedMessages) {
            long deliveryTag = receivedMessage.deliveryTag();
            try {
                channel.basicNack(deliveryTag, false, true);
            } catch (IOException exception) {
                throw new AmqpIOException(exception);
            }
        }
    }

    /**
     * Bestätigt eine Nachricht. Erst jetzt löscht RabbitMQ sie aus der Queue.
     */
    private void acknowledge(long deliveryTag, Channel channel) {
        try {
            channel.basicAck(deliveryTag, false);
        } catch (IOException exception) {
            throw new AmqpIOException(exception);
        }
    }

    /**
     * Lehnt eine Nachricht endgültig ab. requeue=false heisst: nicht zurück
     * in chat.persist, sondern über die Dead-Letter-Argumente der Queue nach
     * chat.dlq, unverändert und mit dem Header x-death.
     */
    private void reject(long deliveryTag, Channel channel) {
        try {
            channel.basicReject(deliveryTag, false);
        } catch (IOException exception) {
            throw new AmqpIOException(exception);
        }
    }
}
