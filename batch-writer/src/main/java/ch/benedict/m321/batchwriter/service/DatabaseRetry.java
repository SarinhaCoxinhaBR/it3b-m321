package ch.benedict.m321.batchwriter.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

/**
 * Wiederholt eine Datenbankarbeit, solange die Datenbank nicht erreichbar ist,
 * aber höchstens so oft wie konfiguriert.
 *
 * Ein Datenbankausfall ist kein Fehler der Nachricht (Szenario S7). Wer in
 * diesem Moment ablehnt, verliert Nachrichten oder füllt die Dead-Letter-Queue
 * mit einwandfreien Daten. Wer abstürzt, braucht einen Neustart. Darum wartet
 * der batch-writer hier, und die Nachrichten bleiben so lange unbestätigt und
 * damit sicher bei RabbitMQ.
 *
 * Nach dem letzten Versuch geht die Exception an den Aufrufer. Der Listener
 * gibt das Paket dann mit basicNack und Requeue an die Queue zurück
 * (PLANUNG.md 3.6). Verloren geht nichts, und läuft eine zweite Instanz,
 * kann sie das Paket übernehmen.
 */
@Slf4j
@Component
public class DatabaseRetry {

    private final long retryDelayMillis;
    private final int maxAttempts;

    /**
     * Pause und Anzahl Versuche kommen aus der Konfiguration, damit der
     * Unit-Test mit 10 ms statt mit 2 s laufen kann.
     */
    public DatabaseRetry(@Value("${batch-writer.retry-delay-ms}") long retryDelayMillis,
                         @Value("${batch-writer.max-attempts}") int maxAttempts) {
        this.retryDelayMillis = retryDelayMillis;
        this.maxAttempts = maxAttempts;
    }

    /**
     * Führt die Arbeit aus und wiederholt sie, bis sie gelingt oder die
     * Versuche aufgebraucht sind.
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
                // The limit: after the last attempt the caller decides what happens to the batch.
                if (attempt >= maxAttempts) {
                    log.warn("Database still not reachable after {} attempts, giving up: {}",
                            attempt, cause.toString());
                    throw exception;
                }
                log.warn("Database not reachable (attempt {} of {}), retrying in {} ms: {}",
                        attempt, maxAttempts, retryDelayMillis, cause.toString());
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
