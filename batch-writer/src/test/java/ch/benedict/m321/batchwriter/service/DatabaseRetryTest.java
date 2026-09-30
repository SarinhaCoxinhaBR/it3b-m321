package ch.benedict.m321.batchwriter.service;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.CannotCreateTransactionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Prüft die Regel «was zählt als Ausfall, was nicht» ohne Datenbank.
 * Die Pause ist 10 ms, damit der Test in Millisekunden durchläuft.
 */
class DatabaseRetryTest {

    private final DatabaseRetry databaseRetry = new DatabaseRetry(10);

    /** Zwei Verbindungsfehler, dann Erfolg: genau drei Versuche. */
    @Test
    void retriesUntilDatabaseAnswers() {
        DataAccessException connectionLost = new DataAccessResourceFailureException("Verbindung weg");
        ScriptedDatabaseWork work = new ScriptedDatabaseWork(2, connectionLost);

        databaseRetry.run(work);

        assertEquals(3, work.attempts);
    }

    /**
     * Kann Spring gar keine Transaktion öffnen, kommt eine
     * TransactionException statt einer DataAccessException. Auch sie ist
     * ein Ausfall und wird wiederholt.
     */
    @Test
    void retriesWhenNoTransactionCanBeOpened() {
        RuntimeException noTransaction = new CannotCreateTransactionException("Keine Verbindung");
        ScriptedDatabaseWork work = new ScriptedDatabaseWork(1, noTransaction);

        databaseRetry.run(work);

        assertEquals(2, work.attempts);
    }

    /**
     * Lehnt die Datenbank die Daten ab, bringt Warten nichts. Die Exception
     * geht nach genau einem Versuch an den Aufrufer.
     */
    @Test
    void passesRejectedDataThroughImmediately() {
        RuntimeException rejected = new DataIntegrityViolationException("Wert zu lang");
        ScriptedDatabaseWork work = new ScriptedDatabaseWork(1, rejected);

        assertThrows(DataIntegrityViolationException.class, () -> databaseRetry.run(work));
        assertEquals(1, work.attempts);
    }

    /**
     * Eine Datenbankarbeit, die zuerst eine festgelegte Anzahl Mal scheitert
     * und dann gelingt. Sie zählt mit, wie oft sie aufgerufen wurde.
     */
    private static class ScriptedDatabaseWork implements Runnable {

        private final int failuresBeforeSuccess;
        private final RuntimeException failure;
        private int attempts = 0;

        /** Legt fest, wie oft und womit die Arbeit scheitert. */
        ScriptedDatabaseWork(int failuresBeforeSuccess, RuntimeException failure) {
            this.failuresBeforeSuccess = failuresBeforeSuccess;
            this.failure = failure;
        }

        /** Scheitert, solange die festgelegte Anzahl nicht erreicht ist. */
        @Override
        public void run() {
            attempts = attempts + 1;
            if (attempts <= failuresBeforeSuccess) {
                throw failure;
            }
        }
    }
}
