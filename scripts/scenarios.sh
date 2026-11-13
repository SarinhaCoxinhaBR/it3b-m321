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
#
# The eight scenarios of assignment 2 «Live-Zustellung» (docs/spec-live-zustellung.md):
#   scripts/scenarios.sh live      S1 to S8 of the live delivery, with a fresh stack
#   scripts/scenarios.sh live-s3   a single one (live-s3 to live-s8), stack must be running
# SSE clients run as curl containers in chat-net; they connect to every
# chat-service instance by its container name, like the teacher's script.

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
# Optional $3 = Instanz (Standard: der Dienstname chat-service) und
# $4 = Raum (Standard: ROOM_ID).
send_messages() {
    local count="$1" text="$2" host="${3:-chat-service}" room="${4:-$ROOM_ID}" body
    body="{\"roomId\":\"$room\",\"senderId\":\"skript\",\"senderName\":\"Szenario-Skript\",\"content\":\"$text\"}"
    docker run --rm --network "$NETWORK" "$CURL_IMAGE" \
        --silent --parallel --parallel-max 20 \
        --request POST --header "Content-Type: application/json" --data "$body" \
        --output "/tmp/antwort_#1" --write-out "%{http_code}\n" \
        "http://$host:8080/messages?nr=[1-$count]" | grep -c '^202$' || true
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
# Optional $1 = eine bestimmte Instanz statt des Dienstnamens.
chat_service_ready() {
    local code host="${1:-chat-service}"
    code=$(docker run --rm --network "$NETWORK" "$CURL_IMAGE" --silent --output /dev/null \
        --write-out "%{http_code}" --request POST --header "Content-Type: application/json" \
        --data '{}' "http://$host:8080/messages" 2>/dev/null || true)
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

# --- Live delivery: tools ----------------------------------------------------

# Names of the running chat-service containers, one per line.
chat_service_instances() {
    local id
    for id in $(docker compose ps -q chat-service); do
        docker inspect -f '{{.Name}}' "$id" | sed 's#^/##'
    done
}

# Name of the n-th chat-service container. $1 = 1, 2, ...
instance() {
    chat_service_instances | sed -n "${1}p"
}

# Scales chat-service to $1 instances and waits until each one answers, at most 120 s.
scale_chat_service() {
    local wanted="$1" start="$SECONDS" ready name
    docker compose up -d --scale chat-service="$wanted" chat-service >/dev/null 2>&1
    while [ $((SECONDS - start)) -lt 120 ]; do
        ready=0
        for name in $(chat_service_instances); do
            if chat_service_ready "$name"; then
                ready=$((ready + 1))
            fi
        done
        if [ "$ready" -eq "$wanted" ]; then
            return 0
        fi
        sleep 3
    done
    echo "  not all $wanted chat-service instances answer after 120 s"
    return 1
}

# Starts an SSE client as a background container that writes the stream to its
# log. $1 = client name, $2 = instance, $3 = room, $4 = Last-Event-ID (optional).
# Waits until the response headers are in, at most 10 s.
open_stream() {
    local client="$1" host="$2" room="$3" last_event_id="${4:-}" start="$SECONDS"
    docker rm -f "$client" >/dev/null 2>&1 || true
    if [ -n "$last_event_id" ]; then
        docker run -d --name "$client" --network "$NETWORK" "$CURL_IMAGE" --silent --no-buffer --include \
            --header "Last-Event-ID: $last_event_id" "http://$host:8080/stream?roomId=$room" >/dev/null
    else
        docker run -d --name "$client" --network "$NETWORK" "$CURL_IMAGE" --silent --no-buffer --include \
            "http://$host:8080/stream?roomId=$room" >/dev/null
    fi
    while [ $((SECONDS - start)) -lt 10 ]; do
        if docker logs "$client" 2>/dev/null | grep -q '^HTTP/'; then
            return 0
        fi
        sleep 1
    done
    echo "  $client: no answer from $host"
}

# Ends an SSE client, like a client that goes away.
close_stream() {
    docker rm -f "$1" >/dev/null 2>&1 || true
}

# HTTP status of the stream of client $1, for example 200 or 410.
stream_status() {
    docker logs "$1" 2>/dev/null | grep -m 1 '^HTTP/' | awk '{ print $2 }' || true
}

# Number of events client $1 has received.
stream_event_count() {
    docker logs "$1" 2>/dev/null | grep -c '^data:' || true
}

# Ids of the events client $1 has received, one per line, in order.
stream_ids() {
    docker logs "$1" 2>/dev/null | grep '^id:' | cut -c4- || true
}

# Message texts of the events client $1 has received, one per line, in order.
stream_contents() {
    docker logs "$1" 2>/dev/null | grep '^data:' | sed -n 's/.*"content":"\([^"]*\)".*/\1/p' || true
}

# Waits until client $1 has at least $2 events, at most $3 seconds.
wait_for_events() {
    local client="$1" expected="$2" timeout="$3" start="$SECONDS"
    while [ $((SECONDS - start)) -lt "$timeout" ]; do
        if [ "$(stream_event_count "$client")" -ge "$expected" ]; then
            return 0
        fi
        sleep 1
    done
    return 1
}

# Sends $2 messages one after another to instance $1 for room $3, with the texts
# "$4-1" to "$4-$2". One after another keeps the order of the sender. A single
# curl runs all requests from a config on stdin. Prints how many got 202.
send_numbered() {
    local host="$1" count="$2" room="$3" prefix="$4" i=1
    while [ "$i" -le "$count" ]; do
        printf 'url = "http://%s:8080/messages"\n' "$host"
        printf 'header = "Content-Type: application/json"\n'
        printf 'data = "{\\"roomId\\":\\"%s\\",\\"senderId\\":\\"%s\\",\\"senderName\\":\\"Szenario-Skript\\",\\"content\\":\\"%s-%d\\"}"\n' \
            "$room" "$prefix" "$prefix" "$i"
        printf 'silent\noutput = "/dev/null"\nwrite-out = "%%{http_code}\\n"\n'
        if [ "$i" -lt "$count" ]; then
            printf 'next\n'
        fi
        i=$((i + 1))
    done | docker run --rm -i --network "$NETWORK" "$CURL_IMAGE" --config - | grep -c '^202$' || true
}

# Are the texts of sender $2 at client $1 exactly "$2-1" to "$2-$3", in this order?
sender_in_order() {
    local received expected
    received=$(stream_contents "$1" | grep "^$2-" | tr '\n' ' ' || true)
    expected=$(seq 1 "$3" | sed "s/^/$2-/" | tr '\n' ' ')
    [ "$received" = "$expected" ]
}

# Queues bound to the fanout exchange chat.delivery, one name per line.
delivery_queues() {
    docker compose exec -T rabbitmq rabbitmqctl list_bindings -q source_name destination_name destination_kind \
        | awk '$1 == "chat.delivery" && $3 == "queue" { print $2 }'
}

# How many queues at chat.delivery have exactly one consumer.
delivery_queues_with_one_consumer() {
    local queue count=0
    for queue in $(delivery_queues); do
        if [ "$(queue_consumers "$queue")" -eq 1 ]; then
            count=$((count + 1))
        fi
    done
    echo "$count"
}

# Reads the length of queue $1 (ready plus unacknowledged) every second and keeps
# the highest value in file $2. Runs in the background until it is stopped.
watch_queue_length() {
    local highest=0 now
    while true; do
        now=$(queue_messages "$1")
        if [ "$now" -gt "$highest" ]; then
            highest="$now"
            echo "$highest" > "$2"
        fi
        sleep 1
    done
}

# --- Live delivery: scenarios ------------------------------------------------

# S3: two instances, a client in room A on each, a third one in room B.
# 200 messages for room A through each instance.
live_s3() {
    title "S3: zwei Instanzen, je 200 Nachrichten für Raum A"
    local one two room_a room_b accepted_one accepted_two count_one count_two count_b
    local ids_one ids_two client order_ok=ja
    scale_chat_service 2 || true
    one=$(instance 1)
    two=$(instance 2)
    room_a=$(new_uuid)
    room_b=$(new_uuid)
    open_stream live-a1 "$one" "$room_a"
    open_stream live-a2 "$two" "$room_a"
    open_stream live-b "$one" "$room_b"
    accepted_one=$(send_numbered "$one" 200 "$room_a" eins)
    accepted_two=$(send_numbered "$two" 200 "$room_a" zwei)
    wait_for_events live-a1 400 30 || true
    wait_for_events live-a2 400 30 || true
    # A duplicate or a message for room B would arrive right behind the others.
    sleep 2
    count_one=$(stream_event_count live-a1)
    count_two=$(stream_event_count live-a2)
    count_b=$(stream_event_count live-b)
    ids_one=$(stream_ids live-a1 | sort -u | wc -l | tr -d ' ')
    ids_two=$(stream_ids live-a2 | sort -u | wc -l | tr -d ' ')
    for client in live-a1 live-a2; do
        if ! sender_in_order "$client" eins 200 || ! sender_in_order "$client" zwei 200; then
            order_ok=nein
        fi
    done
    close_stream live-a1
    close_stream live-a2
    close_stream live-b
    if [ "$accepted_one" -eq 200 ] && [ "$accepted_two" -eq 200 ] && [ "$count_one" -eq 400 ] \
        && [ "$count_two" -eq 400 ] && [ "$ids_one" -eq 400 ] && [ "$ids_two" -eq 400 ] \
        && [ "$order_ok" = ja ] && [ "$count_b" -eq 0 ]; then
        pass S3 "Raum A: beide Clients 400, jede genau einmal, Reihenfolge pro Absender stimmt; Raum B: 0"
    else
        fail S3 "angenommen $accepted_one+$accepted_two, Raum A: $count_one/$count_two ($ids_one/$ids_two verschieden), Reihenfolge: $order_ok, Raum B: $count_b"
    fi
}

# S4: from two instances back to one. Before: exactly two queues at
# chat.delivery with one consumer each. Within 30 s only one is left.
live_s4() {
    title "S4: zwei Instanzen, dann zurück auf eine"
    local before with_consumer after start elapsed kept
    scale_chat_service 2 || true
    before=$(delivery_queues | wc -l | tr -d ' ')
    with_consumer=$(delivery_queues_with_one_consumer)
    start="$SECONDS"
    docker compose up -d --scale chat-service=1 chat-service >/dev/null 2>&1
    after=$(delivery_queues | wc -l | tr -d ' ')
    while [ "$after" -ne 1 ] && [ $((SECONDS - start)) -lt 30 ]; do
        sleep 1
        after=$(delivery_queues | wc -l | tr -d ' ')
    done
    elapsed=$((SECONDS - start))
    kept=$(docker compose exec -T rabbitmq rabbitmqctl list_queues -q name | grep -cxE 'chat\.persist|chat\.dlq' || true)
    if [ "$before" -eq 2 ] && [ "$with_consumer" -eq 2 ] && [ "$after" -eq 1 ] && [ "$elapsed" -le 30 ] && [ "$kept" -eq 2 ]; then
        pass S4 "vorher 2 Queues mit je 1 Consumer, nach $elapsed s nur noch 1; chat.persist und chat.dlq bleiben"
    else
        fail S4 "vorher $before Queues ($with_consumer mit 1 Consumer), nach $elapsed s: $after; chat.persist/chat.dlq vorhanden: $kept"
    fi
}

# S5: batch-writer and postgres stopped, 100 messages. All 100 reach the client
# within 5 s; after both are started again all 100 are in the table.
live_s5() {
    title "S5: batch-writer und postgres gestoppt, 100 Nachrichten"
    local host room before accepted start elapsed delivered rows
    host=$(instance 1)
    room=$(new_uuid)
    before=$(row_count)
    open_stream live-s5 "$host" "$room"
    docker compose stop batch-writer postgres >/dev/null 2>&1
    accepted=$(send_messages 100 "S5" "$host" "$room")
    start="$SECONDS"
    wait_for_events live-s5 100 5 || true
    elapsed=$((SECONDS - start))
    delivered=$(stream_event_count live-s5)
    close_stream live-s5
    docker compose start postgres batch-writer >/dev/null 2>&1
    wait_for_rows $((before + 100)) 120 || true
    rows=$(row_count)
    local stored=$((${rows:-0} - before))
    if [ "$accepted" -eq 100 ] && [ "$delivered" -eq 100 ] && [ "$elapsed" -le 5 ] && [ "$stored" -eq 100 ]; then
        pass S5 "100 beim Client nach $elapsed s, nach dem Neustart 100 in der Tabelle"
    else
        fail S5 "$accepted angenommen, $delivered beim Client nach $elapsed s, $stored in der Tabelle"
    fi
}

# S6: one of two instances paused, 5000 messages through the other one. The
# paused instance's queue never holds more than 1000, the client at the running
# instance gets all 5000, and after resuming the paused instance delivers again.
live_s6() {
    title "S6: eine von zwei Instanzen angehalten, 5000 Nachrichten"
    local paused running room queue watch_file watcher accepted delivered highest start resumed=nein
    scale_chat_service 2 || true
    paused=$(instance 1)
    running=$(instance 2)
    room=$(new_uuid)
    queue="chat.delivery.$(docker inspect -f '{{.Config.Hostname}}' "$paused")"
    open_stream live-s6-running "$running" "$room"
    open_stream live-s6-paused "$paused" "$room"
    watch_file=$(mktemp)
    echo 0 > "$watch_file"
    docker pause "$paused" >/dev/null
    watch_queue_length "$queue" "$watch_file" &
    watcher=$!
    accepted=$(send_messages 5000 "S6" "$running" "$room")
    wait_for_events live-s6-running 5000 120 || true
    delivered=$(stream_event_count live-s6-running)
    kill "$watcher" 2>/dev/null || true
    wait "$watcher" 2>/dev/null || true
    highest=$(cat "$watch_file")
    rm -f "$watch_file"
    docker unpause "$paused" >/dev/null
    # The paused instance may need a few seconds to reconnect to RabbitMQ, so
    # send a new message every 5 s until it reaches the client there.
    start="$SECONDS"
    while [ $((SECONDS - start)) -lt 60 ]; do
        send_numbered "$running" 1 "$room" "nach-fortsetzen" >/dev/null
        if stream_contents live-s6-paused | grep -q '^nach-fortsetzen-1$'; then
            resumed=ja
            break
        fi
        sleep 5
    done
    close_stream live-s6-running
    close_stream live-s6-paused
    if [ "$highest" -le 1000 ] && [ "$accepted" -eq 5000 ] && [ "$delivered" -eq 5000 ] && [ "$resumed" = ja ]; then
        pass S6 "Queue der angehaltenen Instanz höchstens $highest, anderer Client 5000, danach wieder zugestellt"
    else
        fail S6 "Queue höchstens $highest, $accepted angenommen, anderer Client $delivered, wieder zugestellt: $resumed"
    fi
}

# S7: the client disconnects after one message, 50 messages are sent, after 10 s
# it reconnects with Last-Event-ID. It gets exactly the 50 missed ones in order,
# none twice, and then live ones again.
live_s7() {
    title "S7: Neuverbindung mit Last-Event-ID"
    local host room last_id accepted status received expected different
    host=$(instance 1)
    room=$(new_uuid)
    open_stream live-s7 "$host" "$room"
    send_numbered "$host" 1 "$room" vorher >/dev/null
    wait_for_events live-s7 1 10 || true
    last_id=$(stream_ids live-s7 | tail -n 1)
    close_stream live-s7
    accepted=$(send_numbered "$host" 50 "$room" verpasst)
    echo "  10 s warten ..."
    sleep 10
    open_stream live-s7 "$host" "$room" "$last_id"
    wait_for_events live-s7 50 10 || true
    send_numbered "$host" 1 "$room" live >/dev/null
    wait_for_events live-s7 51 10 || true
    sleep 2
    status=$(stream_status live-s7)
    received=$(stream_contents live-s7 | tr '\n' ' ')
    expected="$(seq 1 50 | sed 's/^/verpasst-/' | tr '\n' ' ')live-1 "
    different=$(stream_ids live-s7 | sort -u | wc -l | tr -d ' ')
    close_stream live-s7
    if [ "$status" = "200" ] && [ "$accepted" -eq 50 ] && [ "$received" = "$expected" ] && [ "$different" -eq 51 ]; then
        pass S7 "genau die 50 fehlenden in Reihenfolge, keine doppelt, danach wieder live"
    else
        fail S7 "Status $status, $accepted angenommen, $different verschiedene IDs, erhalten: $received"
    fi
}

# S8: rules from CLAUDE.md in chat-service/, .env not in the repository. The
# word "stream" itself is allowed here: GET /stream and text/event-stream are
# part of the assignment. Forbidden are Java streams.
live_s8() {
    title "S8: Quelltext von chat-service/"
    local problems="" hits
    hits=$(grep -rnE --include='*.java' '\.stream\(|java\.util\.stream' chat-service/src || true)
    if [ -n "$hits" ]; then
        echo "$hits"
        problems="$problems Java-Streams;"
    fi
    if [ -n "$(git ls-files .env)" ]; then
        problems="$problems .env ist im Repository;"
    fi
    if ! grep -qx '.env' .gitignore; then
        problems="$problems .env fehlt in .gitignore;"
    fi
    if command -v mvn >/dev/null 2>&1; then
        if ! mvn -q -pl chat-service test -Dtest=CodeRulesTest -Dsurefire.failIfNoSpecifiedTests=false; then
            problems="$problems Kommentar fehlt (CodeRulesTest);"
        fi
    else
        echo "  Maven fehlt: die Kommentarregel prüft CodeRulesTest in S1"
    fi
    if [ -z "$problems" ]; then
        pass S8 "keine Java-Streams, jede Klasse und Methode kommentiert, .env nicht im Repo"
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
        live)
            # S1 and S2 are the same as in assignment 1: all tests, fresh stack without ports.
            load_env
            s1
            s2
            live_s3
            live_s4
            live_s5
            live_s6
            live_s7
            live_s8
            ;;
        live-s3 | live-s4 | live-s5 | live-s6 | live-s7 | live-s8)
            load_env
            # live-s3 becomes the function name live_s3.
            "${command//-/_}"
            ;;
        *)
            echo "Aufruf: scripts/scenarios.sh all | fresh | s1 ... s8 | live | live-s3 ... live-s8"
            exit 2
            ;;
    esac
    printf '\n=== Übersicht ===\n%s' "$SUMMARY"
    if [ "$FAILURES" -gt 0 ]; then
        exit 1
    fi
}

main "$@"
