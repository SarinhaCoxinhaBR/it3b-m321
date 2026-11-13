# Spezifikation: Live-Zustellung (Bewertung 2)

Relay, die Chat-App aus Modul M321 · Klasse IT3b

Ziel: Jede Nachricht erscheint sofort bei allen Clients, die im gleichen Raum sind. Der
Speicherweg aus Bewertung 1 (`chat.persist` → batch-writer → PostgreSQL) bleibt unverändert;
hier kommt der Anzeigeweg dazu: Fanout-Exchange → eigene Queue pro Instanz → Server-Sent Events.

Massstab: Eine Mitschülerin könnte die Zustellung allein aus dieser Spezifikation bauen.

## 1 Vertrag

### 1.1 Was am Exchange ankommt (rein)

Am Exchange kommt dasselbe JSON an wie bei `chat.persist`: `id`, `roomId`, `senderId`,
`senderName`, `content`, `sentAt`. Es gibt also kein eigenes Format für die Zustellung.
Der Austausch-Name ist `chat.delivery` (Fanout).

Woher ich das weiss: `MessagePublisher.java` schickt das `ChatMessage`-Record serialisiert an
beide Wege; das PDF nennt als feste Vorgabe „im selben Format wie in chat.persist".

### 1.2 Was am Stream hinausgeht (raus)

`GET /stream?roomId=…` liefert `Content-Type: text/event-stream` (Server-Sent Events). Jedes
Event besteht aus zwei Zeilen: `id:` enthält die Nachrichten-ID, `data:` das unveränderte JSON
wie in 1.1. Die ID braucht man später für das Wiederverbinden (Header `Last-Event-ID`).

Woher ich das weiss: `LiveDeliveryController.java` und `RoomSubscriptions.sendEvent`; die
feste Vorgabe steht im Auftrag-PDF, Abschnitt 02 („Feste Punkte").

## 2 Verhalten

### Normalfall

Client sendet `POST /messages` → Antwort `202` mit der Nachrichten-ID → `MessagePublisher`
schickt das JSON an den Fanout `chat.delivery` → RabbitMQ kopiert es in die Zustell-Queue
jeder Instanz → `DeliveryQueueListener` liest die Queue seiner Instanz → `RoomSubscriptions`
schaut, welcher Raum und welche Clients → `SseEmitter` schickt `id:` + `data:` an die Clients
im Raum. Die Verbindung bleibt offen; alle 15 s geht ein Kommentar als Lebenszeichen.

### Die Fälle aus Abschnitt 02 des Auftrags

- **S1 – Tests:** Ein Lauf von `mvn clean test`, alles grün, mit echtem RabbitMQ
  (Testcontainers). Begründung: Nur ein echter Broker beweist Queue-Argumente und Fanout.
- **S2 – Stack:** Alle Dienste laufen, kein Dienst veröffentlicht einen Port. Begründung:
  Der Auftrag misst von innen im Netz `chat-net`; `chat-service` hat deshalb auch kein
  `container_name` und ist skalierbar.
- **S3 – Zwei Instanzen:** Weil der Exchange ein Fanout ist, bekommt jede Instanz eine eigene
  Kopie in ihrer eigenen Queue – kein Konkurrieren um dieselbe Nachricht. `RoomSubscriptions`
  sendet nur an die Clients des Raums der Nachricht. Ergebnis: beide Clients in Raum A erhalten
  alle 400 Nachrichten genau einmal, pro Absender in Reihenfolge; Raum B erhält keine.
- **S4 – Instanz weg:** Die Zustell-Queue ist `exclusive` und nicht durable. Stirbt die Instanz
  (oder verliert die Heartbeats), räumt RabbitMQ die Queue sofort selbst weg – sonst würden sich
  dort Nachrichten für eine tote Instanz stapeln. `chat.persist` und `chat.dlq` bleiben, weil
  sie durable sind. Nach spätestens 30 s ist nur noch eine Queue am Fanout.
- **S5 – Datenbank weg:** Der Anzeigeweg berührt weder PostgreSQL noch den batch-writer.
  Gestoppte Schreibdienste halten die Zustellung nicht auf: alle 100 Nachrichten erscheinen
  nach spätestens 5 s beim Client. Nach dem Neustart stehen alle 100 in der Tabelle, weil
  `chat.persist` die Nachrichten inzwischen gepuffert hat.
- **S6 – Hängende Instanz:** Eine pausierte Instanz liest ihre Queue nicht mehr. S6 verlangt
  maximal 1000 Nachrichten pro Queue. RabbitMQ zählt bei `x-max-length` nur die bereiten
  Nachrichten, nicht die unbestätigten; mit Prefetch 50 können bis zu 50 unbestätigte
  dazukommen. Deshalb steht `x-max-length` auf 950, damit es insgesamt höchstens 1000 sind.
  `drop-head` bedeutet: Ist die Queue voll, fliegt die älteste Nachricht raus. Der Client an
  der anderen Instanz erhält trotzdem alle 5000; nach dem Fortsetzen stellt die pausierte
  Instanz neue Nachrichten wieder zu.
- **S7 – Wiederverbinden:** Jede Instanz hält pro Raum die letzten 5000 Nachrichten im
  Arbeitsspeicher (`CHAT_SERVICE_REPLAY_BUFFER_SIZE`). Verbindet sich ein Client neu und
  schickt den Header `Last-Event-ID`, bekommt er genau die Nachrichten nach dieser ID aus dem
  Puffer, dann live weiter – ohne Lücke und ohne Doppelte, weil Nachholen und Registrieren
  unter derselben Sperre laufen. Ist die ID zu alt und nicht mehr im Puffer, antwortet der
  Server mit `410 Gone`; der Client muss dann komplett neu laden.
- **S8 – Code-Regeln:** Regeln aus CLAUDE.md: keine Java-Streams, Kommentar über jeder Klasse
  und Methode; `.env` ist nicht im Repository. Geprüft durch `CodeRulesTest` und das
  Szenario-Skript.

## 3 Queues

| Queue | Lebensdauer | Grenzen | Bei voller Queue / Instanz weg |
|---|---|---|---|
| `chat.persist` | durable (Bewertung 1) | unverändert | bleibt liegen, batch-writer holt nach |
| `chat.dlq` | durable (Bewertung 1) | unverändert | bleibt |
| `chat.delivery.<Instanz-ID>` | exclusive, nicht durable | `x-max-length` 950, `x-overflow` drop-head | älteste Nachricht fliegt raus; Queue verschwindet mit der Instanz |

Die Instanz-ID kommt aus `chat-service.instance-id` (`${HOSTNAME:${random.uuid}}`), im
Container also der Hostname – deshalb hat jede skalierte Instanz ihre eigene Queue.
Bestätigung der Zustell-Queue: `AUTO` (nach dem Senden an die Clients), Prefetch 50, ein
Konsument. Begründung der Zahlen: siehe S6 oben und der Kommentar in `RabbitConfig.java`.

## 4 Abnahmekriterien

Messbar, jeder Fall mit dem Befehl, der ihn misst:

| Fall | Befehl | Erwartet |
|---|---|---|
| S1 | `mvn clean test` (Wurzelverzeichnis) | ein Lauf, alles grün |
| S2 | `.env` aus `.env.example`, dann `docker compose up -d --build` | 4 Dienste laufen, kein veröffentlichter Port |
| S3 | `scripts/scenarios.sh live-s3` | `BESTANDEN`: beide Clients 400, jede genau einmal, Reihenfolge pro Absender, Raum B 0 |
| S4 | `scripts/scenarios.sh live-s4` | `BESTANDEN`: vorher 2 Queues mit je 1 Consumer, nach ≤ 30 s nur 1; persist/dlq bleiben |
| S5 | `scripts/scenarios.sh live-s5` | `BESTANDEN`: 100 beim Client nach ≤ 5 s, nach Neustart 100 in der Tabelle |
| S6 | `scripts/scenarios.sh live-s6` | `BESTANDEN`: Queue der pausierten Instanz nie > 1000, anderer Client 5000 |
| S7 | `scripts/scenarios.sh live-s7` | `BESTANDEN`: genau die 50 fehlenden in Reihenfolge, keine doppelt, danach live |
| S8 | `scripts/scenarios.sh live-s8` | `BESTANDEN`: keine Streams, alles kommentiert, `.env` nicht im Repo |

Alle acht auf demselben Stack in dieser Reihenfolge: `scripts/scenarios.sh live`.

## Offene Punkte

- Der Puffer liegt nur im Arbeitsspeicher: Nach einem Neustart der Instanz kann ein Client
  nichts nachholen (410), er lädt neu. Verlauf aus der Datenbank ist bewusst nicht Teil
  von Bewertung 2.
- Die Anzahl Räume im Speicher ist nicht begrenzt (5000 Nachrichten pro Raum); das wäre ein
  Punkt für später.
