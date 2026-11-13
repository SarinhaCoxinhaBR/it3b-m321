package ch.benedict.m321.chatservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Startpunkt des chat-service.
 *
 * Dieser Dienst ist die einzige Stelle im System, die eine Nachricht vom
 * Benutzer entgegennimmt. Er speichert nichts selbst — er reicht die
 * Nachricht an RabbitMQ weiter.
 */
@SpringBootApplication
// Runs the keep-alive in RoomSubscriptions every 15 seconds.
@EnableScheduling
public class ChatServiceApplication {

    /** Startet Spring. Alles Weitere richten die Konfigurationsklassen ein. */
    public static void main(String[] args) {
        SpringApplication.run(ChatServiceApplication.class, args);
    }
}
