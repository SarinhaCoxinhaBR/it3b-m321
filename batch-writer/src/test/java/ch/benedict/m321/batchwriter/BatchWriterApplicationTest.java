package ch.benedict.m321.batchwriter;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Prüft, dass der Spring-Kontext mit echter Datenbank und echtem Broker
 * hochfährt. Scheitert schon das, ist jeder weitere Test wertlos.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class BatchWriterApplicationTest {

    /**
     * Kein Assert nötig. Fährt der Kontext nicht hoch, wirft Spring eine
     * Exception und der Test wird rot.
     */
    @Test
    void contextLoads() {
    }
}
