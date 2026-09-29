# Relay: Spezifikation des batch-writer

**Relay, die Chat-App aus Modul M321 · Klasse IT3c · Bewertung 1**

Grundlage: `PLANUNG.md` (Abschnitte 3.4 bis 3.7, 4.1, 6 und 7), der Quelltext des `chat-service`
und der Auftrag «batch-writer». Massstab: Eine Mitschülerin kann den Dienst allein aus diesem
Dokument bauen.

---

## 1. Zweck und Abgrenzung

### 1.1 Zweck

Der `batch-writer` ist der **einzige Dienst, der in die Tabelle `message` schreibt**. Er holt die
Nachrichten aus der Queue `chat.persist`, bündelt sie zu Paketen und schreibt jedes Paket mit
**einer einzigen Datenbanktransaktion**. Erst nach dem `COMMIT` bestätigt er die Nachrichten bei
RabbitMQ.

Der Grund steht in `PLANUNG.md` 4.1: Das System soll 100'000 Nachrichten pro Minute tragen, also
1'667 pro Sekunde. Einzeln geschrieben wären das 1'667 Transaktionen pro Sekunde. In Paketen zu
500 Nachrichten sind es rund 3,3. Ohne diesen Dienst bleibt jede Nachricht in `chat.persist`
liegen und ist bei einem Neustart von RabbitMQ oder einer vollen Queue verloren.

### 1.2 Was der Dienst bewusst nicht tut

| Nicht Aufgabe des batch-writer | Wer stattdessen, oder warum nicht |
|---|---|
| Nachrichten annehmen, ID und Zeitstempel vergeben | `chat-service`. Der batch-writer übernimmt beides unverändert |
| Nachrichten an Clients zustellen (`chat.delivery`) | `web-gateway`, später. Zustellung und Speicherung sind entkoppelt (`PLANUNG.md` 3.4) |
| Verlauf lesen | `chat-service`, später. Nicht Teil dieser Aufgabe |
| Räume und Mitgliedschaften prüfen | Nicht Teil dieser Aufgabe. Es gibt keine Tabelle `room` und keinen Fremdschlüssel (4.1) |
| Token prüfen | `web-gateway`. Der batch-writer ist von aussen nicht erreichbar |
| HTTP-Schnittstelle, offener Port | Keiner. Der Dienst startet ohne Webserver und baut Verbindungen nur auf |
| Nachrichten ändern, löschen, archivieren | Nicht vorgesehen. Wachstum der Datenbank ist offener Punkt 7 |
| Nachrichten aus `chat.dlq` wieder einspielen | Von Hand, nach Prüfung. Die DLQ ist ein Abstellgleis zum Anschauen |

---

## 2. Vertrag mit dem chat-service

### 2.1 Was auf `chat.persist` ankommt

Eine AMQP-Nachricht pro Chat-Nachricht. Der **Body** ist JSON in UTF-8 mit genau diesen Feldern:

| Feld | JSON-Typ | Java-Typ | Pflicht | Beispiel |
|---|---|---|---|---|
| `id` | String (UUID) | `UUID` | ja | `"0b9f6c2e-5d1a-4c55-9a3e-2f0e7d1c4b11"` |
| `roomId` | String (UUID) | `UUID` | ja | `"3f2b1c4e-0000-0000-0000-000000000001"` |
| `senderId` | String | `String` | ja, nicht leer | `"anna"` |
| `senderName` | String | `String` | ja, nicht leer | `"Anna Muster"` |
| `content` | String | `String` | ja, nicht leer | `"Hallo zusammen"` |
| `sentAt` | String (ISO-8601, UTC) | `Instant` | ja | `"2026-10-02T08:15:30.123456Z"` |

Vollständiges Beispiel:

```json
{"id":"0b9f6c2e-5d1a-4c55-9a3e-2f0e7d1c4b11","roomId":"3f2b1c4e-0000-0000-0000-000000000001","senderId":"anna","senderName":"Anna Muster","content":"Hallo zusammen","sentAt":"2026-10-02T08:15:30.123456Z"}
```

Header, die der chat-service mitschickt: `content_type: application/json`,
`content_encoding: UTF-8`, `__TypeId__: ch.benedict.m321.chatservice.dto.ChatMessage` und
`delivery_mode: 2` (persistent). **Der batch-writer liest nur den Body.** Eine Nachricht, die nur
mit `content_type` in der Queue liegt (Szenario S5), wird genauso verarbeitet.

### 2.2 Woher ich das weiss

| Aussage | Beleg |
|---|---|
| Feldnamen und Typen | `chat-service/.../dto/ChatMessage.java`: `record ChatMessage(UUID id, UUID roomId, String senderId, String senderName, String content, Instant sentAt)` |
| JSON statt Java-Serialisierung | `chat-service/.../config/RabbitConfig.java`: Bean `Jackson2JsonMessageConverter` mit dem `ObjectMapper` von Spring Boot |
| `sentAt` als ISO-Text, nicht als Zahl | Spring Boot schaltet im `ObjectMapper` `WRITE_DATES_AS_TIMESTAMPS` aus; der Kommentar in `RabbitConfig.java` sagt dasselbe |
| Weg in die Queue | `chat-service/.../service/MessagePublisher.java`: `convertAndSend(QueueNames.PERSIST_QUEUE, message)`, also über den Standard-Exchange mit dem Queue-Namen als Routing-Key |
| `id` und `sentAt` vergibt der Server | `chat-service/.../service/MessageService.java`: `UUID.randomUUID()` und `Instant.now()` |
| Pflichtfelder beim Eingang | `chat-service/.../dto/SendMessageRequest.java`: `@NotNull roomId`, `@NotBlank senderId`, `senderName`, `content` |
| Argumente der Queues | `RabbitConfig.java`: `chat.persist` ist `durable` mit `x-dead-letter-exchange: ""` und `x-dead-letter-routing-key: chat.dlq`; `chat.dlq` ist `durable` ohne Argumente |
| Header | `Jackson2JsonMessageConverter` setzt `content_type`, `content_encoding` und `__TypeId__`; `RabbitTemplate` sendet standardmässig persistent |

Nachprüfbar am laufenden Stack: batch-writer anhalten, eine Nachricht senden und sie mit der
Management-API anschauen, ohne sie aus der Queue zu nehmen.

```bash
set -a; . ./.env; set +a
docker compose stop batch-writer
docker run --rm --network chat-net curlimages/curl:8.10.1 -s -X POST \
  -H 'Content-Type: application/json' \
  -d '{"roomId":"3f2b1c4e-0000-0000-0000-000000000001","senderId":"anna","senderName":"Anna Muster","content":"Beleg"}' \
  http://chat-service:8080/messages
docker run --rm --network chat-net curlimages/curl:8.10.1 -s -u "$RABBITMQ_USER:$RABBITMQ_PASSWORD" \
  -H 'Content-Type: application/json' -X POST \
  -d '{"count":1,"ackmode":"ack_requeue_true","encoding":"auto"}' \
  http://rabbitmq:15672/api/queues/%2F/chat.persist/get
docker compose start batch-writer
```

### 2.3 Regeln, die der batch-writer daraus ableitet

- **Nur der Body zählt.** Der Header `__TypeId__` nennt eine Klasse aus dem chat-service. Wer sich
  darauf verlässt, lehnt jede Nachricht ohne diesen Header ab (S5) und bindet sich an einen
  fremden Paketnamen.
- **Unbekannte Felder werden ignoriert.** Ergänzt der chat-service später ein Feld, landet nicht
  plötzlich jede Nachricht in der Dead-Letter-Queue.
- **Gültig** ist eine Nachricht, wenn `id`, `roomId` und `sentAt` vorhanden sind und `senderId`,
  `senderName` und `content` nicht leer sind. Das sind dieselben Regeln wie beim Eingang im
  chat-service. Alles andere ist **unlesbar** und geht in `chat.dlq` (3.2).
- **Eigene Kopie des Records.** Der batch-writer hat seine eigene `ChatMessage`. Der Vertrag ist das
  JSON, nicht eine gemeinsame Klasse. So hat es der chat-service in `ChatMessage.java` begründet:
  ein gemeinsames Modul würde die Dienste aneinanderbinden.

---

## 3. Verhalten

### 3.1 Normalfall

1. **Abholen.** Ein Konsument pro Instanz hängt an `chat.persist`. RabbitMQ schickt ihm bis zu 500
   Nachrichten auf Vorrat (`prefetch = 500`), ohne auf eine Bestätigung zu warten.
2. **Sammeln.** Ein Paket ist fertig, sobald **500 Nachrichten** da sind oder **200 ms** vergangen
   sind, je nachdem, was zuerst eintritt (`PLANUNG.md` 3.6).
3. **Lesen.** Jede Nachricht wird aus dem JSON gelesen und geprüft. Eine unlesbare wird sofort
   abgelehnt (`basicReject`, ohne Requeue) und blockiert den Rest des Pakets nicht.
4. **Schreiben.** Alle lesbaren Nachrichten gehen mit **einem** `JdbcTemplate.batchUpdate` in
   **einer** Transaktion in die Datenbank:
   `INSERT INTO message (...) VALUES (...) ON CONFLICT (id) DO NOTHING`.
   Mit `reWriteBatchedInserts=true` fasst der PostgreSQL-Treiber die Zeilen zu wenigen
   mehrzeiligen `INSERT` zusammen, statt jede Zeile einzeln über die Leitung zu schicken.
5. **Bestätigen.** Erst nach dem `COMMIT` bekommt jede gespeicherte Nachricht ihr `basicAck`.

| Entscheid | Wert | Begründung |
|---|---|---|
| Paketgrösse | 500 | `PLANUNG.md` 3.6 und 4.1: Faktor 500 weniger Transaktionen |
| Zeitlimit | 200 ms | Auch bei wenig Betrieb liegt keine Nachricht länger als etwa 0,4 s in der Queue |
| Prefetch | gleich der Paketgrösse | Ist er kleiner, wird ein Paket nie voll und jedes Paket läuft ins Zeitlimit |
| Bestätigung | `MANUAL`, `basicAck` pro Nachricht nach dem Commit | Die Zeile, die bestätigt, steht sichtbar im Listener hinter dem Schreiben. Einzeln statt gesammelt, weil im selben Paket abgelehnte Nachrichten stecken können. Ein Sammel-ACK (`multiple=true`) auf eine bereits abgelehnte Nummer quittiert RabbitMQ mit einem Kanalfehler |
| Konsumenten pro Instanz | 1 | Skaliert wird über `--scale`. So entspricht die Zahl der Konsumenten an `chat.persist` der Zahl der Instanzen (S6) |

Wichtig für das Verständnis: Im Modus `MANUAL` bestätigt der Listener-Container **nie** selbst,
auch nicht bei einer Exception. Darum endet im Listener jeder Weg mit genau einer Antwort pro
Nachricht: `basicAck`, `basicReject` oder `basicNack`.

### 3.2 Fehlerfälle

#### S3: 1000 Nachrichten über `POST /messages`

**Verhalten:** Die Nachrichten kommen verteilt über einige Sekunden. Der Listener schreibt alle
200 ms ein Paket mit dem, was gerade da ist.
**Begründung:** Das Zeitlimit sorgt dafür, dass ein langsamer Strom nicht auf ein volles Paket
wartet. 1000 Nachrichten stehen damit deutlich unter 60 s in der Tabelle, und die Queue ist leer,
weil jede gespeicherte Nachricht bestätigt ist.

#### S4: Rückstau, batch-writer war gestoppt

**Verhalten:** Beim Start liegen 1000 Nachrichten bereit. RabbitMQ liefert sofort 500 (Prefetch),
das Paket ist voll und wird geschrieben, danach die zweiten 500. Das sind **2 Transaktionen** für
die Nachrichten, dazu eine Handvoll beim Start (Schema-Skript, Verbindungsprüfungen). Erwartet
sind deutlich unter 20, erlaubt sind 100.
**Begründung:** Genau dafür ist die Queue da: sie puffert, solange niemand abholt, und das
Bündeln macht aus dem Rückstau wenige grosse Schreibvorgänge statt 1000 kleine.

#### S5: Dieselbe Nachricht zweimal, nur mit `content_type`

**Verhalten:** Beide Kopien werden gelesen (nur der Body zählt). Das `INSERT` der zweiten Kopie
trifft auf denselben Primärschlüssel, `ON CONFLICT (id) DO NOTHING` verwirft sie ohne Fehler.
Beide Kopien werden bestätigt. Ergebnis: **genau eine Zeile, nichts in `chat.dlq`**. Das gilt auch,
wenn beide Kopien im selben Paket und damit im selben `INSERT` stecken.
**Begründung:** RabbitMQ garantiert *at-least-once*: nach einem Absturz oder Verbindungsabbruch
wird erneut zugestellt. Eine Dublette ist also ein normaler Betriebsfall und kein Fehler. Darum
gehört sie weder in die DLQ noch darf sie eine zweite Zeile erzeugen (`PLANUNG.md` 3.6).

#### S6: Zwei Instanzen (`--scale batch-writer=2`)

**Verhalten:** Beide Instanzen hängen mit je einem Konsumenten an **derselben** Queue. RabbitMQ
gibt jede Nachricht genau einem von beiden (Competing Consumers). Keine Instanz weiss von der
anderen, und keine muss es wissen.
**Begründung:** Die beiden stören sich nicht, weil (a) der Broker die Verteilung übernimmt, (b)
jede Instanz nur bestätigt, was sie selbst geschrieben hat, und (c) eine erneut zugestellte
Nachricht über `ON CONFLICT` harmlos bleibt, egal welche Instanz sie bekommt. Damit `--scale`
überhaupt funktioniert, hat der Dienst im Compose **keinen** `container_name`.

#### S7: PostgreSQL 15 s weg, 300 Nachrichten kommen an

**Verhalten:** Das Schreiben scheitert mit einem Verbindungsfehler. Der batch-writer wartet
2 s (`BATCH_WRITER_RETRY_DELAY_MS`) und versucht **dasselbe Paket** erneut, so lange, bis die
Datenbank wieder antwortet. In dieser Zeit sind die Nachrichten weder bestätigt noch abgelehnt:
sie liegen sicher als *unacked* bei RabbitMQ. Nichts geht in die DLQ, der Prozess stürzt nicht ab.
Sobald PostgreSQL wieder läuft, gelingt der nächste Versuch, danach folgt das ACK. Ein Versuch
dauert höchstens etwa 5 s (3 s Zeitlimit für eine Verbindung plus 2 s Pause), also stehen alle 300
wenige Sekunden nach dem Neustart von PostgreSQL in der Tabelle, weit unter 90 s.
**Begründung:** Ein Datenbankausfall ist kein Fehler der Nachricht. Wer in diesem Fall ablehnt,
verliert Nachrichten oder füllt die DLQ mit einwandfreien Daten. Wer abstürzt, braucht einen
Neustart. Warten ist die einzige Reaktion, die weder Daten noch Betrieb kostet. Fällt die Instanz
während des Wartens aus, stellt RabbitMQ die unbestätigten Nachrichten neu zu.

Als «Datenbank weg» gilt jede `DataAccessException` und jede `TransactionException`, **ausser**
`DataIntegrityViolationException`. Die bedeutet: die Datenbank ist da, lehnt aber die Daten ab
(nächster Fall).

#### Unlesbare Nachricht (Giftnachricht)

Kaputtes JSON, falscher Typ, fehlendes oder leeres Pflichtfeld.
**Verhalten:** `basicReject` ohne Requeue. RabbitMQ legt die Nachricht über die Argumente
`x-dead-letter-exchange` und `x-dead-letter-routing-key` von `chat.persist` unverändert in
`chat.dlq` und hängt den Header `x-death` mit Grund und Herkunft an. Die übrigen Nachrichten des
Pakets werden normal geschrieben.
**Begründung:** Ein zweiter Versuch mit denselben Bytes kann nie gelingen. Ohne DLQ würde die
Nachricht endlos neu zugestellt und die Queue nie leer. Der chat-service hat die
Dead-Letter-Argumente genau für diesen Fall gesetzt, der batch-writer nutzt sie.

#### Datenbank lehnt eine einzelne Zeile ab

Zum Beispiel ein `senderName` mit mehr als 255 Zeichen (der chat-service begrenzt die Länge nicht).
**Verhalten:** Die Transaktion des Pakets wird zurückgerollt, es steht keine einzige Zeile in der
Tabelle. Der batch-writer schreibt dasselbe Paket danach **einmalig Zeile für Zeile**. Nur die
Zeile, die wieder scheitert, wird abgelehnt und landet in `chat.dlq`. Alle anderen werden
gespeichert und bestätigt.
**Begründung:** Sonst würde eine einzige fehlerhafte Zeile 499 einwandfreie Nachrichten mit in die
DLQ reissen. Der Einzelweg kostet bis zu 500 Transaktionen, tritt aber nur in diesem seltenen Fall
auf und nie im Normalbetrieb.

#### Absturz zwischen COMMIT und ACK

**Verhalten:** Die Zeilen stehen in der Datenbank, die Bestätigung ist nie angekommen. RabbitMQ
stellt die Nachrichten erneut zu, das `INSERT` trifft auf die vorhandenen IDs und verwirft sie.
**Begründung:** Das ist der Grund, warum die ID vom chat-service kommt und Primärschlüssel ist:
at-least-once plus idempotentes Schreiben ergibt keine Dubletten, ohne dass wir exactly-once
behaupten müssen.

#### RabbitMQ weg

**Verhalten:** Der Listener-Container baut die Verbindung alle 5 s neu auf. Was unbestätigt war,
gibt RabbitMQ beim Verbindungsabbruch selbst wieder frei.
**Begründung:** Wiederverbinden ist Standardverhalten von Spring AMQP. Mögliche Dubletten fängt
wieder `ON CONFLICT` ab.

#### Unerwarteter Fehler im Code

**Verhalten:** Der Listener fängt ihn ab, schreibt ihn mit Stacktrace ins Log und gibt die
lesbaren Nachrichten des Pakets mit `basicNack` und Requeue an die Queue zurück.
**Begründung:** Im Modus `MANUAL` würden sie sonst unbestätigt liegen bleiben, bis der Prefetch
voll ist, und der Konsument stünde still. Das entspricht dem NACK-Pfad aus `PLANUNG.md` 3.6.

#### Start ohne Datenbank

**Verhalten:** Das Schema-Skript beim Start scheitert, der Prozess beendet sich, Docker startet ihn
neu (`restart: unless-stopped`). Im normalen Start verhindert `depends_on` mit
`condition: service_healthy`, dass es so weit kommt.
**Begründung:** Beim Start gibt es noch nichts zu schützen. Ein klarer Abbruch ist hier
verständlicher als ein halb gestarteter Dienst.

### 3.3 Bewusste Abweichungen von PLANUNG.md

| PLANUNG.md | Umgesetzt | Grund |
|---|---|---|
| 3.6: COMMIT scheitert, dann NACK mit Requeue | Bei Datenbankausfall im selben Thread warten und dasselbe Paket wiederholen | NACK mit Requeue liefert sofort erneut, auch an die andere Instanz. Solange die Datenbank weg ist, entsteht eine Schleife aus Zustellen und Zurückgeben, die nichts gewinnt. Unbestätigt sind die Nachrichten genauso sicher. Der NACK-Pfad bleibt für unerwartete Fehler |
| 3.5: `chat.dlq` nach 3 fehlgeschlagenen Versuchen | Dauerhafte Fehler sofort in die DLQ, vorübergehende nie | Der chat-service legt `chat.persist` als klassische Queue an. Einen Zähler wie `x-delivery-limit` gibt es nur bei Quorum-Queues, und die Argumente müssen in beiden Diensten gleich sein. Ein dritter Versuch mit denselben Daten ändert am Ergebnis nichts |
| 3.7: `room_id` als Fremdschlüssel auf `room` | Kein Fremdschlüssel, keine Tabelle `room` | Räume sind nicht Teil dieser Aufgabe. Ein Fremdschlüssel würde jede Nachricht in einen nicht existierenden Raum in die DLQ schicken |

---

## 4. Datenmodell und Konfiguration

### 4.1 Tabelle `message`

```sql
CREATE TABLE IF NOT EXISTS message (
    id          UUID         PRIMARY KEY,
    room_id     UUID         NOT NULL,
    sender_id   VARCHAR(255) NOT NULL,
    sender_name VARCHAR(255) NOT NULL,
    content     TEXT         NOT NULL,
    sent_at     TIMESTAMPTZ  NOT NULL
);
```

| Spalte | Entscheid | Begründung |
|---|---|---|
| `id` | UUID, Primärschlüssel, **ohne** Default | Kommt vom chat-service. Nur so ist ein wiederholtes `INSERT` gefahrlos (S5, Absturz nach COMMIT). Eine Datenbank-ID würde bei jeder erneuten Zustellung eine neue Zeile erzeugen |
| `room_id` | UUID, kein Fremdschlüssel | Siehe 3.3 |
| `sender_id` | `VARCHAR(255)` | Keycloak-`sub` ist eine UUID (36 Zeichen). 255 lässt Luft und setzt trotzdem eine Grenze |
| `sender_name` | `VARCHAR(255)` | Keycloak erlaubt Benutzernamen bis 255 Zeichen. Denormalisiert, damit der Verlauf lesbar bleibt, wenn ein Konto gelöscht wird (`PLANUNG.md` 3.7) |
| `content` | `TEXT` | Nachrichtenlänge begrenzt der chat-service, nicht die Datenbank |
| `sent_at` | `TIMESTAMPTZ`, **ohne** `DEFAULT now()` | Zeitpunkt des Sendens vom chat-service. Ein Default hielte den Moment des Schreibens fest: alle 500 Nachrichten eines Pakets hätten dieselbe Zeit. `TIMESTAMPTZ` speichert UTC, unabhängig von der Zeitzone des Servers |

Die Spaltennamen folgen `PLANUNG.md` 3.7 und der Regel «Code auf Englisch».

### 4.2 Index

```sql
CREATE INDEX IF NOT EXISTS idx_message_room_sent_at ON message (room_id, sent_at DESC);
```

Passt genau auf die einzige Abfrage des späteren Lesepfads, «die letzten 50 Nachrichten eines
Raums» (`PLANUNG.md` 3.7). Er kostet beim Schreiben etwas Zeit pro Zeile; bei 3 Paketen pro Sekunde
ist das vernachlässigbar.

### 4.3 Wo das Schema entsteht

In `batch-writer/src/main/resources/schema.sql`. Spring Boot führt die Datei **bei jedem Start**
aus (`spring.sql.init.mode: always`). Weil sie nur `IF NOT EXISTS` verwendet, lässt ein zweiter
Lauf bestehende Daten unangetastet. Damit läuft ein frischer Klon mit leerem Volume ohne Handgriff
(S2), und die Tests bekommen mit Testcontainers dasselbe Schema wie der Stack.

Der einzige Schreiber besitzt damit auch das Schema.

| Verworfen | Grund |
|---|---|
| Init-Skript im Postgres-Container (`/docker-entrypoint-initdb.d`) | Läuft nur bei leerem Volume. Die Tests bräuchten eine Kopie davon, und die Tabelle hätte zwei Quellen |
| Flyway | Versionierte Migrationen sind richtig, sobald sich das Schema ändert. Für eine Tabelle ein zusätzliches Werkzeug, das man erklären muss |
| JPA mit `ddl-auto` | Kein JPA im batch-writer (`PLANUNG.md` 2.1). Das Schema wäre im Java-Code versteckt |

### 4.4 Queues

Der batch-writer **deklariert** `chat.persist` und `chat.dlq` beim Start selbst, mit genau denselben
Argumenten wie der chat-service (2.2). Existiert die Queue schon, passiert nichts.

Grund: Im frischen Stack kann der batch-writer vor der ersten Nachricht starten. Der chat-service
deklariert seine Queues erst beim ersten Senden, weil RabbitMQ-Verbindungen in Spring erst dann
aufgebaut werden. Ohne eigene Deklaration hinge der Listener an einer Queue, die es noch nicht gibt.
Die Namen stehen wie im chat-service in einer Klasse `QueueNames`. Weichen die Argumente zwischen
den beiden Diensten ab, lehnt RabbitMQ die zweite Deklaration ab. Das ist gewollt: ein
abweichender Vertrag fällt beim Start auf und nicht erst im Betrieb.

### 4.5 Umgebungsvariablen

| Variable | Standard in `application.yml` | Gesetzt in | Bedeutung |
|---|---|---|---|
| `POSTGRES_HOST` | `localhost` | `docker-compose.yml`: `postgres` | Hostname der Datenbank |
| `POSTGRES_PORT` | `5432` | nicht nötig | Port der Datenbank im Netz |
| `POSTGRES_DB` | `chat` | `.env` | Name der Datenbank |
| `POSTGRES_USER` | `chat` | `.env` | Benutzer |
| `POSTGRES_PASSWORD` | `chat` | `.env` | Passwort |
| `RABBITMQ_HOST` | `localhost` | `docker-compose.yml`: `rabbitmq` | Hostname des Brokers |
| `RABBITMQ_USER` | `guest` | `.env` | Benutzer des Brokers |
| `RABBITMQ_PASSWORD` | `guest` | `.env` | Passwort des Brokers |
| `BATCH_WRITER_BATCH_SIZE` | `500` | `.env` | Höchstens so viele Nachrichten pro Paket, zugleich Prefetch |
| `BATCH_WRITER_BATCH_TIMEOUT_MS` | `200` | `.env` | Spätestens nach dieser Zeit wird ein Paket geschrieben |
| `BATCH_WRITER_RETRY_DELAY_MS` | `2000` | `.env` | Pause zwischen zwei Versuchen, solange die Datenbank fehlt |

Die Standardwerte gelten nur beim Start ausserhalb von Docker. Echte Zugangsdaten stehen nur in
`.env` (in `.gitignore`), im Repository steht `.env.example` mit Beispielwerten.

### 4.6 Feste Werte in `application.yml`

| Wert | Einstellung | Begründung |
|---|---|---|
| Kein Webserver | `spring.main.web-application-type: none` | Keine HTTP-Schnittstelle, also kein Port |
| Prozess bleibt am Leben | `spring.main.keep-alive: true` | Ohne Webserver hält sonst nur der Listener-Thread den Prozess |
| 3 Verbindungen | `hikari.maximum-pool-size` | Ein Listener-Thread schreibt, mehr braucht es nicht |
| 3 s | `hikari.connection-timeout` | Ist die Datenbank weg, scheitert ein Versuch nach 3 s statt nach 30 s (S7) |
| 30 s | Treiber-Option `socketTimeout` | Eine hängende Verbindung blockiert den Listener nie länger als 30 s |
| gebündelte INSERT | Treiber-Option `reWriteBatchedInserts=true` | Ohne sie schickt der Treiber jede Zeile einzeln über die Leitung |

### 4.7 Docker Compose

- Neue Dienste `postgres` (PostgreSQL 16, `PLANUNG.md` 2.1) und `batch-writer`, beide nur im Netz
  `chat-net`.
- **Kein Dienst hat einen `ports:`-Eintrag** (S2).
- `postgres` speichert in einem benannten Volume und hat einen Healthcheck mit `pg_isready`.
- `batch-writer` startet erst, wenn `postgres` und `rabbitmq` gesund sind, und hat
  `restart: unless-stopped`. Ein von Hand gestoppter Dienst bleibt gestoppt (S4).
- `batch-writer` hat **keinen `container_name`**, sonst scheitert `--scale` (S6).
- Das Image baut Maven im Container, ein frischer Klon braucht weder Java noch Maven. Das
  Eltern-POM kennt jetzt zwei Module, darum kopieren beide Dockerfiles beide Modul-POMs.

---

## 5. Abnahmekriterien

Gemessen wird wie bei der Lehrperson: von innen mit `docker compose exec postgres psql` und
`rabbitmqctl`. Vorbereitung im Wurzelverzeichnis:

```bash
cp .env.example .env
set -a; . ./.env; set +a
SQL="docker compose exec -T postgres psql -U $POSTGRES_USER -d $POSTGRES_DB -tAc"
QUEUES="docker compose exec -T rabbitmq rabbitmqctl list_queues -q name messages consumers"
```

`scripts/scenarios.sh` führt S2 bis S8 automatisch in dieser Reihenfolge aus, auf demselben
Stack und ohne Aufräumen dazwischen.

| Nr | Kriterium (messbar) | Befehl |
|---|---|---|
| S1 | Ein Lauf, alle Tests grün, mit echter Queue und Datenbank (Testcontainers) | `mvn clean test` im Wurzelverzeichnis: `BUILD SUCCESS` |
| S2 | Jeder Dienst `running`, kein veröffentlichter Port | `docker compose up -d --build`, dann `docker compose ps`: Spalte STATE überall `running`, Spalte PORTS ohne `->`; `grep -n 'ports:' docker-compose.yml` findet nichts |
| S3 | 1000 Nachrichten per POST: nach höchstens 60 s 1000 Zeilen mehr, `chat.persist` hat 0 Nachrichten | `$SQL "SELECT count(*) FROM message"` vorher und nachher; `$QUEUES` |
| S4 | batch-writer gestoppt, 1000 gesendet, gestartet: 1000 Zeilen mehr, Differenz von `xact_commit` höchstens 100 | `$SQL "SELECT xact_commit FROM pg_stat_database WHERE datname = current_database()"` vor dem Start und nach dem Schreiben, jeweils 11 s warten, weil PostgreSQL seine Statistik verzögert nachführt |
| S5 | Gleiche Nachricht zweimal direkt in `chat.persist`, nur `content_type`: genau 1 Zeile mit dieser ID, `chat.dlq` unverändert | `$SQL "SELECT count(*) FROM message WHERE id = '<id>'"`; `$QUEUES` |
| S6 | `--scale batch-writer=2`: `chat.persist` hat 2 Konsumenten, 1000 Nachrichten ergeben 1000 Zeilen mehr, keine ID doppelt, beide Instanzen haben geschrieben | `$QUEUES`; `$SQL "SELECT count(*) - count(DISTINCT id) FROM message"` ergibt 0; `docker compose logs batch-writer` zeigt `committed in one transaction` bei beiden |
| S7 | Postgres 15 s gestoppt, 300 gesendet: nach höchstens 90 s 300 Zeilen mehr, `chat.dlq` unverändert, kein Neustart der batch-writer | `docker compose stop postgres`, senden, 15 s warten, `docker compose start postgres`; `$SQL` wie S3; `docker inspect -f '{{.RestartCount}} {{.State.StartedAt}}'` vorher und nachher gleich |
| S8 | Keine Streams, Kommentar über jeder Klasse und Methode, `.env` nicht im Repo | `grep -rnE '\.stream\(|java\.util\.stream' batch-writer/src` findet nichts; Test `CodeRulesTest` (läuft in S1) ist grün; `git ls-files .env` gibt nichts aus |
| Z1 | Unlesbare Nachricht landet unverändert in `chat.dlq`, die gültige aus demselben Paket in der Tabelle | Test `PersistFlowIntegrationTest.movesUnreadableMessageToDeadLetterQueue` |
| Z2 | Von der Datenbank abgelehnte Zeile landet allein in `chat.dlq`, die anderen in der Tabelle | Test `PersistFlowIntegrationTest.movesRowRejectedByDatabaseToDeadLetterQueue` |
| Z3 | Tabelle und Index entsprechen `PLANUNG.md` 3.7 | Test `SchemaIntegrationTest`; `$SQL "\d message"` |

---

## 6. Entscheidungen und verworfene Varianten

| Entscheid | Verworfen | Grund |
|---|---|---|
| `JdbcTemplate.batchUpdate` | JPA `saveAll` | `PLANUNG.md` 2.1. JPA schreibt ohne weitere Einstellungen Zeile für Zeile, und das SQL wäre versteckt |
| Bestätigung `MANUAL` | `AUTO` | Bei `AUTO` bestätigt der Container unsichtbar nach der Methode, und einzelne Nachrichten eines Pakets lassen sich nicht ablehnen |
| Unlesbares per `basicReject` in die DLQ | Selbst in `chat.dlq` publizieren | Dafür hat der chat-service die Dead-Letter-Argumente gesetzt. RabbitMQ ergänzt `x-death` mit Grund und Herkunft |
| Einzelweg nach abgelehntem Paket | Ganzes Paket in die DLQ | Eine kaputte Zeile würde 499 gute mitnehmen |
| Im Thread warten bei Datenbankausfall | NACK mit Requeue, Absturz | Siehe 3.3 und S7 |
| Queues selbst deklarieren | Auf den chat-service verlassen | Im frischen Stack existiert `chat.persist` sonst eventuell noch nicht |
| Body selbst lesen | `Jackson2JsonMessageConverter` mit `__TypeId__` | S5 schickt keinen `__TypeId__`, und der Header nennt eine fremde Klasse |
| Eigene Kopie von `ChatMessage` | Gemeinsames Modul | Bindet die Dienste aneinander (so im chat-service begründet) |
| Ein Konsument pro Instanz | Mehrere Threads pro Instanz | Skalierung über `--scale` ist im Betrieb sichtbar und entspricht S6 |

---

## 7. Offene Punkte

| # | Punkt | Möglicher Weg |
|---|---|---|
| 1 | 500 Nachrichten und 200 ms sind Startwerte, nicht gemessen | Mit dem load-generator (`PLANUNG.md` Schritt 5) Queue-Tiefe und Schreibdauer gegeneinander messen |
| 2 | Die Tabelle wächst um etwa 1,2 GB pro Stunde (`PLANUNG.md` 7.2) | Partitionierung nach Tag oder ein Aufräum-Job, beides ausserhalb dieser Aufgabe |
| 3 | Der chat-service begrenzt die Länge von `senderName` nicht. Zu lange Namen landen erst hier in der DLQ | `@Size(max = 255)` in `SendMessageRequest` des chat-service, dann bekommt der Client sofort 400 |
| 4 | Nachrichten in `chat.dlq` werden nur gesammelt | Anschauen per Management-API; ein Werkzeug zum Wiedereinspielen wäre ein eigener Auftrag |
| 5 | Starten zwei Instanzen **gleichzeitig** auf einer leeren Datenbank, kann `CREATE TABLE IF NOT EXISTS` in einer der beiden scheitern | Diese Instanz wird von `restart: unless-stopped` neu gestartet und findet die Tabelle dann vor. Kommt in keinem Szenario vor |
