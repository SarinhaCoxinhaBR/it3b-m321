package ch.benedict.m321.chatservice;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A real RabbitMQ for the Spring tests of this module.
 *
 * Every test class imports exactly this configuration, so Spring builds one
 * context per test setup and shares it, together with its broker. Before,
 * every test class started its own RabbitMQ. The image is the same as in
 * docker-compose.yml.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    /**
     * RabbitMQ with the management plugin. ServiceConnection points host,
     * port and credentials of Spring AMQP to this container.
     */
    @Bean
    @ServiceConnection
    public RabbitMQContainer rabbitContainer() {
        DockerImageName image = DockerImageName.parse("rabbitmq:3.13-management");
        return new RabbitMQContainer(image);
    }
}
