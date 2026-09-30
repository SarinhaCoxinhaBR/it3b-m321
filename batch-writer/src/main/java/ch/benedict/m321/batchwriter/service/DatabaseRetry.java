package ch.benedict.m321.batchwriter.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

/**
 * Wiederholt eine Datenbankarbeit, solange die Datenbank nicht erreichbar ist.
 *
 * Ein Datenbankausfall ist kein Fehler der Nachricht (Szenario S7). Wer in
 * diesem Moment ablehnt, verliert Nachrichten oder füllt die Dead-Letter-Queue
 * mit einwandfreien Daten. Wer abstürzt, braucht einen Neustart. Darum wartet
 * der batch-writer hier, und die Nachrichten bleiben so lange unbestätigt und
 * damit sicher bei RabbitMQ.
 */
@Slf4j
@Component
public class DatabaseRetry {

    private final long retryDelayMillis;

    /**
     * Die Pause kommt aus der Konfiguration, damit der Unit-Test mit 10 ms
     * statt mit 2 s laufen kann.
     */
    public DatabaseRetry(@Value("${batch-writer.retry-delay-ms}") long retryDelayMillis) {
        this.retryDelayMillis = retryDelayMillis;
    }

    /**
     * Führt die Arbeit aus und wiederholt sie, bis sie gelingt.
     *
     * Wiederholt wird bei jeder DataAccessException und TransactionException:
     * keine Verbindung, Verbindung abgebrochen, Commit gescheitert. Nicht
     * wiederholt wird die DataIntegrityViolationException. Sie bedeutet: die
     * Datenbank ist da, lehnt aber die Daten ab. Ein zweiter Versuch mit
     * denselben Daten ergäbe dasselbe, also geht sie sofort an den Aufrufer.
     */
    public void run(Runnable databaseWork) {
        int attempt = 1;
        while (true) {
            try {
                databaseWork.run();
                logRecoveryIfNeeded(attempt);
                return;
            } catch (DataIntegrityViolationException exception) {
                // Muss VOR DataAccessException stehen, weil sie davon erbt.
                throw exception;
            } catch (DataAccessException | TransactionException exception) {
                // Nur die innerste Ursache loggen, etwa "Connection refused".
                // Die äussere Meldung kann die Werte des INSERT enthalten,
                // also private Chat-Texte, und die gehören nicht ins Log.
                Throwable cause = exception.getMostSpecificCause();
                log.warn("Database not reachable (attempt {}), retrying in {} ms: {}",
                        attempt, retryDelayMillis, cause.toString());
                pause();
                attempt = attempt + 1;
            }
        }
    }

    /**
     * Meldet im Log, dass die Datenbank nach einem Ausfall wieder antwortet.
     * So sieht man im Log von S7 genau, wann es weiterging.
     */
    private void logRecoveryIfNeeded(int attempt) {
        if (attempt > 1) {
            log.info("Database reachable again after {} attempts", attempt);
        }
    }

    /**
     * Wartet die konfigurierte Zeit.
     *
     * Wird der Thread beim Warten unterbrochen, soll der Dienst herunterfahren.
     * Dann wird das Unterbrechungs-Flag wiederhergestellt und abgebrochen. Die
     * Nachrichten sind noch nicht bestätigt, RabbitMQ stellt sie neu zu.
     */
    private void pause() {
        try {
            Thread.sleep(retryDelayMillis);
        } catch (InterruptedException exception) {
            Thread currentThread = Thread.currentThread();
            currentThread.interrupt();
            throw new IllegalStateException("Interrupted while waiting for the database", exception);
        }
    }
}
