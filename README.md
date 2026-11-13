# Relay: Chat-App aus M321 (Klasse IT3c)

**Relay** ist das Lernprojekt zum Modul **M321 Verteilte Systeme / Microservices**: eine
Chat-Anwendung aus mehreren Services, die über eine Message Queue miteinander reden und mit
docker-compose gestartet werden.

## Für Lernende: so startest du

1. Dieses Repository **forken** (Button «Fork» oben rechts).
2. Deinen Fork klonen:
   ```bash
   git clone https://github.com/<dein-benutzername>/it3c-m321.git
   cd it3c-m321
   ```
3. Voraussetzungen installieren: **Java 21**, **Maven**, **Docker Desktop**, **Git**.
4. Lokale Umgebungsdatei anlegen und die Werte anpassen:
   ```bash
   cp .env.example .env
   ```
5. Die Planung lesen (siehe unten) — erst verstehen, dann programmieren.

Alle Aufgaben werden in **deinem Fork** gelöst. Das Original-Repository bleibt die Referenz.

## Bauen, testen, starten

```bash
mvn clean test                   # alle Tests, PostgreSQL und RabbitMQ kommen per Testcontainers
docker compose up -d --build     # RabbitMQ, chat-service, PostgreSQL und batch-writer im Netz chat-net
scripts/scenarios.sh all         # Szenarien S1 bis S8 des batch-writer, löscht das Datenbank-Volume
scripts/scenarios.sh live        # Szenarien S1 bis S8 der Live-Zustellung, löscht das Datenbank-Volume
```

Einem Raum live zuhören, von innen aus dem Netz `chat-net`:

```bash
docker run --rm --network chat-net curlimages/curl:8.10.1 -sN \
  "http://chat-service:8080/stream?roomId=3f2b1c4e-0000-0000-0000-000000000001"
```

Kein Dienst veröffentlicht einen Port auf den Host. Der einzige offene Port des Gesamtsystems
gehört später dem Gateway. Nachsehen, was gespeichert ist, geht deshalb von innen:

```bash
docker compose exec postgres psql -U chat -d chat -c "SELECT count(*) FROM message"
docker compose exec rabbitmq rabbitmqctl list_queues name messages consumers
```

## Was gebaut wird

| Baustein | Technologie | Aufgabe | Stand |
|---|---|---|---|
| chat-service | Spring Boot 3, Java 21 | Nimmt Nachrichten per `POST /messages` an, legt sie auf Queue und Fanout-Exchange. Stellt sie per `GET /stream?roomId=…` (Server-Sent Events) sofort allen Clients des Raums zu, über eine eigene Queue pro Instanz, mit Nachholen per `Last-Event-ID` | vorhanden |
| rabbitmq | RabbitMQ 3.13 | Message Queue zwischen den Services | vorhanden |
| batch-writer | Spring Boot 3, Java 21 | Einziger Schreiber in die Datenbank: liest `chat.persist`, schreibt gebündelt (500 Nachrichten oder 200 ms) in einer Transaktion. Fehlt die Datenbank, nach 5 Versuchen zurück in die Queue | vorhanden |
| postgres | PostgreSQL 16 | Speichert den Chat-Verlauf in der Tabelle `message` | vorhanden |
| keycloak | Keycloak | Login (OIDC) | folgt |
| web-gateway | nginx | Einziger nach aussen offener Port | folgt |
| Web-UI | React | Browser-Client | folgt |

Alles unterhalb des Gateways läuft in einem internen Docker-Netzwerk und ist von aussen nicht
erreichbar.

## Dokumente

- [`PLANUNG.md`](PLANUNG.md) — Auftrag, Stack, Architektur, Nachrichtenfluss, Queues, Datenmodell,
  Umsetzungsreihenfolge. Das ist die Grundlage für alles Weitere.
- [`docs/design/2026-08-28-chat-app-planung.html`](docs/design/2026-08-28-chat-app-planung.html)
  — grafische Fassung der Planung, lokal im Browser öffnen.
- [`docs/plan-chat-service.md`](docs/plan-chat-service.md) — Schritt-für-Schritt-Plan, nach dem
  der `chat-service` gebaut wurde. Jeder Schritt mit Test.
- [`docs/spec-batch-writer.md`](docs/spec-batch-writer.md): Spezifikation des `batch-writer`.
  Vertrag mit dem chat-service, Verhalten in jedem Fehlerfall, Datenmodell, Abnahmekriterien.
- [`docs/plan-batch-writer.md`](docs/plan-batch-writer.md): Umsetzungsplan des `batch-writer`,
  ein Task pro Commit, jeder mit Test.
- [`docs/spec-live-zustellung.md`](docs/spec-live-zustellung.md): Spezifikation der
  Live-Zustellung. Vertrag am Exchange und am Stream, Verhalten in jedem Szenario, Queues,
  Abnahmekriterien.
- [`docs/plan-live-zustellung.md`](docs/plan-live-zustellung.md): Umsetzungsplan der
  Live-Zustellung, ein Schritt pro Commit, jeder mit Auftrag an die KI und Test.
- [`CLAUDE.md`](CLAUDE.md) — Codestil-Regeln für dieses Projekt. Gelten auch für dich.
- [`docs/flipchart-chat-app.png`](docs/flipchart-chat-app.png) — das Flipchart aus der Lektion,
  von dem die Planung ausgeht.

## Codestil, kurz

Der Massstab ist: **kann eine lernende Person jede Zeile vorlesen und sagen, was sie tut?**

- Eine Anweisung pro Zeile, Zwischenresultate in benannte Variablen.
- `for`-Schleife statt Stream, `if` statt verschachteltem Ternary.
- Sprechende Namen in ganzen Wörtern.
- Über jeder Methode ein bis zwei Sätze: was sie tut und warum es sie gibt.
- Kommentare auf Deutsch, als Erklärung an eine Mitlernende.

Die vollständigen Regeln stehen in [`CLAUDE.md`](CLAUDE.md).
