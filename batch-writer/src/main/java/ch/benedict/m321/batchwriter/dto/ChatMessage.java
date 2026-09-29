package ch.benedict.m321.batchwriter.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Eine Chat-Nachricht, genau so, wie der chat-service sie in chat.persist legt.
 *
 * Das ist eine eigene Kopie, keine gemeinsame Klasse mit dem chat-service.
 * Der Vertrag zwischen den Diensten ist das JSON, nicht diese Datei
 * (docs/spec-batch-writer.md, Abschnitt 2).
 *
 * @param id         vom chat-service vergeben, Primärschlüssel der Tabelle
 * @param roomId     der Raum, in den die Nachricht gehört
 * @param senderId   die sub-Kennung des Absenders aus Keycloak
 * @param senderName der Anzeigename, damit der Verlauf lesbar bleibt
 * @param content    der Text der Nachricht
 * @param sentAt     der Zeitpunkt, zu dem der chat-service sie angenommen hat
 */
public record ChatMessage(
        UUID id,
        UUID roomId,
        String senderId,
        String senderName,
        String content,
        Instant sentAt) {
}
