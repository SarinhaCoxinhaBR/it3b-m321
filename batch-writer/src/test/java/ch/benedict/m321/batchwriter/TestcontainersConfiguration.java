package ch.benedict.m321.batchwriter;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Echte Container für alle Spring-Tests dieses Moduls.
 *
 * Weil jede Testklasse genau diese Konfiguration importiert, legt Spring
 * nur einen Kontext an und teilt ihn. Es laufen also während des ganzen
 * Testlaufs ein PostgreSQL und ein RabbitMQ, nicht zwei pro Testklasse.
 * Die Images sind dieselben wie in docker-compose.yml.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    /**
     * PostgreSQL im Container. ServiceConnection setzt URL, Benutzer und
     * Passwort der Datasource automatisch auf diesen Container.
     */
    @Bean
    @ServiceConnection
    public PostgreSQLContainer<?> postgresContainer() {
        DockerImageName image = DockerImageName.parse("postgres:16-alpine");
        return new PostgreSQLContainer<>(image);
    }

    /**
     * RabbitMQ im Container, mit Management-Plugin wie im Stack.
     * ServiceConnection setzt Host, Port und Zugangsdaten automatisch.
     */
    @Bean
    @ServiceConnection
    public RabbitMQContainer rabbitContainer() {
        DockerImageName image = DockerImageName.parse("rabbitmq:3.13-management");
        return new RabbitMQContainer(image);
    }
}
