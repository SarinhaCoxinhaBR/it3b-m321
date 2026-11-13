package ch.benedict.m321.chatservice.controller;

import ch.benedict.m321.chatservice.ChatServiceApplication;
import ch.benedict.m321.chatservice.controller.SseTestClient.ReceivedEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Scenario S3: two chat-service instances on one RabbitMQ, like
 * "docker compose up --scale chat-service=2".
 *
 * The test starts the application twice in this JVM, each with its own port
 * and its own instance id, so each one gets its own delivery queue. A client
 * in room A hangs on each instance, a third client in room B. 200 messages
 * for room A go through each instance.
 */
@Testcontainers
class TwoInstancesIntegrationTest {

    private static final int MESSAGES_PER_INSTANCE = 200;
    private static final int ALL_MESSAGES = 2 * MESSAGES_PER_INSTANCE;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Container
    private static final RabbitMQContainer RABBIT_MQ =
            new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13-management"));

    private static ConfigurableApplicationContext instanceOne;
    private static ConfigurableApplicationContext instanceTwo;

    /** Starts both instances once the broker is running. */
    @BeforeAll
    static void startTwoInstances() {
        instanceOne = startInstance("instance-one");
        instanceTwo = startInstance("instance-two");
    }

    /** Stops both instances. Their exclusive queues disappear with their connections. */
    @AfterAll
    static void stopInstances() {
        instanceOne.close();
        instanceTwo.close();
    }

    /**
     * Both clients in room A get all 400 messages, each exactly once, and the
     * messages of each sender in their order. The client in room B gets none.
     */
    @Test
    void bothRoomAClientsGetEveryMessageOnceAndInOrder() throws Exception {
        String urlOne = baseUrl(instanceOne);
        String urlTwo = baseUrl(instanceTwo);
        UUID roomA = UUID.randomUUID();
        UUID roomB = UUID.randomUUID();

        try (SseTestClient clientOne = new SseTestClient(urlOne, roomA, null);
             SseTestClient clientTwo = new SseTestClient(urlTwo, roomA, null);
             SseTestClient clientInRoomB = new SseTestClient(urlOne, roomB, null)) {
            for (int i = 0; i < MESSAGES_PER_INSTANCE; i++) {
                SseTestClient.postMessage(urlOne, roomA, "sender-one", "one-" + i);
                SseTestClient.postMessage(urlTwo, roomA, "sender-two", "two-" + i);
            }
            clientOne.waitForEvents(ALL_MESSAGES, TIMEOUT);
            clientTwo.waitForEvents(ALL_MESSAGES, TIMEOUT);
            // A duplicate or a message for room B would arrive right behind the others.
            Thread.sleep(1000);

            assertEveryMessageOnceAndInOrder(clientOne);
            assertEveryMessageOnceAndInOrder(clientTwo);
            List<ReceivedEvent> eventsInRoomB = clientInRoomB.events();
            assertEquals(0, eventsInRoomB.size());
        }
    }

    /**
     * Starts the application with its own port and instance id, connected to
     * the test broker.
     */
    private static ConfigurableApplicationContext startInstance(String instanceId) {
        SpringApplication application = new SpringApplication(ChatServiceApplication.class);
        String[] arguments = {
                "--server.port=0",
                "--chat-service.instance-id=" + instanceId,
                "--spring.rabbitmq.host=" + RABBIT_MQ.getHost(),
                "--spring.rabbitmq.port=" + RABBIT_MQ.getAmqpPort(),
                "--spring.rabbitmq.username=" + RABBIT_MQ.getAdminUsername(),
                "--spring.rabbitmq.password=" + RABBIT_MQ.getAdminPassword()
        };
        return application.run(arguments);
    }

    /** The address of an instance, with the port the web server has picked. */
    private static String baseUrl(ConfigurableApplicationContext instance) {
        Environment environment = instance.getEnvironment();
        String port = environment.getProperty("local.server.port");
        return "http://localhost:" + port;
    }

    /**
     * Checks one client: 400 events, 400 different ids, and per sender the
     * contents 0 to 199 in exactly this order.
     */
    private void assertEveryMessageOnceAndInOrder(SseTestClient client) throws IOException {
        List<ReceivedEvent> events = client.events();
        Set<String> differentIds = new HashSet<>();
        List<String> fromSenderOne = new ArrayList<>();
        List<String> fromSenderTwo = new ArrayList<>();
        for (ReceivedEvent event : events) {
            differentIds.add(event.id());
            JsonNode message = OBJECT_MAPPER.readTree(event.data());
            JsonNode contentField = message.get("content");
            String content = contentField.asText();
            if (content.startsWith("one-")) {
                fromSenderOne.add(content);
            } else {
                fromSenderTwo.add(content);
            }
        }

        assertEquals(ALL_MESSAGES, events.size());
        assertEquals(ALL_MESSAGES, differentIds.size());
        assertEquals(expectedContents("one-"), fromSenderOne);
        assertEquals(expectedContents("two-"), fromSenderTwo);
    }

    /** The contents one sender has sent, in the order it sent them. */
    private List<String> expectedContents(String prefix) {
        List<String> contents = new ArrayList<>();
        for (int i = 0; i < MESSAGES_PER_INSTANCE; i++) {
            contents.add(prefix + i);
        }
        return contents;
    }
}
