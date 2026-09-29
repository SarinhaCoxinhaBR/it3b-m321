# Relay: Umsetzungsplan des batch-writer

**Ziel:** Der `batch-writer` holt die Nachrichten aus `chat.persist`, schreibt sie in Paketen von
höchstens 500 Stück oder nach 200 ms mit **einer** Transaktion in die Tabelle `message` und
bestätigt sie erst nach dem `COMMIT`. Duplikate sind harmlos, Unlesbares landet in `chat.dlq`,
ein Datenbankausfall kostet keine einzige Nachricht.

**Architektur:** Schichtung `listener → service → repository`, dazu `config` für alles, was beim
Start eingerichtet wird, und `dto` für die Daten. Der Listener kennt kein SQL, das Repository kein
RabbitMQ. Der Dienst hat keine HTTP-Schnittstelle und keinen offenen Port.

**Tech-Stack:** Java 21, Spring Boot 3.5.16, Spring AMQP (`SimpleMessageListenerContainer` mit
Consumer-Batching), Spring JDBC (`JdbcTemplate.batchUpdate`, `TransactionTemplate`),
PostgreSQL 16, RabbitMQ 3.13, JUnit 5, Testcontainers, Maven Multi-Modul.

**Spec:** [`spec-batch-writer.md`](spec-batch-writer.md). Jede Aufgabe unten verweist auf den
Abschnitt, den sie umsetzt.

## Globale Vorgaben

Diese Punkte gelten für **jede** Aufgabe in diesem Plan:

- **Java 21**, Spring Boot **3.5.16**, dieselbe Version wie der `chat-service` über das Eltern-POM.
- **Code auf Englisch**: Klassen, Methoden, Variablen, Dateinamen und Log-Meldungen. **Alles
  andere auf Deutsch**: Kommentare, Javadoc, Commit-Messages, Doku.
- **Keine verschachtelten Aufrufe.** Ein Ergebnis pro Zeile, in eine benannte Variable. Gilt auch
  in Tests.
- **Schleifen statt Pipelines.** Jede Sammlung wird mit einer `for`-Schleife durchlaufen.
- **Kommentar über jeder Klasse und jeder Methode**, auch in den Tests. Task 9 prüft das
  automatisch.
- **Keine Interfaces mit einer einzigen Implementierung**, keine Abstraktion auf Vorrat.
- **Lombok** für Logger (`@Slf4j`) und Konstruktor-Injektion (`@RequiredArgsConstructor`), Java-
  `record` für Datenklassen.
- **Kein `ports`-Eintrag** in `docker-compose.yml`.
- **Keine Geheimnisse im Repository.** Zugangsdaten nur in `.env`, im Repo nur `.env.example`.
- **Jeder Commit hat genau ein Thema**, eine deutsche Message und endet mit dieser Zeile:
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  ```
- **Voraussetzung:** Docker läuft. Testcontainers startet für die Tests einen echten PostgreSQL und
  einen echten RabbitMQ.

---

## Abgrenzung

| Bewusst **nicht** in diesem Plan | Warum |
|---|---|
| Chat-Historie lesen | Nicht Teil der Bewertung. Der Index für diese Abfrage wird trotzdem angelegt (Spec 4.2) |
| Tabellen `room` und `room_member`, Fremdschlüssel | Nicht Teil der Bewertung. Siehe Spec 3.3 |
| Keycloak, web-gateway, load-generator | Nicht Teil der Bewertung |
| Werkzeug zum Wiedereinspielen aus `chat.dlq` | Offener Punkt 4 der Spec |
| Änderungen am Verhalten des `chat-service` | Sein Vertrag ist die Grundlage. Angepasst wird nur sein `Dockerfile`, weil das Eltern-POM ein zweites Modul bekommt |

---

## Dateistruktur

```
pom.xml                                   # Eltern-POM: Modul batch-writer dazu
docker-compose.yml                        # postgres und batch-writer dazu, kein ports-Eintrag
.env.example                              # Zugangsdaten für postgres, Werte des batch-writer
.dockerignore                             # .env und Build-Ordner nie ins Image
chat-service/Dockerfile                   # kopiert jetzt auch batch-writer/pom.xml
scripts/scenarios.sh                      # stellt S2 bis S8 am laufenden Stack nach
batch-writer/
├── pom.xml
├── Dockerfile
└── src/
    ├── main/java/ch/benedict/m321/batchwriter/
    │   ├── BatchWriterApplication.java
    │   ├── config/
    │   │   ├── QueueNames.java             # die zwei Namen an genau einer Stelle
    │   │   └── RabbitConfig.java           # Queues und der gebündelte Listener-Container
    │   ├── dto/
    │   │   ├── ChatMessage.java            # eigene Kopie des Vertrags
    │   │   └── ReceivedMessage.java        # Nachricht plus Zustellnummer für ACK/Reject
    │   ├── listener/
    │   │   └── PersistQueueListener.java   # lesen, schreiben lassen, bestätigen
    │   ├── service/
    │   │   ├── InvalidMessageException.java
    │   │   ├── MessageParser.java          # JSON lesen und prüfen
    │   │   ├── DatabaseRetry.java          # warten, solange die Datenbank fehlt
    │   │   └── MessageBatchWriter.java     # ein Paket, eine Transaktion, Einzelweg
    │   └── repository/
    │       └── MessageRepository.java      # das INSERT mit ON CONFLICT
    ├── main/resources/
    │   ├── application.yml
    │   └── schema.sql                      # Tabelle message und Index
    └── test/java/ch/benedict/m321/batchwriter/
        ├── TestcontainersConfiguration.java
        ├── BatchWriterApplicationTest.java
        ├── SchemaIntegrationTest.java
        ├── CodeRulesTest.java
        ├── service/MessageParserTest.java
        ├── service/DatabaseRetryTest.java
        ├── service/MessageBatchWriterIntegrationTest.java
        ├── repository/MessageRepositoryIntegrationTest.java
        └── listener/
            ├── ScenarioSupport.java        # senden, zählen, warten
            ├── DatabaseOutage.java         # Datenbank im Test sperren und freigeben
            ├── PersistFlowIntegrationTest.java
            ├── BacklogIntegrationTest.java
            ├── CompetingConsumersIntegrationTest.java
            └── DatabaseOutageIntegrationTest.java
```

**Wer wen kennt**, und zwar nur in dieser Richtung:

```
RabbitConfig ──erzeugt──► Listener-Container ──ruft auf──► PersistQueueListener
                                                                │
                                   ┌────────────────────────────┴───────────────┐
                                   ▼                                            ▼
                             MessageParser                              MessageBatchWriter
                                                                         │             │
                                                                         ▼             ▼
                                                                  DatabaseRetry  MessageRepository ──► PostgreSQL
```

Alle Spring-Tests nutzen dieselbe `TestcontainersConfiguration`. Spring legt dadurch **einen**
Kontext mit **einem** PostgreSQL und **einem** RabbitMQ an und teilt ihn zwischen allen
Testklassen. Das hält `mvn clean test` in einem Lauf schnell genug. Testklassen heissen wie im
`chat-service` `...Test` oder `...IntegrationTest`, damit Surefire sie ohne weiteres Plugin findet.

---

## Reihenfolge und Commits

| Nr | Commit-Message | Warum an dieser Stelle |
|---|---|---|
| 0a | `docs: Spezifikation für den batch-writer` | Erst beschreiben, der Lehrperson zeigen, dann planen |
| 0b | `docs: Umsetzungsplan für den batch-writer` | Dieser Plan, vor der ersten Zeile Code |
| 1 | `chore: Modul batch-writer im Eltern-POM anlegen` | Alles Weitere braucht ein Modul, das baut und gegen echte Container startet |
| 2 | `feat: Tabelle message beim Start anlegen` | Jeder spätere Test schreibt in diese Tabelle, ihre Spalten sind ein fester Prüfpunkt |
| 3 | `feat: Nachrichten im Format des chat-service lesen und prüfen` | Der Vertrag steht vor allem anderen und lässt sich ohne Container testen |
| 4 | `feat: Nachrichten mit einem Batch-INSERT und ON CONFLICT schreiben` | Das INSERT ist der Kern von S4 und S5 und braucht nur die Tabelle |
| 5 | `feat: Bei Datenbankausfall warten statt Nachrichten verwerfen` | Der Writer in Task 6 baut darauf auf, allein ist es als Unit-Test prüfbar |
| 6 | `feat: Paket in einer Transaktion schreiben, abgelehnte Zeilen einzeln aussortieren` | Verbindet Repository und Warten, bevor RabbitMQ dazukommt |
| 7 | `feat: chat.persist gebündelt lesen und erst nach dem Commit bestätigen` | Erst jetzt gibt es etwas, das der Listener aufrufen kann |
| 8 | `test: Rückstau, zwei Konsumenten und Datenbankausfall nachstellen` | Diese Szenarien brauchen den ganzen Weg aus Task 1 bis 7 |
| 9 | `test: Kommentarregel aus CLAUDE.md automatisch prüfen` | Prüft den fertigen Quelltext, darum nach dem letzten Code |
| 10 | `chore: postgres und batch-writer in docker-compose abbilden` | Der Dienst ist fertig getestet, jetzt kommt er in den Stack |
| 11 | `test: Szenario-Skript für S2 bis S8` | Braucht den laufenden Stack aus Task 10 |
| 12 | `docs: README-Stand nachführen` | Zum Schluss, wenn feststeht, was vorhanden ist |

---

## Task 1: Modul im Eltern-POM und Anwendungsstart

**Setzt um:** Spec 4.5 und 4.6.

**Dateien:**
- Ändern: `pom.xml` (Modul `batch-writer`, Lombok als Annotation-Prozessor)
- Ändern: `chat-service/Dockerfile` (kopiert zusätzlich `batch-writer/pom.xml`)
- Anlegen: `batch-writer/pom.xml`
- Anlegen: `batch-writer/src/main/java/ch/benedict/m321/batchwriter/BatchWriterApplication.java`
- Anlegen: `batch-writer/src/main/resources/application.yml`
- Test: `batch-writer/src/test/java/ch/benedict/m321/batchwriter/TestcontainersConfiguration.java`
- Test: `batch-writer/src/test/java/ch/benedict/m321/batchwriter/BatchWriterApplicationTest.java`

**Warum jetzt:** Ohne Modul gibt es nichts zu testen. `TestcontainersConfiguration` entsteht hier,
weil ab Task 2 jeder Spring-Test dieselben Container braucht.

- [ ] **Schritt 1: Den fehlschlagenden Test schreiben.** `BatchWriterApplicationTest.contextLoads`
  startet den Spring-Kontext mit `@SpringBootTest` und `@Import(TestcontainersConfiguration.class)`.
  Die Konfiguration stellt `PostgreSQLContainer` (`postgres:16-alpine`) und `RabbitMQContainer`
  (`rabbitmq:3.13-management`) als Beans mit `@ServiceConnection` bereit.
- [ ] **Schritt 2: Test laufen lassen.** `mvn -q -pl batch-writer test`. Erwartet: Fehlschlag, das
  Modul existiert nicht.
- [ ] **Schritt 3: Eltern-POM ergänzen.** `<module>batch-writer</module>`. Dazu Lombok als
  `annotationProcessorPath` im `maven-compiler-plugin`, damit Lombok auch mit einem JDK ab
  Version 23 läuft, das Annotation-Prozessoren nicht mehr von selbst startet.
- [ ] **Schritt 4: `chat-service/Dockerfile` anpassen.** Maven liest beim Bauen alle Module des
  Eltern-POM. Ohne `COPY batch-writer/pom.xml` bricht der Build des chat-service ab.
- [ ] **Schritt 5: Modul-POM anlegen.** Eltern-POM `ch.benedict.m321:it3c-m321`. Abhängigkeiten:
  `spring-boot-starter-amqp`, `spring-boot-starter-jdbc`, `spring-boot-starter-json`,
  `postgresql` (runtime), `lombok` (optional). Test: `spring-boot-starter-test`,
  `spring-boot-testcontainers`, Testcontainers `postgresql` und `rabbitmq`.
- [ ] **Schritt 6: Hauptklasse und `application.yml` anlegen.** Werte genau wie in Spec 4.5 und
  4.6: kein Webserver, Hikari mit 3 Verbindungen und 3 s Zeitlimit, `reWriteBatchedInserts`,
  `batch-writer.batch-size`, `batch-writer.batch-timeout-ms`, `batch-writer.retry-delay-ms`.
- [ ] **Schritt 7: Test laufen lassen.** `mvn -q clean test` im Wurzelverzeichnis. Erwartet: grün,
  auch die Tests des chat-service.
- [ ] **Schritt 8: Committen.**
  ```bash
  git add pom.xml chat-service/Dockerfile batch-writer
  git commit -m "chore: Modul batch-writer im Eltern-POM anlegen" \
    -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
  ```

---

## Task 2: Tabelle `message` beim Start anlegen

**Setzt um:** Spec 4.1 bis 4.3.

**Dateien:**
- Anlegen: `batch-writer/src/main/resources/schema.sql`
- Test: `batch-writer/src/test/java/ch/benedict/m321/batchwriter/SchemaIntegrationTest.java`

**Warum jetzt:** Ab Task 4 schreibt jeder Test in diese Tabelle. Ihre Spalten aus `PLANUNG.md` 3.7
sind ein fester Punkt, an dem das Prüfskript der Lehrperson ansetzt.

- [ ] **Schritt 1: Den fehlschlagenden Test schreiben.** Drei Tests gegen
  `information_schema.columns` und `pg_indexes`: die Spalten `id, room_id, sender_id, sender_name,
  content, sent_at` in dieser Reihenfolge und mit den Typen aus Spec 4.1; `id` und `sent_at` ohne
  Default; der Index `idx_message_room_sent_at` auf `(room_id, sent_at DESC)`.
- [ ] **Schritt 2: Test laufen lassen.** Erwartet: rot, die Tabelle gibt es nicht.
- [ ] **Schritt 3: `schema.sql` anlegen.** `CREATE TABLE IF NOT EXISTS` und
  `CREATE INDEX IF NOT EXISTS` wie in Spec 4.1 und 4.2. `spring.sql.init.mode: always` steht
  bereits in `application.yml`.
- [ ] **Schritt 4: Test laufen lassen.** Erwartet: grün.
- [ ] **Schritt 5: Committen** mit `feat: Tabelle message beim Start anlegen`.

---

## Task 3: Nachrichten im Format des chat-service lesen

**Setzt um:** Spec 2.1 und 2.3, Fehlerfall «Unlesbare Nachricht».

**Dateien:**
- Anlegen: `batch-writer/src/main/java/ch/benedict/m321/batchwriter/dto/ChatMessage.java`
- Anlegen: `batch-writer/src/main/java/ch/benedict/m321/batchwriter/service/InvalidMessageException.java`
- Anlegen: `batch-writer/src/main/java/ch/benedict/m321/batchwriter/service/MessageParser.java`
- Test: `batch-writer/src/test/java/ch/benedict/m321/batchwriter/service/MessageParserTest.java`

**Warum jetzt:** Was auf der Queue liegt, bestimmt alles Weitere. Der Parser braucht keinen
Container, der Test läuft in Millisekunden.

- [ ] **Schritt 1: Den fehlschlagenden Test schreiben.** Fälle: eine Nachricht genau im Format des
  chat-service, nur mit `content_type` (S5); ein unbekanntes zusätzliches Feld wird ignoriert;
  kaputtes JSON, eine fehlende `id`, ein leerer `content` und ein leerer Body werfen
  `InvalidMessageException`. Der Test baut den `ObjectMapper` mit `Jackson2ObjectMapperBuilder`,
  also mit denselben Voreinstellungen wie Spring Boot.
- [ ] **Schritt 2: Test laufen lassen.** Erwartet: rot, die Klassen fehlen.
- [ ] **Schritt 3: `ChatMessage` als `record`** mit denselben Feldern wie im chat-service.
- [ ] **Schritt 4: `InvalidMessageException`** als geprüfte Exception, damit der Aufrufer den Fall
  behandeln muss.
- [ ] **Schritt 5: `MessageParser`** liest nur den Body mit dem `ObjectMapper` von Spring Boot und
  prüft die Pflichtfelder aus Spec 2.3.
- [ ] **Schritt 6: Test laufen lassen.** Erwartet: grün.
- [ ] **Schritt 7: Committen** mit `feat: Nachrichten im Format des chat-service lesen und prüfen`.

---

## Task 4: Batch-INSERT mit `ON CONFLICT`

**Setzt um:** Spec 3.1 Schritt 4, Fehlerfall S5.

**Dateien:**
- Anlegen: `batch-writer/src/main/java/ch/benedict/m321/batchwriter/repository/MessageRepository.java`
- Test: `batch-writer/src/test/java/ch/benedict/m321/batchwriter/repository/MessageRepositoryIntegrationTest.java`

**Warum jetzt:** Das INSERT ist die Stelle, an der S5 besteht oder nicht. Es braucht nur die
Tabelle aus Task 2 und die Datenklasse aus Task 3.

- [ ] **Schritt 1: Den fehlschlagenden Test schreiben.** Ein Paket mit drei Nachrichten ergibt drei
  Zeilen; dieselbe Nachricht zweimal im **selben** Paket ergibt eine Zeile; dieselbe Nachricht in
  **zwei** Paketen ergibt eine Zeile; `sent_at` ist auf die Mikrosekunde der Wert aus der Nachricht.
- [ ] **Schritt 2: Test laufen lassen.** Erwartet: rot.
- [ ] **Schritt 3: `MessageRepository`** mit `JdbcTemplate.batchUpdate` und
  `INSERT ... ON CONFLICT (id) DO NOTHING`. `Instant` wird als `OffsetDateTime` in UTC übergeben,
  damit der Treiber `timestamptz` ohne Umweg über die Zeitzone des Servers schreibt.
- [ ] **Schritt 4: Test laufen lassen.** Erwartet: grün.
- [ ] **Schritt 5: Committen** mit `feat: Nachrichten mit einem Batch-INSERT und ON CONFLICT schreiben`.

---

## Task 5: Warten, solange die Datenbank fehlt

**Setzt um:** Spec 3.2, Fehlerfall S7, und 3.3 (Abweichung vom NACK-Pfad).

**Dateien:**
- Anlegen: `batch-writer/src/main/java/ch/benedict/m321/batchwriter/service/DatabaseRetry.java`
- Test: `batch-writer/src/test/java/ch/benedict/m321/batchwriter/service/DatabaseRetryTest.java`

**Warum jetzt:** Task 6 schreibt jedes Paket durch diese Klasse. Allein lässt sich die Regel «was
zählt als Ausfall, was nicht» ohne Datenbank und in Millisekunden prüfen.

- [ ] **Schritt 1: Den fehlschlagenden Test schreiben.** Mit 10 ms Pause: zwei
  Verbindungsfehler, dann Erfolg, ergibt drei Versuche; eine `TransactionException` wird ebenfalls
  wiederholt; eine `DataIntegrityViolationException` wird sofort weitergereicht, nach genau einem
  Versuch.
- [ ] **Schritt 2: Test laufen lassen.** Erwartet: rot.
- [ ] **Schritt 3: `DatabaseRetry`** mit einer Schleife: Arbeit ausführen, bei `DataAccessException`
  oder `TransactionException` loggen, `retry-delay-ms` warten, nochmals.
  `DataIntegrityViolationException` wird vorher abgefangen und weitergeworfen.
- [ ] **Schritt 4: Test laufen lassen.** Erwartet: grün.
- [ ] **Schritt 5: Committen** mit `feat: Bei Datenbankausfall warten statt Nachrichten verwerfen`.

---

## Task 6: Ein Paket, eine Transaktion, Einzelweg

**Setzt um:** Spec 3.1 Schritt 4, Fehlerfall «Datenbank lehnt eine einzelne Zeile ab».

**Dateien:**
- Anlegen: `batch-writer/src/main/java/ch/benedict/m321/batchwriter/dto/ReceivedMessage.java`
- Anlegen: `batch-writer/src/main/java/ch/benedict/m321/batchwriter/service/MessageBatchWriter.java`
- Test: `batch-writer/src/test/java/ch/benedict/m321/batchwriter/service/MessageBatchWriterIntegrationTest.java`

**Warum jetzt:** Repository und Warten sind da. Bevor RabbitMQ dazukommt, soll feststehen, was der
Listener zurückbekommt: die Liste der Nachrichten, die die Datenbank abgelehnt hat.

- [ ] **Schritt 1: Den fehlschlagenden Test schreiben.** Ein gültiges Paket ergibt eine leere
  Ablehnungsliste und alle Zeilen; ein Paket mit einem `senderName` von 300 Zeichen ergibt genau
  diese eine Nachricht als abgelehnt und alle anderen als Zeilen.
- [ ] **Schritt 2: Test laufen lassen.** Erwartet: rot.
- [ ] **Schritt 3: `ReceivedMessage`** als `record` aus Zustellnummer und `ChatMessage`.
- [ ] **Schritt 4: `MessageBatchWriter`** schreibt mit `TransactionTemplate` alles in einer
  Transaktion, über `DatabaseRetry`. Bei `DataIntegrityViolationException` einmalig Zeile für
  Zeile, jede Zeile in ihrer eigenen Transaktion. Log-Meldung nach Erfolg:
  `Batch of {} messages committed in one transaction`.
- [ ] **Schritt 5: Test laufen lassen.** Erwartet: grün.
- [ ] **Schritt 6: Committen** mit `feat: Paket in einer Transaktion schreiben, abgelehnte Zeilen einzeln aussortieren`.

---

## Task 7: `chat.persist` gebündelt lesen und bestätigen

**Setzt um:** Spec 3.1, 4.4, Fehlerfälle S3, S5, «Unlesbare Nachricht», «Unerwarteter Fehler».

**Dateien:**
- Anlegen: `batch-writer/src/main/java/ch/benedict/m321/batchwriter/config/QueueNames.java`
- Anlegen: `batch-writer/src/main/java/ch/benedict/m321/batchwriter/config/RabbitConfig.java`
- Anlegen: `batch-writer/src/main/java/ch/benedict/m321/batchwriter/listener/PersistQueueListener.java`
- Test: `batch-writer/src/test/java/ch/benedict/m321/batchwriter/listener/ScenarioSupport.java`
- Test: `batch-writer/src/test/java/ch/benedict/m321/batchwriter/listener/PersistFlowIntegrationTest.java`

**Warum jetzt:** Der Listener verbindet Parser und Writer. Beide sind getestet, ein Fehler hier
liegt also im Zusammenspiel mit RabbitMQ und nicht darunter.

- [ ] **Schritt 1: Die fehlschlagenden Tests schreiben.** `ScenarioSupport` sendet JSON direkt in
  `chat.persist`, nur mit `content_type`, und zählt Zeilen und Queue-Inhalte. Tests:
  1000 Nachrichten stehen nach höchstens 60 s in der Tabelle und die Queue ist leer (S3);
  dieselbe Nachricht zweimal ergibt eine Zeile und eine leere DLQ (S5); kaputtes JSON landet
  unverändert in `chat.dlq`, die gültige Nachricht daneben in der Tabelle; eine zu lange Zeile
  landet allein in `chat.dlq`.
- [ ] **Schritt 2: Tests laufen lassen.** Erwartet: rot, niemand liest die Queue.
- [ ] **Schritt 3: `QueueNames`** mit `chat.persist` und `chat.dlq`.
- [ ] **Schritt 4: `RabbitConfig`** deklariert beide Queues mit denselben Argumenten wie der
  chat-service und baut den `SimpleMessageListenerContainer`: `MANUAL`, Consumer-Batching,
  `batchSize` und `prefetchCount` gleich `batch-size`, `receiveTimeout` und `batchReceiveTimeout`
  gleich `batch-timeout-ms`, ein Konsument, fehlende Queues nicht tödlich.
- [ ] **Schritt 5: `PersistQueueListener`** als `ChannelAwareBatchMessageListener`: jede Nachricht
  lesen, Unlesbares mit `basicReject` ohne Requeue ablehnen, den Rest schreiben lassen, danach
  jede Nachricht einzeln `basicAck` oder `basicReject`. Unerwartete Fehler: loggen und
  `basicNack` mit Requeue.
- [ ] **Schritt 6: Tests laufen lassen.** Erwartet: grün, dazu alle Tests aus Task 1 bis 6.
- [ ] **Schritt 7: Committen** mit `feat: chat.persist gebündelt lesen und erst nach dem Commit bestätigen`.

---

## Task 8: Rückstau, zwei Konsumenten, Datenbankausfall

**Setzt um:** Spec 3.2, Fehlerfälle S4, S6 und S7.

**Dateien:**
- Test: `batch-writer/src/test/java/ch/benedict/m321/batchwriter/listener/BacklogIntegrationTest.java`
- Test: `batch-writer/src/test/java/ch/benedict/m321/batchwriter/listener/CompetingConsumersIntegrationTest.java`
- Test: `batch-writer/src/test/java/ch/benedict/m321/batchwriter/listener/DatabaseOutage.java`
- Test: `batch-writer/src/test/java/ch/benedict/m321/batchwriter/listener/DatabaseOutageIntegrationTest.java`

**Warum jetzt:** Diese drei Szenarien prüfen das Verhalten des ganzen Wegs. Sie sind erst sinnvoll,
wenn Task 1 bis 7 stehen. Erwartet ist, dass sie **sofort grün** sind. Wird einer rot, liegt der
Fehler in Task 5 bis 7 und wird in diesem Commit behoben.

- [ ] **Schritt 1: `BacklogIntegrationTest` (S4).** Listener-Container stoppen, 1000 Nachrichten
  senden, `xact_commit` lesen, Container starten, warten bis 1000 Zeilen da sind, `xact_commit`
  wieder lesen. Vor jedem Lesen 11 s warten, weil PostgreSQL seine Statistik verzögert nachführt.
  Erwartet: Differenz höchstens 100.
- [ ] **Schritt 2: `CompetingConsumersIntegrationTest` (S6).** Den Container auf zwei Konsumenten
  stellen, warten, bis RabbitMQ zwei Konsumenten an `chat.persist` meldet, 1000 Nachrichten senden.
  Erwartet: 1000 Zeilen, keine doppelt. Am Ende wieder ein Konsument. Zwei Prozesse prüft das
  Skript aus Task 11, der Test prüft dieselbe Regel innerhalb eines Prozesses.
- [ ] **Schritt 3: `DatabaseOutage` und `DatabaseOutageIntegrationTest` (S7).** Die Hilfsklasse
  sperrt die Testdatenbank mit `ALTER DATABASE ... ALLOW_CONNECTIONS false` und beendet alle
  offenen Verbindungen. Den Container selbst zu stoppen geht nicht: Testcontainers vergäbe beim
  Neustart einen anderen Port. Test: sperren, 300 Nachrichten senden, 15 s warten, freigeben.
  Erwartet: nach höchstens 90 s 300 Zeilen, `chat.dlq` leer, Listener läuft noch.
- [ ] **Schritt 4: Tests laufen lassen.** `mvn -q clean test`. Erwartet: grün.
- [ ] **Schritt 5: Committen** mit `test: Rückstau, zwei Konsumenten und Datenbankausfall nachstellen`.

---

## Task 9: Kommentarregel automatisch prüfen

**Setzt um:** Spec 5, Kriterium S8.

**Dateien:**
- Test: `batch-writer/src/test/java/ch/benedict/m321/batchwriter/CodeRulesTest.java`

**Warum jetzt:** Der Test prüft den fertigen Quelltext. Vorher würde er bei jeder neuen Datei rot,
die gerade im Entstehen ist.

- [ ] **Schritt 1: Test schreiben.** Der Test liest jede `.java`-Datei unter `src/main/java` und
  `src/test/java` mit dem Java-Compiler (`JavacTask.parse`) und meldet jede Klasse und jede
  Methode ohne Javadoc, mit Datei und Name.
- [ ] **Schritt 2: Probe aufs Exempel.** Einen Kommentar kurz entfernen, der Test muss rot werden
  und die Stelle nennen. Kommentar wieder einsetzen.
- [ ] **Schritt 3: Test laufen lassen.** Erwartet: grün.
- [ ] **Schritt 4: Committen** mit `test: Kommentarregel aus CLAUDE.md automatisch prüfen`.

---

## Task 10: postgres und batch-writer im Stack

**Setzt um:** Spec 4.5 und 4.7, Kriterium S2.

**Dateien:**
- Anlegen: `batch-writer/Dockerfile`
- Anlegen: `.dockerignore`
- Ändern: `docker-compose.yml`
- Ändern: `.env.example`

**Warum jetzt:** Der Dienst ist fertig und getestet. Erst jetzt lohnt es sich, ihn in den Stack zu
bringen, weil ein Fehler hier nur noch an Docker liegen kann.

- [ ] **Schritt 1: `batch-writer/Dockerfile`** in zwei Stufen wie beim chat-service, Build-Kontext
  ist das Wurzelverzeichnis. Das Image läuft als eigener Benutzer ohne Root-Rechte und hat kein
  `EXPOSE`.
- [ ] **Schritt 2: `.dockerignore`**, damit `.env`, `.git` und `target` nie im Build-Kontext landen.
- [ ] **Schritt 3: `docker-compose.yml`** um `postgres` und `batch-writer` ergänzen, wie in
  Spec 4.7. Healthcheck von postgres über TCP (`pg_isready -h 127.0.0.1`), damit er erst grün wird,
  wenn die Datenbank nach dem ersten Anlegen wirklich bereit ist.
- [ ] **Schritt 4: `.env.example`** um `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_DB` und die
  drei `BATCH_WRITER_*`-Werte ergänzen.
- [ ] **Schritt 5: Stack starten und prüfen.**
  ```bash
  cp .env.example .env
  docker compose up -d --build
  docker compose ps
  ```
  Erwartet: vier Dienste `running`, bei keinem ein Eintrag der Form `0.0.0.0:...->...`.
- [ ] **Schritt 6: Committen** mit `chore: postgres und batch-writer in docker-compose abbilden`.

---

## Task 11: Szenario-Skript

**Setzt um:** Spec 5.

**Dateien:**
- Anlegen: `scripts/scenarios.sh` (ausführbar)

**Warum jetzt:** Das Skript misst am laufenden Stack aus Task 10 so, wie die Lehrperson misst: von
innen mit `docker compose exec postgres psql` und `rabbitmqctl`.

- [ ] **Schritt 1: Skript schreiben.** Befehle `fresh`, `s1` bis `s8` und `all`. Gesendet wird mit
  `curlimages/curl` im Netz `chat-net`, S5 legt die Nachricht über die Management-API von RabbitMQ
  direkt in `chat.persist`, nur mit `content_type`. Jedes Szenario meldet `BESTANDEN` oder
  `NICHT BESTANDEN` mit dem gemessenen Wert.
- [ ] **Schritt 2: Prüfen.** `shellcheck scripts/scenarios.sh` ohne Befund, dann
  `scripts/scenarios.sh all`. Erwartet: acht Mal `BESTANDEN`.
- [ ] **Schritt 3: Committen** mit `test: Szenario-Skript für S2 bis S8`.

---

## Task 12: README

**Dateien:**
- Ändern: `README.md`

- [ ] **Schritt 1:** Tabelle «Stand»: `batch-writer` und `postgres` auf «vorhanden». Befehle für
  Test, Stack und Skript ergänzen, Spec und Plan unter «Dokumente» verlinken.
- [ ] **Schritt 2: Prüfen.** Jede verlinkte Datei muss existieren, jeder Befehl muss laufen:
  ```bash
  for datei in docs/spec-batch-writer.md docs/plan-batch-writer.md scripts/scenarios.sh; do
    test -e "$datei" && echo "ok: $datei" || echo "fehlt: $datei"
  done
  grep -n "vorhanden" README.md
  ```
  Erwartet: dreimal `ok`, `batch-writer` und `postgres` stehen auf «vorhanden». Die Befehle aus
  dem README einmal von Hand ausführen.
- [ ] **Schritt 3: Committen** mit `docs: README-Stand nachführen`.

---

## Abschluss-Prüfung

- [ ] `mvn clean test`: alle Tests grün, in einem Lauf
- [ ] `scripts/scenarios.sh all`: S2 bis S8 `BESTANDEN`
- [ ] `grep -nE '^\s*ports:' docker-compose.yml`: keine Treffer
- [ ] `git status --short`: sauber, `.env` taucht nicht auf
- [ ] `git log --oneline`: die Commits 0a bis 12 in dieser Reihenfolge
- [ ] `git tag bewertung-1 && git push origin main bewertung-1`
