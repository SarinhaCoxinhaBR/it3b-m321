# Relay: Umsetzungsplan der Live-Zustellung

**Relay, die Chat-App aus Modul M321 · Klasse IT3b · Bewertung 2**

**Ziel:** Jede Nachricht erscheint sofort bei allen Clients, die im gleichen Raum sind. Jede
Instanz des `chat-service` holt sich ihre Kopie vom Fanout-Exchange `chat.delivery` und schiebt
sie per Server-Sent Events über `GET /stream?roomId=…` an die offenen Verbindungen.

**Architektur:** `listener → service → controller`. Der `DeliveryQueueListener` liest die
eigene Zustell-Queue der Instanz, `RoomSubscriptions` entscheidet pro Raum, wer die Nachricht
bekommt, und hält die letzten Nachrichten für das Nachholen. Der `LiveDeliveryController` hält
die Verbindungen offen.

**Tech-Stack:** Java 21, Spring Boot 3.5.16, Spring MVC (`SseEmitter`), Spring AMQP
(`SimpleMessageListenerContainer`), RabbitMQ 3.13, JUnit 5, Testcontainers.

**Spezifikation:** [`spec-live-zustellung.md`](spec-live-zustellung.md).

## So ist dieser Plan entstanden

Die KI (Claude) hat die Schritte am 11.11.2026 in einer Sitzung gebaut. Grundlage waren der
Gesamtauftrag unten und meine Antworten auf ihre Rückfragen. Die Aufträge pro Schritt hat die KI
aus dem Gesamtauftrag abgeleitet. Sie stehen hier so, wie sie umgesetzt wurden, und jeder
entspricht genau einem Commit. Geprüft habe ich jeden Schritt danach selbst. Was ich dabei
geändert habe, steht im Feld «Geprüft und korrigiert».

### Gesamtauftrag an die KI (Wortlaut)

> database retry (die ganze zeit wieder zu schreiben es gibt kein limit er versucht immer zu schreiben)
> - beim batchrider, chat narichten die ich in lock file schriebe wenn ein fehler ist. (bricht den befehl wo ich aufgegeben habe)
>
> sei ein professionelles software Entwickler Team. achte das der code keine unnötigen Zeilen hat gut und effizient und professionell ist. kommentiere alles gut und auf english codiere auch auf english. und stelle sicher dass alles so funktioniert wie es funktionieren soll.
>
> Baut den Anzeigeweg der Chat-App: Jede Nachricht soll sofort bei allen ankommen, die im gleichen Raum sind. Die ganze Aufgabe steht im angehängten PDF: Auftrag, die acht Szenarien, die vier Abgaben, das Gespräch und die Bewertung. Lest es zuerst ganz durch.
>
> frage mich was ich möchte bevor du den code schreibst fälle keine eigenen Entscheidungen

> ich möchte das du den code erweiterst und den Anforderungen erfüllst ich möchte das fertige Produkt haben bis wo ich gesagt habe das ich es fertig abgeben kann lese alles im pdf durch und Areal me und allem im zip

### Entscheidungen aus der Rückfrage

| Frage der KI | Meine Entscheidung |
|---|---|
| Sprache der Kommentare (CLAUDE.md verlangt Deutsch) | Neuer Code auf Englisch. Fehlende Kommentare in bestehenden Dateien auf Deutsch, passend zur Datei |
| Woher holt der chat-service verpasste Nachrichten (S7)? | Puffer im Speicher jeder Instanz, 5000 Nachrichten pro Raum |
| Last-Event-ID nicht mehr im Puffer | Antwort `410 Gone`, der Client lädt neu statt still Nachrichten zu verpassen |
| Was passiert mit der 1001. Nachricht einer hängenden Instanz (S6)? | Älteste verwerfen (`x-overflow: drop-head`) |
| Bestätigung der Zustell-Queue | Bestätigen nach dem Senden (`AUTO`), Prefetch 50, `x-max-length` 950. RabbitMQ zählt für die Grenze nur bereite Nachrichten, `rabbitmqctl` zeigt auch die unbestätigten: 950 + 50 = höchstens 1000 |
| `DatabaseRetry` versucht endlos | Nach 5 Versuchen (`BATCH_WRITER_MAX_ATTEMPTS`) `basicNack` mit Requeue, wie PLANUNG.md 3.6 |
| Chattext im Log bei Fehlern (batch-writer) | Entfernen: keine Jackson-Meldung mit Body-Ausschnitt, Treiber mit `logServerErrorDetail=false` |
| Wo kommen die batch-writer-Korrekturen hin? | Als eigene Schritte am Anfang dieses Plans |
| Szenario-Skript | `scripts/scenarios.sh` erweitern, Bewertung 1 bleibt unverändert |
| Lieferung des Codes | Ein Patch pro Schritt, eingespielt mit `git am` |

## Globale Vorgaben

- **Java 21**, Spring Boot **3.5.16** über das Eltern-POM.
- **Code auf Englisch.** Kommentare im neuen Code auf Englisch, Commit-Messages und Dokumente auf
  Deutsch.
- **Regeln aus CLAUDE.md:** ein Ergebnis pro Zeile, `for` statt Streams, Kommentar über jeder
  Klasse und Methode, keine Interfaces mit einer einzigen Implementierung, Lombok für Logger und
  Konstruktor, `record` für Datenklassen.
- **Kein `ports:`-Eintrag** und **kein `container_name`** beim `chat-service`.
- **Kein Chattext im Log**, auch nicht in Fehlermeldungen.
- **Jeder Commit hat genau ein Thema**, eine deutsche Message und die Zeile
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- **Voraussetzung:** Docker läuft. Testcontainers startet für die Tests echte Container.

## Abgrenzung

| Bewusst nicht in diesem Plan | Warum |
|---|---|
| Keycloak, web-gateway, React-Oberfläche, JavaFX-Client | Laut Auftrag nicht Teil von Bewertung 2 |
| Räume anlegen, Mitglieder einladen, Zugriffsrechte pro Raum | Laut Auftrag nicht Teil von Bewertung 2. Wer die Raum-ID kennt, hört mit |
| Verlauf aus der Datenbank laden | Nachholen läuft über den Puffer im Speicher (Entscheidung oben) |
| Begrenzung der Anzahl Räume im Speicher | Offener Punkt der Spezifikation |

## Dateistruktur

```
.env.example                              # CHAT_SERVICE_REPLAY_BUFFER_SIZE, BATCH_WRITER_MAX_ATTEMPTS
docker-compose.yml                        # die zwei neuen Variablen, kein ports, kein container_name
README.md                                 # Stand, Befehle, Dokumente
scripts/scenarios.sh                      # live, live-s3 bis live-s8
batch-writer/
├── src/main/java/.../service/DatabaseRetry.java        # Limit, danach Exception an den Aufrufer
├── src/main/java/.../service/MessageParser.java        # Grund ohne Body-Ausschnitt
├── src/main/java/.../listener/PersistQueueListener.java # Datenbank weg: basicNack mit Requeue
└── src/main/resources/application.yml                  # max-attempts, logServerErrorDetail
chat-service/
├── src/main/java/ch/benedict/m321/chatservice/
│   ├── ChatServiceApplication.java       # @EnableScheduling für das Lebenszeichen
│   ├── config/QueueNames.java            # DELIVERY_QUEUE_PREFIX
│   ├── config/RabbitConfig.java          # Zustell-Queue, Binding, Listener-Container
│   ├── controller/LiveDeliveryController.java  # GET /stream
│   ├── dto/LiveMessage.java              # id, roomId, JSON unverändert
│   ├── listener/DeliveryQueueListener.java     # Queue lesen, an die Räume geben
│   └── service/RoomSubscriptions.java    # Clients pro Raum, Puffer, Lebenszeichen
├── src/main/resources/application.yml    # instance-id, replay-buffer-size
└── src/test/java/ch/benedict/m321/chatservice/
    ├── TestcontainersConfiguration.java  # ein RabbitMQ für alle Spring-Tests
    ├── CodeRulesTest.java                # Kommentarregel (S8)
    ├── config/DeliveryQueueLimitIntegrationTest.java   # S6: höchstens 950 bereit
    ├── controller/SseTestClient.java     # echter HTTP-Client für /stream und /messages
    ├── controller/ReconnectIntegrationTest.java        # S7
    ├── controller/TwoInstancesIntegrationTest.java     # S3
    ├── listener/DeliveryQueueListenerTest.java
    └── service/RoomSubscriptionsTest.java
```

## Reihenfolge und Commits

| Nr | Commit-Message | Warum an dieser Stelle |
|---|---|---|
| 0a | `docs: Spezifikation der Live-Zustellung` | Erst beschreiben, was gebaut wird |
| 0b | `docs: Umsetzungsplan der Live-Zustellung` | Dieser Plan, gepusht vor dem ersten Schritt |
| 1 | `fix: batch-writer gibt ein Paket nach fünf Versuchen per NACK zurück` | Korrektur am bestehenden Dienst, unabhängig von allem Neuen |
| 2 | `fix: batch-writer schreibt keinen Chattext mehr ins Log` | Ebenso unabhängig, eigenes Thema |
| 3 | `test: chat-service-Tests teilen sich einen RabbitMQ` | Ab Schritt 5 kommen viele Spring-Tests dazu. Ohne gemeinsamen Broker startet jeder seinen eigenen |
| 4 | `test: Kommentarregel aus CLAUDE.md im chat-service prüfen` | Der chat-service besteht S8 heute nicht. Ab hier prüft jeder Testlauf jeden neuen Code |
| 5 | `feat: eigene Zustell-Queue pro chat-service-Instanz am Fanout` | Ohne Queue am Fanout verwirft RabbitMQ jede Kopie. Alles Weitere liest aus ihr |
| 6 | `feat: Nachrichten pro Raum puffern und an die Clients des Raums senden` | Die Regeln «wer bekommt was» und «was holt ein Client nach» lassen sich ohne Broker und Webserver testen |
| 7 | `feat: Zustell-Queue lesen und an die Räume weitergeben` | Verbindet Queue (5) und Räume (6). Erst jetzt lässt sich die Grenze von S6 am echten Broker prüfen |
| 8 | `feat: GET /stream liefert die Nachrichten eines Raums als Server-Sent Events` | Der Weg nach aussen braucht 5 bis 7. Der S7-Test läuft über echtes HTTP |
| 9 | `test: Verteilung an zwei Instanzen nachstellen (S3)` | Braucht den ganzen Weg aus 5 bis 8, zweimal gestartet |
| 10 | `test: Szenario-Skript um die Live-Zustellung erweitern` | Misst am laufenden Stack, so wie die Lehrperson |
| 11 | `docs: README-Stand für die Live-Zustellung nachführen` | Zum Schluss, wenn feststeht, was vorhanden ist |

---

## Schritt 1: Retry-Limit im batch-writer

**Ziel:** `DatabaseRetry` versucht ein Paket höchstens `BATCH_WRITER_MAX_ATTEMPTS` Mal
(Standard 5). Danach gibt der Listener es mit `basicNack` und Requeue an `chat.persist` zurück.

**Warum an dieser Stelle:** Korrektur am bestehenden Dienst, die nichts Neues voraussetzt. Sie
soll im Log stehen, bevor die Live-Zustellung dazukommt.

**Auftrag an die KI:**
> Begrenze die Versuche in `DatabaseRetry` auf `batch-writer.max-attempts`
> (`BATCH_WRITER_MAX_ATTEMPTS`, Standard 5). Nach dem letzten Versuch geht die Exception an den
> Aufrufer. `PersistQueueListener` fängt `DataAccessException` und `TransactionException` ab,
> loggt eine Warnung ohne Stacktrace und gibt alle Nachrichten des Pakets mit `basicNack` und
> Requeue zurück. Ergänze `.env.example`, `docker-compose.yml`, die betroffenen Stellen in
> `docs/spec-batch-writer.md` und einen Unit-Test für das Limit.

**Test:**
```bash
mvn -q -pl batch-writer test -Dtest=DatabaseRetryTest,DatabaseOutageIntegrationTest
```
Erwartet: grün. `givesUpAfterMaxAttempts` zählt genau 5 Versuche. Der Ausfall-Test läuft durch
den NACK-Pfad, weil die gesperrte Datenbank Verbindungen sofort ablehnt.

**Geprüft und korrigiert:** Tests grün. Nachgeprüft: Nach dem 5. Versuch geht die Exception an den
Aufrufer und `PersistQueueListener` gibt das Paket mit `basicNack` + Requeue an `chat.persist`
zurück, sonst nichts. Variable `BATCH_WRITER_MAX_ATTEMPTS` in `.env.example` und
`docker-compose.yml` ergänzt, nichts geändert am Vorschlag.

---

## Schritt 2: Kein Chattext im Log

**Ziel:** Bei keinem Fehler landet Nachrichtentext im Log des batch-writer.

**Warum an dieser Stelle:** Eigenes Thema, unabhängig von Schritt 1.

**Auftrag an die KI:**
> `MessageParser` soll im Grund nur die Art des Jackson-Fehlers nennen, nicht dessen Meldung,
> denn die zitiert Teile des Bodys. Setze im PostgreSQL-Treiber `logServerErrorDetail=false`,
> damit Fehlermeldungen keine Zeilenwerte enthalten. Schreibe einen Test, der beweist, dass der
> Grund keinen Text aus dem Body enthält.

**Test:**
```bash
mvn -q -pl batch-writer test -Dtest=MessageParserTest
```
Erwartet: grün, auch `reasonQuotesNothingFromTheBody`. Mit dem alten Code wäre dieser Test rot
(`Unrecognized token 'Geheimer'`).

**Geprüft und korrigiert:** Tests grün, auch `reasonQuotesNothingFromTheBody`. Im Log
nachgesehen: `MessageParser` nennt nur die Art des Jackson-Fehlers, und der Treiber läuft mit
`logServerErrorDetail=false` – kein Body-Ausschnitt mehr in Fehlermeldungen. Nichts geändert.

---

## Schritt 3: Ein RabbitMQ für alle Tests des chat-service

**Ziel:** Alle Spring-Tests des chat-service importieren `TestcontainersConfiguration`, wie im
batch-writer.

**Warum an dieser Stelle:** Die Schritte 5 bis 9 bringen weitere Spring-Tests. Mit einem
gemeinsamen Kontext bleibt `mvn clean test` schnell.

**Auftrag an die KI:**
> Lege im chat-service eine `TestcontainersConfiguration` mit einem `RabbitMQContainer`
> (`rabbitmq:3.13-management`, `@ServiceConnection`) an und stelle die drei bestehenden
> Integrationstests darauf um. Am Verhalten der Tests ändert sich nichts.

**Test:**
```bash
mvn -q -pl chat-service test
```
Erwartet: grün, gleiche Tests wie vorher.

**Geprüft und korrigiert:** `mvn -q -pl chat-service test` grün, gleiche Tests wie vorher. Die
drei bestehenden Integrationstests importieren jetzt `TestcontainersConfiguration` und teilen
sich einen RabbitMQ-Container. Verhalten unverändert, nichts korrigiert.

---

## Schritt 4: Kommentarregel im chat-service

**Ziel:** Ein `CodeRulesTest` prüft wie im batch-writer jede Klasse und Methode unter
`chat-service/src` auf einen Javadoc-Kommentar (S8).

**Warum an dieser Stelle:** Heute fehlen 19 Kommentare, also bestünde S8 nicht. Ab hier prüft
jeder Testlauf jeden neuen Code.

**Auftrag an die KI:**
> Übernimm `CodeRulesTest` aus dem batch-writer in den chat-service. Anonyme Klassen sind
> ausgenommen, weil Java über ihnen keinen Kommentar zulässt. Trage alle fehlenden Kommentare
> nach, auf Deutsch wie die bestehenden Dateien.

**Test:**
```bash
mvn -q -pl chat-service test -Dtest=CodeRulesTest
```
Erwartet: grün. Probe: einen Kommentar entfernen, der Test wird rot und nennt die Stelle.

**Geprüft und korrigiert:** `CodeRulesTest` grün. Probe gemacht: einen Kommentar entfernt – Test
wird rot und nennt die Stelle. Fehlende Kommentare in bestehenden Dateien auf Deutsch ergänzt,
passend zur Datei; neuer Code bleibt auf Englisch (Entscheidung oben). Anonyme Klassen sind
ausgenommen, wie im Auftrag beschrieben.

---

## Schritt 5: Zustell-Queue pro Instanz

**Ziel:** Jede Instanz legt `chat.delivery.<Instanz-ID>` an und bindet sie an den Fanout
`chat.delivery`. Die Queue ist `exclusive`, nicht durable, mit `x-max-length` 950 und
`x-overflow` drop-head.

**Warum an dieser Stelle:** Ohne Queue am Fanout verwirft RabbitMQ jede Kopie. Alles Weitere liest
aus dieser Queue.

**Auftrag an die KI:**
> Deklariere in `RabbitConfig` die Zustell-Queue dieser Instanz: Name
> `chat.delivery.` plus `chat-service.instance-id` (`${HOSTNAME:${random.uuid}}`), nicht durable,
> exclusive, `x-max-length` 950, `x-overflow` drop-head, gebunden an `chat.delivery`. Begründe
> 950 im Code: RabbitMQ zählt für die Grenze nur bereite Nachrichten, mit dem Prefetch von 50 sind
> es höchstens 1000. Teste, dass die Queue im Broker existiert und die Argumente stimmen.

**Test:**
```bash
mvn -q -pl chat-service test -Dtest=RabbitConfigIntegrationTest
```
Erwartet: grün, auch `declaresDeliveryQueueOfThisInstance`.

**Geprüft und korrigiert:** `RabbitConfigIntegrationTest` grün, Queue existiert mit den richtigen
Argumenten. Nachgeprüft: `exclusive` + nicht durable, damit die Queue mit der Instanz verschwindet
(S4). Die Begründung 950 bereit + 50 Prefetch = höchstens 1000 steht als Kommentar im Code.
Nichts geändert.

---

## Schritt 6: Räume und Puffer

**Ziel:** `RoomSubscriptions` kennt die Verbindungen pro Raum, sendet jede Nachricht nur an den
Raum und hält pro Raum die letzten 5000 Nachrichten (`CHAT_SERVICE_REPLAY_BUFFER_SIZE`).

**Warum an dieser Stelle:** Die Regeln für S3 (nur der eigene Raum) und S7 (genau das Fehlende)
lassen sich hier ohne Broker und Webserver testen.

**Auftrag an die KI:**
> Baue `RoomSubscriptions`: `subscribe(roomId, lastEventId, client)` und `deliver(message)`,
> beide `synchronized`. Mit Last-Event-ID zuerst alle Nachrichten danach aus dem Puffer, dann
> live, unter derselben Sperre, also ohne Lücke und ohne Doppelte. Unbekannte Last-Event-ID: nicht
> registrieren und `false` zurückgeben. Jedes Ereignis: `id:` mit der Nachrichten-ID, `data:` mit
> dem JSON unverändert. Gescheiterte Clients entfernen, ohne den Text der Nachricht zu loggen.

**Test:**
```bash
mvn -q -pl chat-service test -Dtest=RoomSubscriptionsTest
```
Erwartet: grün. Geprüft werden Raumfilter, das genaue Format auf der Leitung, Nachholen nach
Last-Event-ID, die Grenze des Puffers und das Entfernen kaputter Verbindungen.

**Geprüft und korrigiert:** `RoomSubscriptionsTest` grün. Wichtigster Punkt beim Lesen: das
Nachholen läuft unter derselben Sperre wie das Registrieren, also keine Lücke und keine Doppelten
(S7). Unbekannte Last-Event-ID gibt `false` zurück, der Controller macht daraus 410. Kaputte
Verbindungen werden entfernt, ohne dass Chattext ins Log gelangt. Nichts geändert.

---

## Schritt 7: Zustell-Queue lesen

**Ziel:** Der `DeliveryQueueListener` liest die Zustell-Queue mit einem Konsumenten, Bestätigung
`AUTO` und Prefetch 50 und gibt jede Nachricht an `RoomSubscriptions`.

**Warum an dieser Stelle:** Verbindet Schritt 5 und 6. Erst mit Listener lässt sich die Grenze
von S6 am echten Broker prüfen.

**Auftrag an die KI:**
> Baue `DeliveryQueueListener` als `MessageListener`: id und roomId aus dem JSON lesen, den Body
> unverändert als `LiveMessage` weitergeben, Unlesbares mit einer Warnung ohne Body verwerfen.
> Container in `RabbitConfig`: ein Konsument, `AUTO`, Prefetch 50, `defaultRequeueRejected`
> false. Teste die Grenze: Listener anhalten, 1100 Nachrichten an den Fanout, genau 950 bleiben.

**Test:**
```bash
mvn -q -pl chat-service test -Dtest='DeliveryQueue*Test,ChatServiceApplicationTest'
```
Erwartet: grün.

**Geprüft und korrigiert:** Tests grün. Container-Einstellungen im Code nachgeprüft: ein
Konsument, Bestätigung `AUTO`, Prefetch 50, `defaultRequeueRejected=false`. Der Grenztest
(Listener anhalten, 1100 Nachrichten, genau 950 bleiben bereit) bestätigt die Rechnung aus
Schritt 5. Nichts geändert.

---

## Schritt 8: GET /stream

**Ziel:** `GET /stream?roomId=…` hält die Verbindung offen (`text/event-stream`). Unbekannte
Last-Event-ID ergibt 410. Alle 15 s ein Kommentar als Lebenszeichen, beim Herunterfahren werden
alle Verbindungen geschlossen.

**Warum an dieser Stelle:** Der Weg nach aussen braucht Queue, Listener und Räume.

**Auftrag an die KI:**
> Baue `LiveDeliveryController` mit `GET /stream`, Parameter `roomId`, Header `Last-Event-ID`
> optional. `SseEmitter` ohne Zeitlimit, bei unbekannter ID `410 Gone`. Ergänze in
> `RoomSubscriptions` ein Lebenszeichen alle 15 s und das Schliessen aller Verbindungen bei
> `ContextClosedEvent`. Teste S7 über echtes HTTP: eine Nachricht, trennen, 50 senden, mit
> Last-Event-ID neu verbinden, genau die 50 in Reihenfolge, danach live.

**Test:**
```bash
mvn -q -pl chat-service test -Dtest=ReconnectIntegrationTest
```
Erwartet: grün. Der Test prüft auch den Vertrag: Status 200, `text/event-stream`, `id:` gleich
der ID aus der 202-Antwort, `data:` mit dem JSON der Nachricht.

**Geprüft und korrigiert:** `ReconnectIntegrationTest` grün über echtes HTTP: genau die 50
fehlenden in Reihenfolge, keine Doppelten, danach wieder live. Vertrag im Test gegengelesen –
200, `text/event-stream`, `id:`/`data:` wie gefordert. Lebenszeichen alle 15 s und Schliessen
aller Verbindungen bei `ContextClosedEvent` sind drin. Nichts geändert.

---

## Schritt 9: Zwei Instanzen (S3)

**Ziel:** Ein Test startet den chat-service zweimal in derselben JVM an einem RabbitMQ und
stellt S3 nach.

**Warum an dieser Stelle:** Braucht den ganzen Weg aus Schritt 5 bis 8.

**Auftrag an die KI:**
> Starte die Anwendung zweimal mit eigenem Port und eigener Instanz-ID an einem gemeinsamen
> RabbitMQ-Container. Je ein Client in Raum A pro Instanz, ein dritter in Raum B. Schicke 200
> Nachrichten für Raum A über jede Instanz. Prüfe: beide Clients in Raum A 400, jede genau
> einmal, pro Absender in Reihenfolge, Raum B keine.

**Test:**
```bash
mvn -q -pl chat-service test -Dtest=TwoInstancesIntegrationTest
```
Erwartet: grün.

**Geprüft und korrigiert:** `TwoInstancesIntegrationTest` grün: beide Clients in Raum A erhalten
alle 400 Nachrichten genau einmal, pro Absender in Reihenfolge, Raum B erhält keine. Der Test
startet die Anwendung wirklich zweimal mit eigener Instanz-ID an einem Broker – entspricht S3.
Nichts geändert.

---

## Schritt 10: Szenario-Skript

**Ziel:** `scripts/scenarios.sh live` stellt S1 bis S8 von Bewertung 2 am laufenden Stack nach.

**Warum an dieser Stelle:** Misst den fertigen Stack so wie die Lehrperson.

**Auftrag an die KI:**
> Erweitere `scripts/scenarios.sh` um `live` und `live-s3` bis `live-s8`. SSE-Clients als
> curl-Container im Netz `chat-net`, mit jeder Instanz einzeln über ihren Containernamen
> verbunden. S8 prüft im chat-service gezielt auf Java-Streams. Bewertung 1 bleibt unverändert.
> Das Skript muss mit der bash 3.2 von macOS laufen.

**Test:**
```bash
shellcheck scripts/scenarios.sh
scripts/scenarios.sh live
```
Erwartet: kein Befund, achtmal `BESTANDEN`.

**Geprüft und korrigiert:** Am 13.11.2026 auf dem laufenden Stack gemessen: S1 bis S8 alle
`BESTANDEN` (u. a. S4 Queue nach 2 s weg, S6 Queue-Spitze genau 1000, S7 exakt die 50 fehlenden).
Bewertung-1-Szenarien unverändert gelassen. Hinweis: Auf meinem Windows-Rechner fehlt Git Bash
`/proc/sys/kernel/random/uuid`; ich habe das Skript dort mit einem lokalen `uuidgen`-Shim
ausgeführt. Das Skript selbst ist unverändert und läuft auf Linux/macOS wie vorgesehen.

---

## Schritt 11: README

**Ziel:** Tabelle «Stand», Befehle und Dokumente sind nachgeführt.

**Auftrag an die KI:**
> Führe im README den Stand von chat-service und batch-writer nach, ergänze den Befehl für die
> Live-Szenarien, ein Beispiel zum Mithören eines Raums und die Links auf Spezifikation und Plan.

**Test:**
```bash
for datei in docs/spec-live-zustellung.md docs/plan-live-zustellung.md scripts/scenarios.sh; do
  test -e "$datei" && echo "ok: $datei" || echo "fehlt: $datei"
done
```
Erwartet: dreimal `ok`.

**Geprüft und korrigiert:** README gegen den Ist-Zustand geprüft: Tabelle «Stand», der Befehl für
die Live-Szenarien, das Beispiel zum Mithören eines Raums und die Links auf Spezifikation und
Plan sind nachgeführt. Alle referenzierten Dateien liegen unter `docs/` bzw. `scripts/`.

---

## Änderungen am Plan

Ab dem Push dieses Plans bleibt der Text oben stehen. Jede Änderung kommt hier als eigener
Eintrag dazu, mit Datum und Änderungsgrund, in einem eigenen Commit.

_(noch keine)_

---

## Abschluss-Prüfung

- [ ] `mvn clean test`: alle Tests grün, in einem Lauf
- [ ] `scripts/scenarios.sh live`: S1 bis S8 `BESTANDEN`
- [ ] `grep -nE '^\s*ports:' docker-compose.yml`: keine Treffer
- [ ] `git status --short`: sauber, `.env` taucht nicht auf
- [ ] `git log --oneline`: Spezifikation, Plan und die Schritte 1 bis 11 in dieser Reihenfolge
- [ ] `git tag bewertung-2 && git push origin main bewertung-2`
