#!/usr/bin/env bash
# Stellt die acht Szenarien aus dem Auftrag «batch-writer» nach.
#
# Gemessen wird so wie bei der Lehrperson: von innen mit
# "docker compose exec postgres psql" und "rabbitmqctl". Die Szenarien laufen
# in der Reihenfolge des Auftrags auf demselben Stack, ohne Aufräumen dazwischen.
#
# Aufruf im Wurzelverzeichnis des Repositorys:
#   scripts/scenarios.sh all     S1 bis S8 nacheinander, mit frischem Stack
#   scripts/scenarios.sh fresh   Stack löschen und frisch aufbauen (Teil von S2)
#   scripts/scenarios.sh s3      ein einzelnes Szenario (s1 bis s8), Stack muss laufen
#
# Achtung: "all" und "fresh" löschen das Volume der Datenbank (down -v).
# Voraussetzungen: Docker mit Compose v2. Für S1 zusätzlich Maven und Java 21.
# Läuft auch mit der alten bash 3.2 von macOS.

set -euo pipefail

# Immer vom Wurzelverzeichnis aus arbeiten, egal von wo das Skript startet.
cd "$(dirname "$0")/.."

CURL_IMAGE="curlimages/curl:8.10.1"
NETWORK="chat-net"
ROOM_ID="3f2b1c4e-0000-0000-0000-000000000001"
SUMMARY=""
FAILURES=0

# --- Ausgabe -----------------------------------------------------------------

# Überschrift eines Szenarios.
title() {
    printf '\n=== %s ===\n' "$1"
}

# Hält ein bestandenes Szenario fest. $1 = Nummer, $2 = gemessener Wert.
pass() {
    printf '  BESTANDEN: %s\n' "$2"
    SUMMARY="${SUMMARY}$1 BESTANDEN      $2"$'\n'
}

# Hält ein nicht bestandenes Szenario fest. $1 = Nummer, $2 = gemessener Wert.
fail() {
    printf '  NICHT BESTANDEN: %s\n' "$2"
    SUMMARY="${SUMMARY}$1 NICHT BESTANDEN $2"$'\n'
    FAILURES=$((FAILURES + 1))
}

# --- Messwerkzeuge -------------------------------------------------------------

# Legt .env aus .env.example an, falls sie fehlt, und lädt die Werte.
load_env() {
    if [ ! -f .env ]; then
        cp .env.example .env
        echo "  .env aus .env.example angelegt"
    fi
    set -a
    # shellcheck disable=SC1091
    . ./.env
    set +a
}

# Führt SQL in der Datenbank aus und gibt nur den Wert aus.
sql() {
    docker compose exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc "$1"
}

# Anzahl Zeilen in message. Leer, wenn die Datenbank gerade nicht antwortet.
row_count() {
    sql "SELECT count(*) FROM message" 2>/dev/null || true
}

# Anzahl abgeschlossener Transaktionen der Datenbank, wie in S4 verlangt.
committed_transactions() {
    sql "SELECT xact_commit FROM pg_stat_database WHERE datname = current_database()"
}

# Eine Spalte aus "rabbitmqctl list_queues" für eine Queue. $1 = Queue, $2 = Spalte.
queue_value() {
    local value
    value=$(docker compose exec -T rabbitmq rabbitmqctl list_queues -q name "$2" \
        | awk -v queue="$1" '$1 == queue { print $2 }')
    echo "${value:-0}"
}

# Nachrichten in einer Queue: bereit plus ausgeliefert, aber noch unbestätigt.
queue_messages() {
    queue_value "$1" messages
}

# Anzahl Konsumenten an einer Queue.
queue_consumers() {
    queue_value "$1" consumers
}

# Eine neue UUID in Kleinbuchstaben, auf macOS und Linux.
new_uuid() {
    if command -v uuidgen >/dev/null 2>&1; then
        uuidgen | tr '[:upper:]' '[:lower:]'
    else
        cat /proc/sys/kernel/random/uuid
    fi
}

# Schickt $1 Nachrichten über POST /messages, 20 gleichzeitig, von innen aus
# dem Netz chat-net. Gibt aus, wie viele mit 202 angenommen wurden.
send_messages() {
    local count="$1" text="$2" body
    body="{\"roomId\":\"$ROOM_ID\",\"senderId\":\"skript\",\"senderName\":\"Szenario-Skript\",\"content\":\"$text\"}"
    docker run --rm --network "$NETWORK" "$CURL_IMAGE" \
        --silent --parallel --parallel-max 20 \
        --request POST --header "Content-Type: application/json" --data "$body" \
        --output "/tmp/antwort_#1" --write-out "%{http_code}\n" \
        "http://chat-service:8080/messages?nr=[1-$count]" | grep -c '^202$' || true
}

# Wartet, bis mindestens $1 Zeilen in message stehen, höchstens $2 Sekunden.
wait_for_rows() {
    local target="$1" timeout="$2" start="$SECONDS" now
    while [ $((SECONDS - start)) -lt "$timeout" ]; do
        now=$(row_count)
        if [ -n "$now" ] && [ "$now" -ge "$target" ]; then
            return 0
        fi
        sleep 2
    done
    return 1
}

# Wartet, bis eine Queue genau $2 Konsumenten hat, höchstens $3 Sekunden.
wait_for_consumers() {
    local queue="$1" expected="$2" timeout="$3" start="$SECONDS"
    while [ $((SECONDS - start)) -lt "$timeout" ]; do
        if [ "$(queue_consumers "$queue")" -eq "$expected" ]; then
            return 0
        fi
        sleep 2
    done
    return 1
}

# Antwortet der chat-service? Ein leerer Body ergibt 400, also läuft er.
chat_service_ready() {
    local code
    code=$(docker run --rm --network "$NETWORK" "$CURL_IMAGE" --silent --output /dev/null \
        --write-out "%{http_code}" --request POST --header "Content-Type: application/json" \
        --data '{}' http://chat-service:8080/messages 2>/dev/null || true)
    [ "$code" = "400" ]
}

# Name, Anzahl Neustarts und Startzeit jeder batch-writer-Instanz. Bleibt die
# Ausgabe gleich, ist keine Instanz neu gestartet worden.
batch_writer_states() {
    local id
    for id in $(docker compose ps -q batch-writer); do
        docker inspect -f '{{.Name}} {{.RestartCount}} {{.State.StartedAt}}' "$id"
    done
}

# Zählt die Log-Zeilen eines Containers, in denen ein Paket geschrieben wurde.
commit_log_lines() {
    docker logs "$1" 2>&1 | grep -c "committed in one transaction" || true
}

# --- Szenarien ---------------------------------------------------------------

# S1: alle Tests in einem Lauf, mit echter Queue und echter Datenbank.
s1() {
    title "S1: mvn clean test"
    if ! command -v mvn >/dev/null 2>&1; then
        fail S1 "Maven ist nicht installiert"
        return
    fi
    if mvn -q clean test; then
        pass S1 "alle Tests grün"
    else
        fail S1 "mvn clean test ist rot"
    fi
}

# S2: frischer Stack. Alle Dienste laufen, keiner veröffentlicht einen Port.
s2() {
    title "S2: frischer Stack, kein veröffentlichter Port"
    fresh
    local running missing="" published="" id service
    for service in rabbitmq chat-service postgres batch-writer; do
        running=$(docker compose ps --status running --services)
        if ! printf '%s\n' "$running" | grep -qx "$service"; then
            missing="$missing $service"
        fi
    done
    for id in $(docker compose ps -q); do
        if [ -n "$(docker port "$id")" ]; then
            published="$published $(docker inspect -f '{{.Name}}' "$id")"
        fi
    done
    if grep -qE '^[[:space:]]*ports:' docker-compose.yml; then
        published="$published docker-compose.yml"
    fi
    if [ -z "$missing" ] && [ -z "$published" ]; then
        pass S2 "4 Dienste laufen, kein Port veröffentlicht"
    else
        fail S2 "läuft nicht:${missing:- -}; Port veröffentlicht:${published:- -}"
    fi
}

# Baut den Stack wie aus einem frischen Klon auf und wartet, bis er bereit ist.
fresh() {
    echo "  Stack und Datenbank-Volume löschen, neu bauen und starten ..."
    load_env
    docker compose down -v --remove-orphans
    docker compose up -d --build
    local start="$SECONDS"
    while [ $((SECONDS - start)) -lt 180 ]; do
        if chat_service_ready && docker compose logs batch-writer 2>/dev/null | grep -q "Started BatchWriterApplication"; then
            echo "  Stack bereit nach $((SECONDS - start)) s"
            return 0
        fi
        sleep 3
    done
    echo "  Stack nach 180 s nicht bereit, siehe: docker compose ps, docker compose logs"
}

# S3: 1000 Nachrichten, nach höchstens 60 s alle in der Tabelle, Queue leer.
s3() {
    title "S3: 1000 Nachrichten über POST /messages"
    local before accepted start rows queued
    before=$(row_count)
    start="$SECONDS"
    accepted=$(send_messages 1000 "S3")
    while [ $((SECONDS - start)) -lt 60 ]; do
        rows=$(row_count)
        queued=$(queue_messages chat.persist)
        if [ -n "$rows" ] && [ $((rows - before)) -ge 1000 ] && [ "$queued" -eq 0 ]; then
            break
        fi
        sleep 2
    done
    rows=$(row_count)
    queued=$(queue_messages chat.persist)
    local stored=$((rows - before))
    if [ "$accepted" -eq 1000 ] && [ "$stored" -eq 1000 ] && [ "$queued" -eq 0 ]; then
        pass S3 "1000 angenommen, 1000 gespeichert nach $((SECONDS - start)) s, chat.persist leer"
    else
        fail S3 "$accepted angenommen, $stored gespeichert, chat.persist: $queued"
    fi
}

# S4: Rückstau. Höchstens 100 Transaktionen für 1000 Nachrichten.
s4() {
    title "S4: batch-writer gestoppt, 1000 Nachrichten, dann gestartet"
    local before accepted start transactions_before transactions_after rows
    docker compose stop batch-writer
    before=$(row_count)
    accepted=$(send_messages 1000 "S4")
    start="$SECONDS"
    while [ "$(queue_messages chat.persist)" -lt 1000 ] && [ $((SECONDS - start)) -lt 30 ]; do
        sleep 1
    done
    echo "  11 s warten, bis PostgreSQL seine Statistik nachgeführt hat ..."
    sleep 11
    transactions_before=$(committed_transactions)
    docker compose start batch-writer
    wait_for_rows $((before + 1000)) 120 || true
    echo "  nochmals 11 s warten ..."
    sleep 11
    transactions_after=$(committed_transactions)
    rows=$(row_count)
    local stored=$((rows - before))
    local transactions=$((transactions_after - transactions_before))
    if [ "$accepted" -eq 1000 ] && [ "$stored" -eq 1000 ] && [ "$transactions" -le 100 ]; then
        pass S4 "1000 gespeichert mit $transactions Transaktionen (erlaubt: 100)"
    else
        fail S4 "$accepted angenommen, $stored gespeichert, $transactions Transaktionen"
    fi
}

# S5: dieselbe Nachricht zweimal direkt in chat.persist, nur mit content_type.
s5() {
    title "S5: Duplikat direkt in chat.persist"
    local id sent_at json payload request dead_before copies dead_after routed=0 i
    id=$(new_uuid)
    sent_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
    dead_before=$(queue_messages chat.dlq)
    json="{\"id\":\"$id\",\"roomId\":\"$ROOM_ID\",\"senderId\":\"s5\",\"senderName\":\"Szenario S5\",\"content\":\"Duplikat\",\"sentAt\":\"$sent_at\"}"
    # Base64 erspart das Escapen von JSON in JSON.
    payload=$(printf '%s' "$json" | base64 | tr -d '\n')
    request="{\"properties\":{\"content_type\":\"application/json\"},\"routing_key\":\"chat.persist\",\"payload\":\"$payload\",\"payload_encoding\":\"base64\"}"
    for i in 1 2; do
        if docker run --rm --network "$NETWORK" "$CURL_IMAGE" --silent \
            --user "$RABBITMQ_USER:$RABBITMQ_PASSWORD" --header "Content-Type: application/json" \
            --request POST --data "$request" \
            "http://rabbitmq:15672/api/exchanges/%2F/amq.default/publish" | grep -q '"routed":true'; then
            routed=$((routed + 1))
        fi
        echo "  Kopie $i gesendet"
    done
    local start="$SECONDS"
    while [ $((SECONDS - start)) -lt 30 ]; do
        copies=$(sql "SELECT count(*) FROM message WHERE id = '$id'")
        if [ "$copies" -ge 1 ]; then
            break
        fi
        sleep 2
    done
    # Nochmals warten, damit auch die zweite Kopie sicher verarbeitet ist.
    sleep 5
    copies=$(sql "SELECT count(*) FROM message WHERE id = '$id'")
    dead_after=$(queue_messages chat.dlq)
    if [ "$routed" -eq 2 ] && [ "$copies" -eq 1 ] && [ "$dead_after" -eq "$dead_before" ]; then
        pass S5 "2 Kopien gesendet, genau 1 Zeile, chat.dlq unverändert ($dead_after)"
    else
        fail S5 "$routed Kopien gesendet, $copies Zeilen, chat.dlq von $dead_before auf $dead_after"
    fi
}

# S6: zwei Instanzen an derselben Queue, keine Nachricht doppelt.
s6() {
    title "S6: zwei Instanzen (--scale batch-writer=2)"
    local before accepted rows duplicates consumers id entry lines_before lines_after log_counts=""
    local idle=""
    docker compose up -d --scale batch-writer=2
    if ! wait_for_consumers chat.persist 2 120; then
        echo "  chat.persist hat nach 120 s nicht 2 Konsumenten"
    fi
    consumers=$(queue_consumers chat.persist)
    for id in $(docker compose ps -q batch-writer); do
        log_counts="$log_counts $id=$(commit_log_lines "$id")"
    done
    before=$(row_count)
    accepted=$(send_messages 1000 "S6")
    wait_for_rows $((before + 1000)) 60 || true
    sleep 3
    rows=$(row_count)
    duplicates=$(sql "SELECT count(*) - count(DISTINCT id) FROM message")
    for entry in $log_counts; do
        id="${entry%%=*}"
        lines_before="${entry#*=}"
        lines_after=$(commit_log_lines "$id")
        if [ "$lines_after" -le "$lines_before" ]; then
            idle="$idle $(docker inspect -f '{{.Name}}' "$id")"
        fi
    done
    local stored=$((rows - before))
    if [ "$consumers" -eq 2 ] && [ "$accepted" -eq 1000 ] && [ "$stored" -eq 1000 ] && [ "$duplicates" -eq 0 ]; then
        pass S6 "2 Konsumenten, 1000 gespeichert, 0 doppelt${idle:+ (ohne Paket:$idle)}"
    else
        fail S6 "$consumers Konsumenten, $accepted angenommen, $stored gespeichert, $duplicates doppelt"
    fi
}

# S7: PostgreSQL 15 s weg, 300 Nachrichten, danach alle da, kein Neustart.
s7() {
    title "S7: PostgreSQL gestoppt, 300 Nachrichten, nach 15 s wieder gestartet"
    local before dead_before accepted states_before states_after rows dead_after id not_running=""
    before=$(row_count)
    dead_before=$(queue_messages chat.dlq)
    states_before=$(batch_writer_states)
    docker compose stop postgres
    accepted=$(send_messages 300 "S7")
    echo "  $accepted angenommen, PostgreSQL bleibt 15 s weg ..."
    sleep 15
    docker compose start postgres
    local start="$SECONDS"
    wait_for_rows $((before + 300)) 90 || true
    local elapsed=$((SECONDS - start))
    rows=$(row_count)
    dead_after=$(queue_messages chat.dlq)
    states_after=$(batch_writer_states)
    for id in $(docker compose ps -q batch-writer); do
        if [ "$(docker inspect -f '{{.State.Status}}' "$id")" != "running" ]; then
            not_running="$not_running $id"
        fi
    done
    local stored=$((${rows:-0} - before))
    if [ "$accepted" -eq 300 ] && [ "$stored" -eq 300 ] && [ "$dead_after" -eq "$dead_before" ] \
        && [ "$states_before" = "$states_after" ] && [ -z "$not_running" ]; then
        pass S7 "300 gespeichert $elapsed s nach dem Neustart von PostgreSQL, kein Neustart des batch-writer"
    else
        fail S7 "$accepted angenommen, $stored gespeichert, chat.dlq $dead_before -> $dead_after, Neustart: $([ "$states_before" = "$states_after" ] && echo nein || echo ja)"
    fi
}

# S8: Regeln aus CLAUDE.md im Quelltext, .env nicht im Repository.
s8() {
    title "S8: Quelltext von batch-writer/"
    local problems="" hits
    # Das Wort darf im ganzen Modul nicht vorkommen, auch nicht in Kommentaren.
    hits=$(grep -rniI --exclude-dir=target "stream" batch-writer/ || true)
    if [ -n "$hits" ]; then
        echo "$hits"
        problems="$problems Stream-Treffer;"
    fi
    if [ -n "$(git ls-files .env)" ]; then
        problems="$problems .env ist im Repository;"
    fi
    if ! grep -qx '.env' .gitignore; then
        problems="$problems .env fehlt in .gitignore;"
    fi
    if command -v mvn >/dev/null 2>&1; then
        if ! mvn -q -pl batch-writer test -Dtest=CodeRulesTest -Dsurefire.failIfNoSpecifiedTests=false; then
            problems="$problems Kommentar fehlt (CodeRulesTest);"
        fi
    else
        echo "  Maven fehlt: die Kommentarregel prüft CodeRulesTest in S1"
    fi
    if [ -z "$problems" ]; then
        pass S8 "keine Streams, jede Klasse und Methode kommentiert, .env nicht im Repo"
    else
        fail S8 "$problems"
    fi
}

# --- Ablauf ------------------------------------------------------------------

# Führt die gewählten Szenarien aus und gibt am Ende eine Übersicht aus.
main() {
    local command="${1:-}"
    case "$command" in
        all)
            load_env
            s1
            s2
            s3
            s4
            s5
            s6
            s7
            s8
            ;;
        fresh)
            fresh
            return
            ;;
        s1 | s2 | s3 | s4 | s5 | s6 | s7 | s8)
            load_env
            "$command"
            ;;
        *)
            echo "Aufruf: scripts/scenarios.sh all | fresh | s1 ... s8"
            exit 2
            ;;
    esac
    printf '\n=== Übersicht ===\n%s' "$SUMMARY"
    if [ "$FAILURES" -gt 0 ]; then
        exit 1
    fi
}

main "$@"
