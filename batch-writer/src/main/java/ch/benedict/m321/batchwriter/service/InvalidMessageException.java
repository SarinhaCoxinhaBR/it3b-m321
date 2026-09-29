package ch.benedict.m321.batchwriter.service;

/**
 * Eine Nachricht aus der Queue lässt sich nicht lesen oder ist unvollständig.
 *
 * Bewusst eine geprüfte Exception: der Compiler zwingt den Aufrufer, den
 * Fall zu behandeln. Eine unlesbare Nachricht ist im Betrieb zu erwarten und
 * kein Programmierfehler.
 */
public class InvalidMessageException extends Exception {

    /**
     * Erzeugt die Exception mit einer Begründung, die im Log landet.
     */
    public InvalidMessageException(String reason) {
        super(reason);
    }

    /**
     * Erzeugt die Exception mit Begründung und dem Fehler, der dahintersteht,
     * zum Beispiel dem Fehler von Jackson bei kaputtem JSON.
     */
    public InvalidMessageException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
