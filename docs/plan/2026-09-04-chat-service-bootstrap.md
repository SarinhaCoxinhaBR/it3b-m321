# Chat-Service Bootstrap — Implementierungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Ziel:** Ein lauffähiger `chat-service`, der über Swagger dokumentiert ist, den Nachrichtenverlauf
aus PostgreSQL liest und neue Nachrichten auf den RabbitMQ-Fanout-Exchange publiziert.

**Architektur:** Ein einzelner Spring-Boot-Dienst mit drei Schichten — Controller (HTTP und
Swagger-Doku), Service (Fachlogik) und Repository (SQL). Geschrieben wird in die
Nachrichtentabelle **nicht**: der `chat-service` publiziert nur, das Speichern übernimmt später der
`batch-service` (siehe `PLANUNG.md`, Abschnitt 2.3). PostgreSQL und RabbitMQ laufen in
`docker-compose`, der Dienst selbst zunächst aus der IDE heraus.

**Tech-Stack:** Java 21 · Spring Boot 3.5.16 · springdoc-openapi 2.9.0 (Swagger UI) ·
Spring JDBC (`JdbcTemplate`) · Spring AMQP · PostgreSQL 17 · RabbitMQ 4 · Maven

**Spec:** `PLANUNG.md` (Abschnitte 1, 2.1, 2.2, 2.3, 3) und `docs/design/2026-08-28-chat-app-architektur.html`

---

## Globale Vorgaben

Diese Punkte gelten für **jede** Aufgabe in diesem Plan.

| Vorgabe | Wert |
|---|---|
| Java | **21** — nicht ein neueres System-JDK; `java -version` muss 21 zeigen |
| Spring Boot | **3.5.16** (`spring-boot-starter-parent`) |
| springdoc-openapi | **2.9.0** (`springdoc-openapi-starter-webmvc-ui`) — diese Version wird gegen Boot 3.5.16 gebaut |
| Basis-Paket | `ch.benedict.m321.chat` |
| **Sprache im Code** | **Englisch.** Klassen, Methoden, Variablen, Felder, Testmethoden, Tabellen- und Spaltennamen, JSON-Felder, Query-Parameter — alles englisch. |
| **Sprache in Text** | **Deutsch.** Kommentare, Javadoc, Log-Ausgaben, Fehlermeldungen an den Client und alle Swagger-Beschreibungen. |
| Codestil | `CLAUDE.md` im Projektwurzelverzeichnis: eine Anweisung pro Zeile, keine Stream-Ketten, keine Annotation-Magie, kurze Methoden |
| Kein Lombok | Das Projekt hat Lombok nicht als Abhängigkeit und bekommt sie auch nicht — `CLAUDE.md` verbietet Annotation-Magie |
| Logging | Jede Aktion loggt: Mutationen auf `INFO`, Repository-Schritte auf `DEBUG`, abgelehnte Anfragen auf `WARN` |
| Abhängigkeiten | Nur was im Plan steht. Keine zusätzliche Bibliothek ohne Rückfrage |

> **Warum englischer Code bei deutschen Kommentaren.** Java, Spring und SQL bringen ihr eigenes
> englisches Vokabular mit (`get`, `find`, `Repository`, `SELECT`). Mischt man deutsche Bezeichner
> darunter, entstehen Wortungetüme wie `findeLetzteNachrichtenByRaumId`. Bezeichner folgen also der
> Sprache der Werkzeuge, die Erklärung folgt der Sprache des Unterrichts.

### Was dieser Plan **nicht** enthält

Bewusst ausgelagert, damit der Bootstrap klein und prüfbar bleibt:

- **Keycloak und Token-Prüfung.** Der Absender kommt vorerst aus dem Request-Body. Sobald das
  Token da ist, wird das Feld ersatzlos gestrichen. Bis dahin steht in der Swagger-Doku
  ausdrücklich «Platzhalter bis Keycloak».
- **SSE (`GET /stream`).** Braucht einen `@RabbitListener` und eine eigene Live-Queue — eigener Plan.
- **Raumverwaltung** (`POST /api/rooms`, Einladen, Mitgliedsprüfung). Die Tabellen werden hier schon
  angelegt, die Endpunkte kommen später.
- **`batch-service`, Gateway, React-App, JavaFX-Client.**

---

## Dateistruktur

```
it3b-m321/
├── .gitignore                                  neu
├── docker-compose.yml                          neu — PostgreSQL + RabbitMQ
├── db/
│   ├── 01-schema.sql                           neu — room, room_member, message
│   └── 02-demo-data.sql                        neu — ein Raum + drei Nachrichten zum Ausprobieren
└── chat-service/
    ├── pom.xml                                 neu
    └── src/
        ├── main/
        │   ├── java/ch/benedict/m321/chat/
        │   │   ├── ChatServiceApplication.java          Startpunkt
        │   │   ├── OpenApiConfiguration.java            Titel/Beschreibung der Swagger-Doku
        │   │   ├── rabbit/
        │   │   │   └── RabbitConfiguration.java         Exchange-Name, Exchange-Bean, JSON-Wandler
        │   │   └── message/
        │   │       ├── Message.java                     Datensatz einer gespeicherten Nachricht
        │   │       ├── NewMessage.java                  Datensatz für den Request-Body
        │   │       ├── MessageRepository.java           SQL zum Lesen des Verlaufs
        │   │       ├── MessageService.java              Fachlogik: publizieren und lesen
        │   │       └── MessageController.java           REST-Endpunkte + Swagger-Annotationen
        │   └── resources/
        │       └── application.yml
        └── test/java/ch/benedict/m321/chat/
            ├── ChatServiceApplicationTest.java          Kontext startet
            └── message/MessageControllerTest.java       Endpunkte, ohne DB und ohne Broker
```

**Warum diese Aufteilung:** ein Paket pro Fachthema (`message`, `rabbit`), nicht pro technischer
Schicht. Was zusammen geändert wird, liegt zusammen. Jede Datei hat genau eine Aufgabe, und keine
ist länger als etwa 80 Zeilen — so kann sie im Unterricht am Stück gelesen werden.

**Kein Eltern-POM, kein Multi-Modul-Maven.** Jeder Dienst bekommt später sein eigenes Verzeichnis
mit eigenem `pom.xml`. Das ist mehr Wiederholung, aber es zeigt genau das Modulthema: Dienste sind
unabhängig voneinander baubar und startbar.

---

## Task 1: Projekt anlegen — Spring Boot startet und Swagger UI ist erreichbar

**Dateien:**
- Erstellen: `.gitignore`
- Erstellen: `chat-service/pom.xml`
- Erstellen: `chat-service/src/main/java/ch/benedict/m321/chat/ChatServiceApplication.java`
- Erstellen: `chat-service/src/main/java/ch/benedict/m321/chat/OpenApiConfiguration.java`
- Erstellen: `chat-service/src/main/resources/application.yml`
- Test: `chat-service/src/test/java/ch/benedict/m321/chat/ChatServiceApplicationTest.java`

**Schnittstellen:**
- Liefert: die startfähige Anwendung auf Port `8080`, Swagger UI unter `/swagger-ui.html`,
  OpenAPI-JSON unter `/v3/api-docs`, Health-Endpunkt unter `/actuator/health`.

- [ ] **Schritt 1: Git-Repository anlegen**

Das Verzeichnis ist noch kein Git-Repository. Ohne Versionierung gibt es keine Commits und kein
Zurück.

```bash
cd it3b-m321
git init
```

Danach gleich die schon vorhandenen Dokumente sichern — Planung, Regeln und die Handskizze sind
bisher nirgends versioniert:

```bash
git add CLAUDE.md PLANUNG.md docs/
git commit -m "docs: Planung, Codestil-Regeln und Architekturskizze aufnehmen"
```

> `docs/design/mermaid.min.js` ist 3,5 MB gross. Die Datei liegt bewusst im Repo, damit das
> Architekturdokument auch ohne Internet rendert — auf Schul-Laptops ist das der Normalfall.

- [ ] **Schritt 2: `.gitignore` anlegen**

Datei `.gitignore` im Projektwurzelverzeichnis:

```gitignore
# Maven
target/

# IntelliJ
.idea/
*.iml
out/

# macOS
.DS_Store
```

- [ ] **Schritt 3: `chat-service/pom.xml` anlegen**

Nur Web, Swagger, Actuator und Test. Datenbank und Broker kommen in Task 2 dazu — so startet der
Dienst in diesem Schritt auch ohne laufendes Docker.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <!-- Der Spring-Boot-Eltern-POM legt die Versionen aller Spring-Bibliotheken fest.
         Deshalb steht bei den meisten Abhaengigkeiten unten keine Versionsnummer. -->
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.5.16</version>
        <relativePath/>
    </parent>

    <groupId>ch.benedict.m321</groupId>
    <artifactId>chat-service</artifactId>
    <version>0.1.0</version>
    <name>chat-service</name>
    <description>Nimmt Nachrichten entgegen und liest den Verlauf (Modul M321)</description>

    <properties>
        <java.version>21</java.version>
        <springdoc.version>2.9.0</springdoc.version>
    </properties>

    <dependencies>
        <!-- REST-Endpunkte und der eingebaute Webserver -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>

        <!-- Swagger UI: erzeugt die API-Dokumentation aus den Annotationen im Controller -->
        <dependency>
            <groupId>org.springdoc</groupId>
            <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
            <version>${springdoc.version}</version>
        </dependency>

        <!-- Liefert /actuator/health. Damit sehen wir spaeter, ob DB und Broker erreichbar sind. -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <!-- Baut ein ausfuehrbares JAR und erlaubt "mvn spring-boot:run" -->
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Schritt 4: `application.yml` anlegen**

Datei `chat-service/src/main/resources/application.yml`:

```yaml
server:
  port: 8080

spring:
  application:
    name: chat-service

# Swagger UI liegt unter http://localhost:8080/swagger-ui.html
springdoc:
  swagger-ui:
    path: /swagger-ui.html
    # Endpunkte nach Reihenfolge im Controller sortieren statt alphabetisch
    operations-sorter: method

management:
  endpoints:
    web:
      exposure:
        include: health
  endpoint:
    health:
      show-details: always

logging:
  level:
    # Im Unterricht wollen wir jeden Schritt sehen.
    ch.benedict.m321: DEBUG
```

- [ ] **Schritt 5: Den fehlschlagenden Test schreiben**

Datei `chat-service/src/test/java/ch/benedict/m321/chat/ChatServiceApplicationTest.java`:

```java
package ch.benedict.m321.chat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Prueft, dass Spring alle Klassen zusammenbauen kann. Der Test hat absichtlich keinen
 * Rumpf: faellt beim Hochfahren irgendwo eine Bean weg oder ist eine Konfiguration
 * fehlerhaft, schlaegt er hier fehl, bevor irgendjemand die Anwendung startet.
 */
@SpringBootTest
class ChatServiceApplicationTest {

    @Test
    void contextLoads() {
        // Kein Inhalt noetig - der Test besteht darin, dass @SpringBootTest oben durchlaeuft.
    }
}
```

- [ ] **Schritt 6: Test laufen lassen — er muss fehlschlagen**

```bash
cd it3b-m321/chat-service
export JAVA_HOME=<Pfad-zu-deinem-JDK-21>   # nur nötig, wenn java -version nicht 21 zeigt
mvn test
```

Erwartet: **FEHLSCHLAG.** Meldung sinngemäss `Unable to find a @SpringBootConfiguration` — es gibt
noch keine Klasse mit `@SpringBootApplication`.

- [ ] **Schritt 7: Die Startklasse schreiben**

Datei `chat-service/src/main/java/ch/benedict/m321/chat/ChatServiceApplication.java`:

```java
package ch.benedict.m321.chat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Startpunkt des chat-service. Spring Boot faehrt von hier aus den eingebauten Webserver
 * hoch und durchsucht dieses Paket samt Unterpaketen nach Klassen, die es verwalten soll
 * (Controller, Service, Repository, Konfigurationen).
 */
@SpringBootApplication
public class ChatServiceApplication {

    /**
     * Uebergibt die Startklasse an Spring Boot. Alles Weitere - Webserver, Beans,
     * Konfigurationsdateien - erledigt der Aufruf darunter.
     */
    public static void main(String[] args) {
        SpringApplication.run(ChatServiceApplication.class, args);
    }
}
```

- [ ] **Schritt 8: Test laufen lassen — er muss bestehen**

```bash
mvn test
```

Erwartet: **BESTANDEN.** `Tests run: 1, Failures: 0, Errors: 0`.

- [ ] **Schritt 9: Titel und Beschreibung der Swagger-Doku setzen**

Ohne diese Klasse heisst die Doku «OpenAPI definition» — nichtssagend. Datei
`chat-service/src/main/java/ch/benedict/m321/chat/OpenApiConfiguration.java`:

```java
package ch.benedict.m321.chat;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Setzt Titel, Version und Beschreibung der Swagger-Oberflaeche. Ohne diese Klasse
 * traegt die Dokumentation nur den Standardtitel "OpenAPI definition".
 */
@Configuration
public class OpenApiConfiguration {

    /**
     * Baut das Kopf-Objekt der API-Dokumentation. springdoc nimmt diese Bean und
     * ergaenzt sie um alles, was es in den Controllern findet.
     */
    @Bean
    public OpenAPI chatOpenApi() {
        Info info = new Info();
        info.setTitle("Chat-Service API");
        info.setVersion("0.1.0");
        info.setDescription(
                "REST-Schnittstelle des chat-service (Modul M321). "
                + "Neue Nachrichten werden entgegengenommen und an RabbitMQ weitergegeben. "
                + "Der Verlauf wird aus PostgreSQL gelesen. "
                + "Der chat-service schreibt selbst NICHT in die Nachrichtentabelle.");

        OpenAPI documentation = new OpenAPI();
        documentation.setInfo(info);
        return documentation;
    }
}
```

- [ ] **Schritt 10: Anwendung starten und Swagger UI im Browser prüfen**

```bash
mvn spring-boot:run
```

Dann im Browser öffnen: <http://localhost:8080/swagger-ui.html>

Erwartet: die Seite lädt und zeigt oben **«Chat-Service API 0.1.0»** mit der Beschreibung.
Endpunkte sind noch keine da — das ist richtig so.

Zweite Prüfung, im Terminal:

```bash
curl -s http://localhost:8080/v3/api-docs | head -c 200
curl -s http://localhost:8080/actuator/health
```

Erwartet: JSON, das mit `{"openapi":"3.1.0","info":{"title":"Chat-Service API"` beginnt, und
`{"status":"UP",...}`.

Anwendung mit `Ctrl+C` beenden.

- [ ] **Schritt 11: Commit**

```bash
cd it3b-m321
git add .gitignore chat-service/
git commit -m "feat(chat-service): Projekt aufsetzen, Swagger UI erreichbar"
```

---

## Task 2: Infrastruktur — PostgreSQL und RabbitMQ in docker-compose

**Dateien:**
- Erstellen: `docker-compose.yml`
- Erstellen: `db/01-schema.sql`
- Erstellen: `db/02-demo-data.sql`
- Ändern: `chat-service/pom.xml` (drei Abhängigkeiten ergänzen)
- Ändern: `chat-service/src/main/resources/application.yml` (Datenbank und Broker eintragen)

**Schnittstellen:**
- Braucht aus Task 1: `application.yml`, `pom.xml`
- Liefert: eine erreichbare Datenbank `chat` mit den Tabellen `room`, `room_member`, `message`
  samt Demo-Daten, und einen RabbitMQ-Broker. `/actuator/health` meldet beide als `UP`.

> **Achtung, bewusste Abweichung von der Vorgabe.** `PLANUNG.md` verlangt, dass nur Port 8080 nach
> aussen offen ist. In diesem Bootstrap läuft der `chat-service` aber noch auf dem Host (aus der
> IDE), nicht im Compose — er muss die Datenbank und den Broker also über `localhost` erreichen.
> Deshalb sind `5432`, `5672` und `15672` hier **veröffentlicht**. Sobald der `chat-service` selbst
> im Compose läuft, werden diese drei `ports:`-Einträge zu `expose:` und die Regel gilt wieder.
> Das ist im Plan festgehalten, damit es später nicht vergessen geht.

- [ ] **Schritt 1: `docker-compose.yml` anlegen**

Datei `docker-compose.yml` im Projektwurzelverzeichnis:

```yaml
# Infrastruktur fuer den Bootstrap: Datenbank und Message Broker.
# Der chat-service laeuft in dieser Phase noch auf dem Host (aus der IDE).
services:

  postgres:
    image: postgres:17-alpine
    container_name: m321-postgres
    environment:
      POSTGRES_DB: chat
      POSTGRES_USER: chat
      POSTGRES_PASSWORD: chat
    ports:
      # NUR fuer den Bootstrap veroeffentlicht - siehe Hinweis im Plan.
      - "5432:5432"
    volumes:
      # Alle .sql-Dateien hier drin fuehrt das Postgres-Image beim ERSTEN Start
      # in alphabetischer Reihenfolge aus. Danach nie wieder.
      - ./db:/docker-entrypoint-initdb.d:ro
      - postgres-data:/var/lib/postgresql/data
    networks:
      - chat-net

  rabbitmq:
    image: rabbitmq:4-management-alpine
    container_name: m321-rabbitmq
    environment:
      RABBITMQ_DEFAULT_USER: chat
      RABBITMQ_DEFAULT_PASS: chat
    ports:
      # NUR fuer den Bootstrap veroeffentlicht - siehe Hinweis im Plan.
      - "5672:5672"
      # Management-Oberflaeche: http://localhost:15672 (chat / chat)
      - "15672:15672"
    networks:
      - chat-net

volumes:
  postgres-data:

networks:
  chat-net:
    driver: bridge
```

- [ ] **Schritt 2: Schema anlegen**

Datei `db/01-schema.sql`:

```sql
-- Schema der Chat-App (Modul M321).
-- Wird vom Postgres-Image beim ERSTEN Start automatisch ausgefuehrt.

CREATE TABLE room (
    id         UUID         PRIMARY KEY,
    name       VARCHAR(100) NOT NULL,
    created_by VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL
);

-- Wer darf in welchem Raum mitlesen. Der Schluessel besteht aus beiden Spalten,
-- damit dieselbe Person nicht zweimal im selben Raum stehen kann.
CREATE TABLE room_member (
    room_id    UUID         NOT NULL REFERENCES room (id),
    username   VARCHAR(100) NOT NULL,
    invited_by VARCHAR(100) NOT NULL,
    joined_at  TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (room_id, username)
);

CREATE TABLE message (
    -- Die ID kommt vom chat-service, NICHT von der Datenbank. Nur so kann der
    -- batch-service ein Paket gefahrlos wiederholen (ON CONFLICT DO NOTHING).
    id       UUID         PRIMARY KEY,
    room_id  UUID         NOT NULL REFERENCES room (id),
    sender   VARCHAR(100) NOT NULL,
    text     TEXT         NOT NULL,
    -- Zeitpunkt des SENDENS, gesetzt vom chat-service. Absichtlich kein DEFAULT now():
    -- sonst haetten alle 500 Nachrichten eines Pakets dieselbe Zeit.
    sent_at  TIMESTAMPTZ  NOT NULL
);

-- Der Verlauf wird immer pro Raum und nach Zeit sortiert gelesen.
-- Genau dafuer ist dieser Index da.
CREATE INDEX idx_message_room_time ON message (room_id, sent_at DESC);
```

- [ ] **Schritt 3: Demo-Daten anlegen**

Ohne Daten liefert `GET /api/messages` eine leere Liste und man sieht nicht, ob es funktioniert.

Datei `db/02-demo-data.sql`:

```sql
-- Ein Raum und drei Nachrichten zum Ausprobieren.
-- Die feste UUID des Raums steht auch im Plan und in der Swagger-Doku als Beispiel.

INSERT INTO room (id, name, created_by, created_at) VALUES
  ('11111111-1111-1111-1111-111111111111', 'Allgemein', 'lehrperson', now());

INSERT INTO room_member (room_id, username, invited_by, joined_at) VALUES
  ('11111111-1111-1111-1111-111111111111', 'lehrperson', 'lehrperson', now()),
  ('11111111-1111-1111-1111-111111111111', 'lernende1',  'lehrperson', now());

INSERT INTO message (id, room_id, sender, text, sent_at) VALUES
  ('aaaaaaaa-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111',
   'lehrperson', 'Willkommen im Raum Allgemein.',     now() - interval '3 minutes'),
  ('aaaaaaaa-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111',
   'lernende1',  'Danke, der Verlauf wird gelesen.',  now() - interval '2 minutes'),
  ('aaaaaaaa-0000-0000-0000-000000000003', '11111111-1111-1111-1111-111111111111',
   'lehrperson', 'Genau, geschrieben wird spaeter.',  now() - interval '1 minute');
```

- [ ] **Schritt 4: Compose starten und Schema prüfen**

```bash
cd it3b-m321
docker compose up -d
docker compose ps
docker exec -it m321-postgres psql -U chat -d chat -c "\dt"
docker exec -it m321-postgres psql -U chat -d chat -c "SELECT sender, text FROM message ORDER BY sent_at;"
```

Erwartet: beide Container laufen, `\dt` zeigt `message`, `room`, `room_member`, und die drei
Demo-Nachrichten in der richtigen Reihenfolge.

Ausserdem <http://localhost:15672> öffnen (Anmeldung `chat` / `chat`) — die RabbitMQ-Oberfläche
muss erscheinen. Sie wird in Task 4 gebraucht.

> **Wenn das Schema später geändert wird:** die Skripte in `docker-entrypoint-initdb.d` laufen nur
> beim allerersten Start auf ein leeres Volume. Danach hilft nur
> `docker compose down -v && docker compose up -d`. Das löscht alle Daten — im Unterricht genau
> richtig, in Produktion nimmt man dafür Flyway.

- [ ] **Schritt 5: Abhängigkeiten in `pom.xml` ergänzen**

In `chat-service/pom.xml` **vor** `spring-boot-starter-test` einfügen:

```xml
        <!-- JdbcTemplate: SQL direkt, ohne ORM. Wir sehen jede Abfrage im Klartext. -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-jdbc</artifactId>
        </dependency>

        <!-- Treiber fuer PostgreSQL. Wird nur zur Laufzeit gebraucht, nicht beim Kompilieren. -->
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>

        <!-- Spring AMQP: die Anbindung an RabbitMQ (Exchange, Queue, publish, ACK). -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-amqp</artifactId>
        </dependency>
```

- [ ] **Schritt 6: Datenbank und Broker in `application.yml` eintragen**

In `chat-service/src/main/resources/application.yml` den Block unter `spring:` erweitern:

```yaml
spring:
  application:
    name: chat-service

  # Verbindung zur Datenbank aus docker-compose.
  # Zugangsdaten stehen hier im Klartext, weil das eine Uebungsumgebung ist.
  datasource:
    url: jdbc:postgresql://localhost:5432/chat
    username: chat
    password: chat

  rabbitmq:
    host: localhost
    port: 5672
    username: chat
    password: chat
```

- [ ] **Schritt 7: Test laufen lassen — der Kontext muss weiterhin starten**

```bash
cd chat-service
export JAVA_HOME=<Pfad-zu-deinem-JDK-21>   # nur nötig, wenn java -version nicht 21 zeigt
mvn test
```

Erwartet: **BESTANDEN.** Ab jetzt braucht dieser Test ein laufendes `docker compose up -d` — ohne
Datenbank kommt Spring beim Hochfahren nicht durch. Das ist gewollt und ehrlich: der Test prüft
genau das Zusammenspiel.

- [ ] **Schritt 8: Health-Endpunkt prüfen**

```bash
mvn spring-boot:run
```

In einem zweiten Terminal:

```bash
curl -s http://localhost:8080/actuator/health
```

Erwartet: `"status":"UP"` und darin die Einträge `"db"` mit `"status":"UP"` sowie `"rabbit"` mit
`"status":"UP"`. Das ist der Beweis, dass beide Verbindungen wirklich stehen — nicht nur, dass die
Anwendung gestartet ist.

Anwendung mit `Ctrl+C` beenden.

- [ ] **Schritt 9: Commit**

```bash
cd it3b-m321
git add docker-compose.yml db/ chat-service/
git commit -m "feat(infra): PostgreSQL und RabbitMQ in docker-compose, Schema und Demo-Daten"
```

---

## Task 3: Verlauf lesen — `GET /api/messages`

**Dateien:**
- Erstellen: `chat-service/src/main/java/ch/benedict/m321/chat/message/Message.java`
- Erstellen: `chat-service/src/main/java/ch/benedict/m321/chat/message/MessageRepository.java`
- Erstellen: `chat-service/src/main/java/ch/benedict/m321/chat/message/MessageService.java`
- Erstellen: `chat-service/src/main/java/ch/benedict/m321/chat/message/MessageController.java`
- Test: `chat-service/src/test/java/ch/benedict/m321/chat/message/MessageControllerTest.java`

**Schnittstellen:**
- Braucht aus Task 2: Tabelle `message`, Demo-Daten, `JdbcTemplate` aus dem Starter.
- Liefert für Task 4:
  - `record Message(UUID id, UUID roomId, String sender, String text, Instant sentAt)`
  - `MessageService` als Spring-Bean mit
    `List<Message> loadHistory(UUID roomId, int limit)`
  - `MessageController` mit dem Pfadpräfix `/api/messages`

- [ ] **Schritt 1: Den fehlschlagenden Test schreiben**

Der Test lädt **nur** die Webschicht und ersetzt den Service durch eine Attrappe. Er braucht damit
weder Datenbank noch Broker und läuft überall.

Datei `chat-service/src/test/java/ch/benedict/m321/chat/message/MessageControllerTest.java`:

```java
package ch.benedict.m321.chat.message;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Testet den Controller allein. @WebMvcTest startet nur die Webschicht, nicht die
 * ganze Anwendung - deshalb braucht dieser Test weder Datenbank noch RabbitMQ.
 * Der Service wird durch eine Attrappe (@MockitoBean) ersetzt, die wir steuern.
 */
@WebMvcTest(MessageController.class)
class MessageControllerTest {

    private static final UUID ROOM_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MessageService messageService;

    @Test
    void historyReturnsMessagesAsJson() throws Exception {
        // Die Attrappe soll genau eine Nachricht zurueckgeben.
        Message example = new Message(
                UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001"),
                ROOM_ID,
                "lehrperson",
                "Willkommen im Raum Allgemein.",
                Instant.parse("2026-09-04T08:00:00Z"));
        when(messageService.loadHistory(any(), anyInt())).thenReturn(List.of(example));

        mockMvc.perform(get("/api/messages").param("roomId", ROOM_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sender").value("lehrperson"))
                .andExpect(jsonPath("$[0].text").value("Willkommen im Raum Allgemein."));
    }
}
```

- [ ] **Schritt 2: Test laufen lassen — er muss fehlschlagen**

```bash
cd chat-service
export JAVA_HOME=<Pfad-zu-deinem-JDK-21>   # nur nötig, wenn java -version nicht 21 zeigt
mvn test
```

Erwartet: **FEHLSCHLAG beim Kompilieren** — `MessageController`, `MessageService` und
`Message` gibt es noch nicht.

- [ ] **Schritt 3: Den Datensatz `Message` schreiben**

Datei `chat-service/src/main/java/ch/benedict/m321/chat/message/Message.java`:

```java
package ch.benedict.m321.chat.message;

import java.time.Instant;
import java.util.UUID;

/**
 * Eine Nachricht, so wie sie in der Datenbank steht und wie sie ueber die API
 * herausgeht. Ein "record" ist eine Kurzform fuer eine Klasse, die nur Daten haelt:
 * Java erzeugt Konstruktor, Lesemethoden, equals und toString selbst.
 * Die Felder sind unveraenderlich - einmal gesetzt, bleibt eine Nachricht, wie sie ist.
 */
public record Message(
        UUID id,
        UUID roomId,
        String sender,
        String text,
        Instant sentAt) {
}
```

- [ ] **Schritt 4: Das Repository schreiben**

Datei `chat-service/src/main/java/ch/benedict/m321/chat/message/MessageRepository.java`:

```java
package ch.benedict.m321.chat.message;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Liest Nachrichten aus der Datenbank. Bewusst ohne ORM: das SQL steht im Klartext
 * da und jede Spalte wird von Hand in ein Feld uebertragen - man kann es Zeile fuer
 * Zeile vorlesen.
 *
 * Schreiben gibt es hier absichtlich nicht. In die Nachrichtentabelle schreibt
 * ausschliesslich der batch-service (siehe PLANUNG.md, Abschnitt 2.3).
 */
@Repository
public class MessageRepository {

    private static final Logger log = LoggerFactory.getLogger(MessageRepository.class);

    private final JdbcTemplate jdbcTemplate;

    /**
     * Spring reicht den JdbcTemplate hier herein (Konstruktor-Injektion). Wir bauen
     * ihn nicht selbst - dann koennte man ihn im Test nicht austauschen.
     */
    public MessageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Holt die letzten Nachrichten eines Raums, neueste zuerst.
     * Der Index aus 01-schema.sql passt genau auf diese Abfrage.
     */
    public List<Message> findLatest(UUID roomId, int limit) {
        String sql = "SELECT id, room_id, sender, text, sent_at "
                   + "FROM message "
                   + "WHERE room_id = ? "
                   + "ORDER BY sent_at DESC "
                   + "LIMIT ?";

        log.debug("Lese die letzten {} Nachrichten aus Raum {}", limit, roomId);

        // Die Fragezeichen werden vom Treiber gefuellt. Niemals Werte in den
        // SQL-String kleben - das waere eine Einladung fuer SQL-Injection.
        List<Message> found = jdbcTemplate.query(sql, this::mapRow, roomId, limit);

        log.debug("{} Nachrichten aus Raum {} gelesen", found.size(), roomId);
        return found;
    }

    /**
     * Uebertraegt eine Ergebniszeile der Datenbank in ein Message-Objekt.
     * Diese Methode wird oben pro gefundener Zeile einmal aufgerufen.
     */
    private Message mapRow(ResultSet row, int rowNumber) throws SQLException {
        UUID id = row.getObject("id", UUID.class);
        UUID roomId = row.getObject("room_id", UUID.class);
        String sender = row.getString("sender");
        String text = row.getString("text");
        Timestamp timestamp = row.getTimestamp("sent_at");
        Instant sentAt = timestamp.toInstant();
        return new Message(id, roomId, sender, text, sentAt);
    }
}
```

- [ ] **Schritt 5: Den Service schreiben (vorerst nur Lesen)**

Datei `chat-service/src/main/java/ch/benedict/m321/chat/message/MessageService.java`:

```java
package ch.benedict.m321.chat.message;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Fachlogik rund um Nachrichten. Der Controller kennt nur diese Klasse, nicht das
 * Repository und nicht RabbitMQ - so bleibt die Weboberflaeche von der Technik
 * dahinter getrennt.
 */
@Service
public class MessageService {

    private static final Logger log = LoggerFactory.getLogger(MessageService.class);

    private final MessageRepository messageRepository;

    public MessageService(MessageRepository messageRepository) {
        this.messageRepository = messageRepository;
    }

    /**
     * Liefert den Verlauf eines Raums. Reines Lesen - hier wird nichts veraendert.
     */
    public List<Message> loadHistory(UUID roomId, int limit) {
        log.debug("Verlauf angefordert: Raum {}, hoechstens {} Nachrichten", roomId, limit);
        return messageRepository.findLatest(roomId, limit);
    }
}
```

- [ ] **Schritt 6: Den Controller schreiben**

Datei `chat-service/src/main/java/ch/benedict/m321/chat/message/MessageController.java`:

```java
package ch.benedict.m321.chat.message;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Die REST-Schnittstelle fuer Nachrichten. Die Annotationen aus io.swagger.v3
 * beschreiben jeden Endpunkt - daraus baut springdoc die Swagger-Oberflaeche.
 * Was hier nicht beschrieben ist, taucht in der Dokumentation auch nicht auf.
 */
@RestController
@RequestMapping("/api/messages")
@Tag(name = "Nachrichten", description = "Nachrichten senden und den Verlauf eines Raums lesen")
public class MessageController {

    private static final Logger log = LoggerFactory.getLogger(MessageController.class);

    /** Obergrenze fuer "limit". Schuetzt die Datenbank vor einer Abfrage ueber Millionen Zeilen. */
    private static final int MAX_LIMIT = 100;

    private final MessageService messageService;

    public MessageController(MessageService messageService) {
        this.messageService = messageService;
    }

    /**
     * Liefert die letzten Nachrichten eines Raums, neueste zuerst.
     * Gelesen wird direkt aus der Datenbank - dieser Weg laeuft voellig getrennt
     * vom Senden ueber RabbitMQ.
     */
    @Operation(
            summary = "Verlauf eines Raums lesen",
            description = "Gibt die letzten Nachrichten eines Raums zurueck, neueste zuerst. "
                        + "Demo-Raum zum Ausprobieren: 11111111-1111-1111-1111-111111111111")
    @ApiResponse(responseCode = "200", description = "Verlauf, moeglicherweise leer")
    @ApiResponse(responseCode = "400", description = "limit ist kleiner als 1 oder groesser als 100")
    @GetMapping
    public List<Message> history(
            @Parameter(description = "ID des Raums", required = true,
                       example = "11111111-1111-1111-1111-111111111111")
            @RequestParam UUID roomId,

            @Parameter(description = "Wie viele Nachrichten hoechstens (1 bis 100)", example = "50")
            @RequestParam(defaultValue = "50") int limit) {

        log.info("Verlauf abgerufen: Raum {}, limit {}", roomId, limit);

        // Grenzen pruefen, bevor die Zahl in die SQL-Abfrage geht.
        if (limit < 1 || limit > MAX_LIMIT) {
            log.warn("Ungueltiges limit {} fuer Raum {} - Anfrage abgelehnt", limit, roomId);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "limit muss zwischen 1 und " + MAX_LIMIT + " liegen");
        }

        List<Message> history = messageService.loadHistory(roomId, limit);
        log.info("Verlauf geliefert: Raum {}, {} Nachrichten", roomId, history.size());
        return history;
    }
}
```

- [ ] **Schritt 7: Test laufen lassen — er muss bestehen**

```bash
mvn test
```

Erwartet: **BESTANDEN.** `Tests run: 2, Failures: 0, Errors: 0` (der Kontext-Test aus Task 1 und
der neue Controller-Test).

> **Docker muss laufen.** Seit Task 2 fährt `ChatServiceApplicationTest` die ganze
> Anwendung hoch und braucht dafür Datenbank und Broker. Vorher im
> Projektwurzelverzeichnis `docker compose up -d` ausführen.

- [ ] **Schritt 8: Von Hand in Swagger UI prüfen**

```bash
docker compose up -d      # falls noch nicht laufend, vom Projektwurzelverzeichnis aus
cd chat-service && mvn spring-boot:run
```

<http://localhost:8080/swagger-ui.html> öffnen. Erwartet:
- Es gibt jetzt den Bereich **«Nachrichten»** mit einem Endpunkt `GET /api/messages`.
- «Try it out» → `roomId` = `11111111-1111-1111-1111-111111111111` → «Execute».
- Antwort `200` mit den **drei** Demo-Nachrichten, neueste zuerst.
- Zweiter Versuch mit `limit` = `0` → Antwort `400`.

In der Konsole müssen dabei die Log-Zeilen `Verlauf abgerufen`, `Lese die letzten …` und
`Verlauf geliefert` erscheinen. Das ist der Beweis, dass alle drei Schichten durchlaufen wurden.

- [ ] **Schritt 9: Commit**

```bash
cd it3b-m321
git add chat-service/
git commit -m "feat(chat-service): GET /api/messages liest den Verlauf, dokumentiert in Swagger"
```

---

## Task 4: Nachricht senden — `POST /api/messages` publiziert auf den Fanout-Exchange

**Dateien:**
- Erstellen: `chat-service/src/main/java/ch/benedict/m321/chat/rabbit/RabbitConfiguration.java`
- Erstellen: `chat-service/src/main/java/ch/benedict/m321/chat/message/NewMessage.java`
- Ändern: `chat-service/src/main/java/ch/benedict/m321/chat/message/MessageService.java`
- Ändern: `chat-service/src/main/java/ch/benedict/m321/chat/message/MessageController.java`
- Test: `chat-service/src/test/java/ch/benedict/m321/chat/message/MessageControllerTest.java` (ergänzen)

**Schnittstellen:**
- Braucht aus Task 3: `Message`, `MessageService`, `MessageController`
- Liefert für spätere Pläne:
  - `RabbitConfiguration.EXCHANGE_NAME` = `"chat.messages"`
  - `record NewMessage(UUID roomId, String sender, String text)`
  - `Message MessageService.sendMessage(NewMessage incoming)`

- [ ] **Schritt 1: Die fehlschlagenden Tests schreiben**

In `MessageControllerTest` **ergänzen**. Zuerst diese beiden Importe zu den bestehenden dazunehmen
(als echte `import`-Zeilen, nicht als Kommentar):

```java
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
```

`any` ist bereits importiert. Dann die beiden Testmethoden in die Klasse einfügen:

```java
    @Test
    void sendAcceptsMessageAndReturns202() throws Exception {
        Message created = new Message(
                UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001"),
                ROOM_ID,
                "lernende1",
                "Hallo zusammen",
                Instant.parse("2026-09-04T08:05:00Z"));
        when(messageService.sendMessage(any())).thenReturn(created);

        String body = """
                {
                  "roomId": "11111111-1111-1111-1111-111111111111",
                  "sender": "lernende1",
                  "text": "Hallo zusammen"
                }
                """;

        // 202 Accepted heisst: angenommen und weitergegeben - aber noch nicht gespeichert.
        // Genau das ist bei uns der Fall, denn schreiben wird spaeter der batch-service.
        mockMvc.perform(post("/api/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value("bbbbbbbb-0000-0000-0000-000000000001"))
                .andExpect(jsonPath("$.text").value("Hallo zusammen"));
    }

    @Test
    void sendRejectsBlankText() throws Exception {
        String body = """
                {
                  "roomId": "11111111-1111-1111-1111-111111111111",
                  "sender": "lernende1",
                  "text": "   "
                }
                """;

        mockMvc.perform(post("/api/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }
```

- [ ] **Schritt 2: Tests laufen lassen — sie müssen fehlschlagen**

```bash
cd chat-service
export JAVA_HOME=<Pfad-zu-deinem-JDK-21>   # nur nötig, wenn java -version nicht 21 zeigt
mvn test
```

Erwartet: **FEHLSCHLAG beim Kompilieren** — `sendMessage` gibt es am Service noch nicht.

- [ ] **Schritt 3: Die RabbitMQ-Konfiguration schreiben**

Datei `chat-service/src/main/java/ch/benedict/m321/chat/rabbit/RabbitConfiguration.java`:

```java
package ch.benedict.m321.chat.rabbit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Legt fest, wie der chat-service mit RabbitMQ spricht.
 *
 * Ein Fanout-Exchange verteilt jede Nachricht an ALLE Queues, die an ihm haengen -
 * ohne auf einen Schluessel zu schauen. Genau das brauchen wir: eine Kopie fuer
 * jede chat-service-Instanz (Anzeige) und eine fuer den batch-service (Speichern).
 */
@Configuration
public class RabbitConfiguration {

    /** Name des Exchange, auf den jede neue Nachricht publiziert wird. */
    public static final String EXCHANGE_NAME = "chat.messages";

    /**
     * Meldet den Exchange beim Broker an. Spring legt ihn beim Start automatisch
     * an, falls es ihn noch nicht gibt - man muss in der Management-UI nichts klicken.
     *
     * "durable" heisst: der Exchange ueberlebt einen Neustart des Brokers.
     * "autoDelete = false" heisst: er verschwindet nicht, wenn gerade keine Queue dranhaengt.
     */
    @Bean
    public FanoutExchange chatExchange() {
        return new FanoutExchange(EXCHANGE_NAME, true, false);
    }

    /**
     * Wandelt Nachrichten beim Senden in JSON um. Ohne diese Bean wuerde Spring die
     * Objekte in ein Java-eigenes Binaerformat serialisieren - in der Management-UI
     * waere dann nur Zeichensalat zu sehen.
     *
     * Wir reichen absichtlich den ObjectMapper von Spring Boot herein: der ist so
     * eingestellt, dass Zeitpunkte als lesbares "2026-09-04T08:05:00Z" geschrieben
     * werden und nicht als blosse Zahl.
     */
    @Bean
    public Jackson2JsonMessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}
```

- [ ] **Schritt 4: Den Request-Datensatz schreiben**

Datei `chat-service/src/main/java/ch/benedict/m321/chat/message/NewMessage.java`:

```java
package ch.benedict.m321.chat.message;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Was der Client beim Senden mitschickt. Bewusst NICHT dasselbe wie Message:
 * id und sentAt vergibt der Server, nicht der Client. Wuerde der Client sie
 * mitschicken duerfen, koennte er sich eine fremde Uhrzeit oder eine fremde ID aussuchen.
 */
public record NewMessage(

        @Schema(description = "In welchen Raum die Nachricht gehoert",
                example = "11111111-1111-1111-1111-111111111111")
        UUID roomId,

        @Schema(description = "PLATZHALTER bis Keycloak da ist. Danach kommt der Absender "
                            + "aus dem Token und dieses Feld faellt ersatzlos weg.",
                example = "lernende1")
        String sender,

        @Schema(description = "Der Nachrichtentext", example = "Hallo zusammen")
        String text) {
}
```

- [ ] **Schritt 5: Den Service um das Senden erweitern**

In `MessageService.java`: die Importe ergänzen, dann den Feldblock und den Konstruktor durch die
folgende Fassung **ersetzen** und die neue Methode anfügen.

Zusätzliche Importe:

```java
import java.time.Instant;

import ch.benedict.m321.chat.rabbit.RabbitConfiguration;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
```

Felder und Konstruktor (ersetzen die bisherige Fassung):

```java
    private final RabbitTemplate rabbitTemplate;
    private final MessageRepository messageRepository;

    public MessageService(RabbitTemplate rabbitTemplate, MessageRepository messageRepository) {
        this.rabbitTemplate = rabbitTemplate;
        this.messageRepository = messageRepository;
    }
```

Neue Methode (anfügen):

```java
    /**
     * Nimmt eine neue Nachricht an und gibt sie an RabbitMQ weiter.
     *
     * Wichtig: hier wird NICHT in die Datenbank geschrieben. Der chat-service
     * publiziert nur; gespeichert wird spaeter gebuendelt vom batch-service
     * (PLANUNG.md, Abschnitt 2.3).
     */
    public Message sendMessage(NewMessage incoming) {
        // Die ID vergeben WIR, nicht die Datenbank. Nur so kann der batch-service
        // ein Paket gefahrlos wiederholen, ohne Dubletten zu erzeugen.
        UUID id = UUID.randomUUID();

        // Auch die Zeit setzen wir hier: das ist der Moment des SENDENS.
        // Die Datenbank wuerde spaeter den Moment des SCHREIBENS festhalten.
        Instant sentAt = Instant.now();

        Message message = new Message(id, incoming.roomId(), incoming.sender(),
                incoming.text(), sentAt);

        log.info("Nachricht {} von {} fuer Raum {} wird publiziert",
                id, incoming.sender(), incoming.roomId());

        // Zweites Argument ist der Routing-Key. Ein Fanout-Exchange ignoriert ihn,
        // deshalb steht dort der leere String.
        rabbitTemplate.convertAndSend(RabbitConfiguration.EXCHANGE_NAME, "", message);

        log.info("Nachricht {} an Exchange {} uebergeben", id, RabbitConfiguration.EXCHANGE_NAME);
        return message;
    }
```

- [ ] **Schritt 6: Den Controller um `POST` erweitern**

In `MessageController.java` anfügen. Zusätzliche Importe:

```java
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
```

Neue Methode:

```java
    /**
     * Nimmt eine Nachricht entgegen und gibt sie an RabbitMQ weiter.
     *
     * Die Antwort ist 202 Accepted und nicht 201 Created: wir haben die Nachricht
     * angenommen und weitergegeben, gespeichert ist sie in diesem Moment noch nicht.
     * 201 wuerde etwas versprechen, was noch nicht stimmt.
     */
    @Operation(
            summary = "Nachricht senden",
            description = "Nimmt eine Nachricht an und publiziert sie auf den Fanout-Exchange "
                        + "'chat.messages'. Die Antwort kommt sofort. Gespeichert wird die "
                        + "Nachricht kurz danach vom batch-service - sie erscheint also erst "
                        + "mit kleiner Verzoegerung im Verlauf.")
    @ApiResponse(responseCode = "202", description = "Nachricht angenommen und publiziert")
    @ApiResponse(responseCode = "400", description = "roomId fehlt oder der Text ist leer")
    @PostMapping
    public ResponseEntity<Message> send(@RequestBody NewMessage incoming) {

        log.info("Sendeanfrage erhalten: Raum {}, Absender {}", incoming.roomId(), incoming.sender());

        // Eingaben pruefen, bevor irgendetwas den Dienst verlaesst.
        if (incoming.roomId() == null) {
            log.warn("Sendeanfrage ohne roomId abgelehnt");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "roomId fehlt");
        }
        if (incoming.sender() == null || incoming.sender().isBlank()) {
            log.warn("Sendeanfrage ohne sender fuer Raum {} abgelehnt", incoming.roomId());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sender fehlt");
        }
        if (incoming.text() == null || incoming.text().isBlank()) {
            log.warn("Sendeanfrage mit leerem Text fuer Raum {} abgelehnt", incoming.roomId());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "text darf nicht leer sein");
        }

        Message published = messageService.sendMessage(incoming);

        log.info("Sendeanfrage beantwortet: Nachricht {} angenommen", published.id());
        return ResponseEntity.accepted().body(published);
    }
```

> **Achtung, zwei gleichnamige Annotationen.** `@RequestBody` gibt es zweimal: die von Spring
> (`org.springframework.web.bind.annotation.RequestBody`) bindet den JSON-Körper an den Parameter,
> die von Swagger (`io.swagger.v3.oas.annotations.parameters.RequestBody`) beschreibt ihn nur in
> der Dokumentation. Gebraucht wird hier die **von Spring**. Importiert man versehentlich die
> andere, kompiliert alles, aber der Parameter bleibt zur Laufzeit `null`.

- [ ] **Schritt 7: Tests laufen lassen — sie müssen bestehen**

```bash
mvn test
```

Erwartet: **BESTANDEN.** `Tests run: 4, Failures: 0, Errors: 0`.

> **Docker muss laufen.** Seit Task 2 fährt `ChatServiceApplicationTest` die ganze
> Anwendung hoch und braucht dafür Datenbank und Broker. Vorher im
> Projektwurzelverzeichnis `docker compose up -d` ausführen.

- [ ] **Schritt 8: Von Hand prüfen — die Nachricht muss wirklich im Broker landen**

Ein grüner Test beweist nur, dass der Controller den Service ruft. Ob RabbitMQ die Nachricht
bekommt, sieht man nur im Broker.

> **Wichtig für das Verständnis:** an `chat.messages` hängt in dieser Phase **keine** Queue. Ein
> Exchange speichert nichts — er verteilt nur. Ohne gebundene Queue verschwindet jede publizierte
> Nachricht **spurlos**, ohne Fehler und ohne Log-Eintrag. Deshalb legen wir unten von Hand eine
> Test-Queue an: sonst gäbe es nichts zu sehen. Die richtigen Queues (`chat.persist` und
> `chat.live.<instanz>`) kommen mit dem `batch-service` und dem SSE-Plan.

```bash
docker compose up -d
cd chat-service && mvn spring-boot:run
```

1. <http://localhost:15672> öffnen (`chat` / `chat`).
2. Unter **Exchanges** muss `chat.messages` stehen, Typ `fanout`, Merkmal `D` (durable).
3. Unter **Queues and Streams** → *Add a new queue* eine Queue `test.listen` anlegen.
4. `chat.messages` anklicken → *Bindings* → *To queue* `test.listen` → *Bind*.
5. In Swagger UI `POST /api/messages` ausführen mit:

```json
{
  "roomId": "11111111-1111-1111-1111-111111111111",
  "sender": "lernende1",
  "text": "Erste Nachricht durch den Broker"
}
```

6. Antwort muss **202** sein, mit `id` und `sentAt` vom Server gesetzt.
7. Im Broker: Queue `test.listen` hat **1** Nachricht. *Get messages* → der Payload ist lesbares
   JSON mit `"text":"Erste Nachricht durch den Broker"` und einem Zeitpunkt in der Form
   `2026-09-04T08:05:00Z`.

Erwartet ist ausserdem: `GET /api/messages` liefert diese Nachricht **nicht** — sie steht ja nicht
in der Datenbank. Genau das ist der Beweis, dass Senden und Speichern getrennt sind. Der
`batch-service` schliesst diese Lücke im nächsten Plan.

8. Test-Queue danach wieder löschen, damit sie nicht unbemerkt volläuft.

- [ ] **Schritt 9: Commit**

```bash
cd it3b-m321
git add chat-service/
git commit -m "feat(chat-service): POST /api/messages publiziert auf den Fanout-Exchange"
```

---

## Fertig, wenn …

- [ ] `mvn test` im Verzeichnis `chat-service` ist grün (4 Tests)
- [ ] `docker compose up -d` bringt PostgreSQL und RabbitMQ hoch, `/actuator/health` meldet beide `UP`
- [ ] <http://localhost:8080/swagger-ui.html> zeigt den Bereich «Nachrichten» mit **beiden** Endpunkten,
      jeweils mit Beschreibung, Beispielwerten und den Antwortcodes 200/202/400
- [ ] `GET /api/messages` liefert die drei Demo-Nachrichten
- [ ] `POST /api/messages` antwortet mit 202, und die Nachricht ist im Broker sichtbar
- [ ] Fünf Commits liegen vor (Dokumente + vier Bau-Schritte)

---

## Übungsaufgabe für die Klasse

Wird der Code an die Lernenden gegeben, **vor dem Austeilen** in
`rabbit/RabbitConfiguration.java` den Rumpf von `chatExchange()` entfernen und ersetzen durch:

```java
    @Bean
    public FanoutExchange chatExchange() {
        // TODO Übung: Einen Fanout-Exchange mit dem Namen aus EXCHANGE_NAME zurückgeben.
        //             Er soll einen Broker-Neustart überleben und nicht automatisch
        //             gelöscht werden, wenn gerade keine Queue an ihm hängt.
    }
```

Umfang: **eine Zeile.** Gebraucht wird die Konstante `EXCHANGE_NAME` und der Konstruktor von
`FanoutExchange`, der neben dem Namen zwei Wahrheitswerte nimmt.

Diese Stelle ist mit Absicht gewählt: fehlt die Zeile, **kompiliert die Datei nicht** («missing
return statement»). Die Aufgabe kann also nicht stillschweigend falsch laufen — sie ist entweder
gelöst oder der Fehler steht sofort da. Der Rest des Dienstes bleibt unverändert.

---

## Prüfungsstoff in diesem Plan

| Thema | Wo es im Code steht |
|---|---|
| **Fanout-Exchange** — verteilt an alle gebundenen Queues, ignoriert den Routing-Key | `RabbitConfiguration`, Schritt 8 in Task 4 |
| **Publizieren statt Schreiben** — warum der Absender-Dienst die Datenbank nicht anfasst | `MessageService.sendMessage` |
| **202 statt 201** — angenommen ist nicht gespeichert | `MessageController.send` |
| **ID und Zeitstempel beim Sender** — Voraussetzung fürs spätere Bündeln | `MessageService.sendMessage` |
| **Schichten** — Controller kennt nur den Service, der Service nur das Repository | alle drei Klassen im Paket `message` |
| **Prepared Statements** — Werte als `?`, nie in den SQL-String geklebt | `MessageRepository.findLatest` |
| **Validierung an der Grenze** — prüfen, bevor etwas den Dienst verlässt | `MessageController`, beide Methoden |
| **API-Dokumentation aus dem Code** — was nicht annotiert ist, steht nicht in Swagger | alle `@Operation`/`@Schema`-Annotationen |
| **docker-compose** — Netzwerk, Volumes, veröffentlichte gegen interne Ports | `docker-compose.yml` |

---

## Entscheide in diesem Plan — und was daran diskutabel ist

| Entscheid | Begründung | Wenn es anders sein soll |
|---|---|---|
| **Englische Bezeichner, deutsche Kommentare** | Java, Spring und SQL bringen englisches Vokabular mit; gemischte Bezeichner wie `findeLetzteByRaumId` liest niemand gern. Erklärt wird trotzdem auf Deutsch. | — |
| **Spring Boot 3.5.16, nicht 4.x** | Boot 4 ist draussen, aber für den Unterricht zählt die Menge an Material: praktisch jedes Tutorial, jede Antwort im Netz und die ganze springdoc-2.x-Linie zielen auf Boot 3. springdoc 2.9.0 wird exakt gegen 3.5.16 gebaut. | Umstellen kostet den Wechsel auf springdoc 3.x und Spring AMQP 4 (dort heisst der Wandler `JacksonJsonMessageConverter`) |
| **`JdbcTemplate` statt JPA/Hibernate** | `CLAUDE.md` verbietet Annotation-Magie. Beim `JdbcTemplate` steht das SQL im Klartext und jede Spalte wird sichtbar in ein Feld übertragen. Ausserdem benutzt der `batch-service` ohnehin `batchUpdate`. | JPA wäre ein eigenes Kapitel — dann aber bewusst als Thema, nicht nebenbei |
| **Methodenreferenz `this::mapRow`** | Kürzer und benannt — man sieht am Namen, was passiert, statt einen Lambda-Rumpf mitten in der Abfrage zu lesen. | Falls Methodenreferenzen im Unterricht noch nicht dran waren: durch `(row, rowNumber) -> mapRow(row, rowNumber)` ersetzen oder eine benannte `RowMapper<Message>`-Klasse anlegen |
| **`record` statt Klasse mit Gettern** | Eine Zeile statt dreissig, und unveränderlich. Kein Lombok nötig. | Falls `record` im Unterricht noch nicht behandelt wurde: `Message` und `NewMessage` als normale Klassen mit Konstruktor und Gettern schreiben — sonst ändert sich nichts |
| **SQL-Init-Skripte statt Flyway** | Das Postgres-Image führt `/docker-entrypoint-initdb.d` von sich aus aus. Kein Werkzeug, kein Namensschema, kein zusätzliches Konzept. | Bei Schema-Änderungen `docker compose down -v` nötig. Sobald das nervt, ist Flyway die Antwort |
| **`TIMESTAMPTZ` statt `TIMESTAMP`** | Ohne Zeitzone geht die Zone beim Speichern verloren, und `Instant` ist genau ein Zeitpunkt in UTC. | — (`PLANUNG.md` ist bereits nachgezogen) |
| **`sender` im Request-Body** | Es gibt noch kein Token. Das Feld ist in Swagger ausdrücklich als Platzhalter markiert. | Fällt weg, sobald Keycloak steht — der Name kommt dann aus `preferred_username` |
| **Tests nur mit `MockMvc`** | Läuft ohne Docker und prüft genau das, was die API verspricht. | Repository und Broker werden hier von Hand geprüft (Schritte 8). Echte Integrationstests brauchen Testcontainers — eigener Plan |
| **Kein Eltern-POM** | Jeder Dienst ist eigenständig baubar — das ist das Modulthema. | Bei vier Diensten wird die Wiederholung lästig; dann ein Eltern-POM nachziehen |

---

## Danach

Diese Pläne bauen auf dem Bootstrap auf, in dieser Reihenfolge:

1. **`batch-service`** — Consumer auf `chat.persist`, zuerst einzeln schreiben, dann bündeln und den
   Unterschied messen (`PLANUNG.md`, Abschnitt 2.3 und 2.4).
2. **Raumverwaltung** — `POST /api/rooms`, Einladen per Benutzername, Mitgliedsprüfung beim Senden
   und Lesen.
3. **Keycloak** — Realm-Import, Token-Prüfung, `sender` aus dem Token.
4. **SSE** — `GET /stream`, Live-Queue je Instanz, `@RabbitListener`.
5. **Gateway und React-App** — nginx als einziger offener Port, `chat-service` wandert ins Compose
   und die veröffentlichten Ports aus Task 2 werden wieder zu `expose`.
