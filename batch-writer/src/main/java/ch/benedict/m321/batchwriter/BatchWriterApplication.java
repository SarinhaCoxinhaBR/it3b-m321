package ch.benedict.m321.batchwriter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Startpunkt des batch-writer.
 *
 * Der Dienst hat keine Weboberfläche und keinen Port. Er lebt davon, dass
 * ein Listener an der Queue chat.persist hängt und die Nachrichten
 * gebündelt in PostgreSQL schreibt.
 */
@SpringBootApplication
public class BatchWriterApplication {

    /**
     * Startet Spring. Alles Weitere richten die Konfigurationsklassen ein:
     * Datenbank, Schema, Queues und den Listener.
     */
    public static void main(String[] args) {
        SpringApplication.run(BatchWriterApplication.class, args);
    }
}
