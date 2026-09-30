package ch.benedict.m321.batchwriter.service;

import ch.benedict.m321.batchwriter.dto.ChatMessage;
import ch.benedict.m321.batchwriter.dto.ReceivedMessage;
import ch.benedict.m321.batchwriter.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Schreibt ein Paket in genau einer Transaktion.
 *
 * Das ist der Kern des Dienstes: 500 Nachrichten, ein INSERT-Batch, ein
 * COMMIT. Lehnt die Datenbank eine einzelne Zeile ab, schreibt diese Klasse
 * das Paket einmalig Zeile für Zeile. So landet nur die kaputte Nachricht in
 * der Dead-Letter-Queue und nicht die 499 anderen mit.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageBatchWriter {

    private final MessageRepository messageRepository;
    private final TransactionTemplate transactionTemplate;
    private final DatabaseRetry databaseRetry;

    /**
     * Schreibt alle Nachrichten des Pakets.
     *
     * Kehrt erst zurück, wenn alles geschrieben ist. Ist die Datenbank weg,
     * wartet DatabaseRetry so lange, bis sie wieder antwortet.
     *
     * @return die Nachrichten, die die Datenbank abgelehnt hat; im
     *         Normalfall eine leere Liste
     */
    public List<ReceivedMessage> write(List<ReceivedMessage> batch) {
        List<ChatMessage> chatMessages = new ArrayList<>();
        for (ReceivedMessage receivedMessage : batch) {
            ChatMessage chatMessage = receivedMessage.chatMessage();
            chatMessages.add(chatMessage);
        }

        try {
            databaseRetry.run(() -> insertInOneTransaction(chatMessages));
        } catch (DataIntegrityViolationException exception) {
            log.warn("Batch of {} messages rejected by the database, writing row by row", batch.size());
            return writeOneByOne(batch);
        }

        // Das Prüfskript sucht genau diesen Text im Log (Szenario S6).
        log.info("Batch of {} messages committed in one transaction", batch.size());
        return new ArrayList<>();
    }

    /**
     * Öffnet eine Transaktion, schreibt alle Nachrichten und macht COMMIT.
     * Wirft der Code darin eine Exception, rollt TransactionTemplate zurück:
     * dann steht keine einzige Zeile des Pakets in der Tabelle.
     */
    private void insertInOneTransaction(List<ChatMessage> chatMessages) {
        transactionTemplate.executeWithoutResult(status -> messageRepository.insertAll(chatMessages));
    }

    /**
     * Der Einzelweg nach einem abgelehnten Paket. Jede Nachricht bekommt ihre
     * eigene Transaktion, damit eine kaputte Zeile die anderen nicht mitreisst.
     * Das kostet bis zu 500 Transaktionen, kommt aber im Normalbetrieb nie vor.
     */
    private List<ReceivedMessage> writeOneByOne(List<ReceivedMessage> batch) {
        List<ReceivedMessage> rejectedMessages = new ArrayList<>();
        for (ReceivedMessage receivedMessage : batch) {
            ChatMessage chatMessage = receivedMessage.chatMessage();
            boolean stored = writeSingle(chatMessage);
            if (!stored) {
                rejectedMessages.add(receivedMessage);
            }
        }
        int storedCount = batch.size() - rejectedMessages.size();
        log.info("Row by row: {} messages stored, {} rejected", storedCount, rejectedMessages.size());
        return rejectedMessages;
    }

    /**
     * Schreibt eine einzelne Nachricht. Auch hier wartet DatabaseRetry, falls
     * die Datenbank genau in diesem Moment ausfällt.
     *
     * @return false, wenn die Datenbank genau diese Nachricht ablehnt
     */
    private boolean writeSingle(ChatMessage chatMessage) {
        List<ChatMessage> singleMessage = List.of(chatMessage);
        try {
            databaseRetry.run(() -> insertInOneTransaction(singleMessage));
            return true;
        } catch (DataIntegrityViolationException exception) {
            Throwable cause = exception.getMostSpecificCause();
            log.warn("Message {} rejected by the database: {}", chatMessage.id(), cause.getMessage());
            return false;
        }
    }
}
