package ch.benedict.m321.batchwriter.listener;

import ch.benedict.m321.batchwriter.config.QueueNames;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Werkzeugkasten für die Szenario-Tests: Nachrichten senden, Zeilen und
 * Queues zählen, auf ein Ergebnis warten.
 *
 * Gesendet wird wie in Szenario S5: das JSON des chat-service direkt in
 * chat.persist, nur mit dem Header content_type. So prüfen die Tests den
 * batch-writer allein, ohne dass der chat-service laufen muss.
 */
class ScenarioSupport {

    /** Der Aufbau einer Nachricht, genau wie der chat-service sie schreibt. */
    private static final String MESSAGE_TEMPLATE = """
            {"id":"%s","roomId":"3f2b1c4e-0000-0000-0000-000000000001",\
            "senderId":"test-user","senderName":"%s","content":"%s","sentAt":"%s"}""";

    /** So oft wird beim Warten nachgesehen. Seltener heisst weniger Transaktionen im Messfenster von S4. */
    private static final long POLL_INTERVAL_MILLIS = 500;

    private final JdbcTemplate jdbcTemplate;
    private final RabbitTemplate rabbitTemplate;
    private final AmqpAdmin amqpAdmin;

    /** Übernimmt die Werkzeuge, die der Test von Spring bekommen hat. */
    ScenarioSupport(JdbcTemplate jdbcTemplate, RabbitTemplate rabbitTemplate, AmqpAdmin amqpAdmin) {
        this.jdbcTemplate = jdbcTemplate;
        this.rabbitTemplate = rabbitTemplate;
        this.amqpAdmin = amqpAdmin;
    }

    /**
     * Leert Tabelle und Queues. Alle Tests teilen denselben Kontext und damit
     * dieselben Container, darum beginnt jeder Test mit einem sauberen Stand.
     */
    void reset() {
        jdbcTemplate.execute("TRUNCATE message");
        amqpAdmin.purgeQueue(QueueNames.PERSIST_QUEUE);
        amqpAdmin.purgeQueue(QueueNames.DEAD_LETTER_QUEUE);
    }

    /** Baut das JSON einer gültigen Nachricht mit frei gewählter id. */
    String messageJson(UUID id, String senderName, String content) {
        Instant sentAt = Instant.now();
        return MESSAGE_TEMPLATE.formatted(id, senderName, content, sentAt);
    }

    /** Legt beliebige Bytes als Nachricht in chat.persist, nur mit content_type. */
    void publishRaw(String body) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Message message = new Message(bytes, properties);
        rabbitTemplate.send(QueueNames.PERSIST_QUEUE, message);
    }

    /** Legt die angegebene Anzahl gültiger Nachrichten mit je neuer id in chat.persist. */
    void publishMessages(int count) {
        for (int i = 0; i < count; i++) {
            UUID id = UUID.randomUUID();
            String json = messageJson(id, "Test User", "Nachricht " + i);
            publishRaw(json);
        }
    }

    /** Zählt die Zeilen der Tabelle message. */
    int countRows() {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM message", Integer.class);
        return count;
    }

    /** Zählt die Zeilen mit dieser id. Mehr als 1 ist wegen des Primärschlüssels unmöglich. */
    int countRowsWithId(UUID id) {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM message WHERE id = ?", Integer.class, id);
        return count;
    }

    /** Wie viele Nachrichten bereitliegen, also noch nicht an einen Konsumenten ausgeliefert sind. */
    int messagesInQueue(String queueName) {
        QueueInformation information = amqpAdmin.getQueueInfo(queueName);
        return information.getMessageCount();
    }

    /** Wie viele Konsumenten an der Queue hängen. */
    int consumersOnQueue(String queueName) {
        QueueInformation information = amqpAdmin.getQueueInfo(queueName);
        return information.getConsumerCount();
    }

    /** Holt eine Nachricht aus der Dead-Letter-Queue und gibt ihren Body als Text zurück. */
    String receiveBodyFromDeadLetterQueue() {
        Message message = rabbitTemplate.receive(QueueNames.DEAD_LETTER_QUEUE, 5000);
        if (message == null) {
            return null;
        }
        byte[] body = message.getBody();
        return new String(body, StandardCharsets.UTF_8);
    }

    /**
     * Anzahl abgeschlossener Transaktionen dieser Datenbank seit ihrem Start.
     * Dieselbe Zahl misst die Lehrperson in Szenario S4.
     */
    long committedTransactions() {
        String sql = "SELECT xact_commit FROM pg_stat_database WHERE datname = current_database()";
        Long committed = jdbcTemplate.queryForObject(sql, Long.class);
        return committed;
    }

    /**
     * Wartet 11 s. PostgreSQL führt seine Statistik verzögert nach: eine
     * Verbindung, die nichts mehr tut, meldet ihre Zahlen spätestens nach
     * 10 s. Ohne diese Pause wären die letzten Transaktionen nicht mitgezählt.
     */
    void waitForStatistics() throws InterruptedException {
        Thread.sleep(11_000);
    }

    /**
     * Gibt Listener und Broker eine Sekunde, um die Antworten für ein schon
     * geschriebenes Paket zu schicken. Die Zeilen stehen vor dem ACK in der
     * Tabelle, darum kann ein Test sie sehen, bevor die Queues nachgezogen sind.
     */
    void waitForAcknowledgements() throws InterruptedException {
        Thread.sleep(1_000);
    }

    /** Wartet, bis mindestens so viele Zeilen in der Tabelle stehen. */
    void waitForRowCount(int expectedRows, Duration timeout) throws InterruptedException {
        waitUntil(() -> countRows() >= expectedRows, timeout, expectedRows + " rows in table message");
    }

    /**
     * Fragt die Bedingung alle 500 ms ab, bis sie stimmt. Stimmt sie nach
     * Ablauf der Zeit noch nicht, schlägt der Test mit einer klaren Meldung fehl.
     */
    void waitUntil(BooleanSupplier condition, Duration timeout, String description) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(POLL_INTERVAL_MILLIS);
        }
        throw new AssertionError("Not reached within " + timeout.toSeconds() + " s: " + description);
    }
}
