package ch.benedict.m321.batchwriter.dto;

/**
 * Eine gelesene Nachricht zusammen mit ihrer Zustellnummer.
 *
 * RabbitMQ nummeriert jede Zustellung auf einem Kanal. Nur mit dieser Nummer
 * kann der Listener nach dem Schreiben genau diese Nachricht bestätigen oder
 * ablehnen. Darum reisen Nummer und Inhalt zusammen durch den Dienst.
 *
 * @param deliveryTag die Zustellnummer von RabbitMQ
 * @param chatMessage der gelesene Inhalt
 */
public record ReceivedMessage(long deliveryTag, ChatMessage chatMessage) {
}
