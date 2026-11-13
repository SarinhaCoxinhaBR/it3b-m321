package ch.benedict.m321.chatservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Prüft, dass der Spring-Kontext überhaupt hochfährt.
 * Seit der Live-Zustellung braucht es dafür einen RabbitMQ: der Listener der
 * Zustell-Queue verbindet sich schon beim Start, nicht erst beim ersten Senden.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ChatServiceApplicationTest {

    /** Der Kontext fährt hoch, ohne dass eine Bean fehlt oder falsch konfiguriert ist. */
    @Test
    void contextLoads() {
        // Kein Assert nötig. Fährt der Kontext nicht hoch, wirft Spring
        // eine Exception und der Test wird rot.
    }
}
