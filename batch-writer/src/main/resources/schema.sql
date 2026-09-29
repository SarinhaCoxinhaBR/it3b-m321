-- Wird bei jedem Start des batch-writer ausgeführt (spring.sql.init.mode: always).
-- Nur IF NOT EXISTS: ein zweiter Start lässt Tabelle und Daten unangetastet.
-- Begründung jeder Spalte: docs/spec-batch-writer.md, Abschnitt 4.1.

-- id und sent_at vergibt der chat-service. Darum hier bewusst KEIN Default:
-- nur mit der fremden id ist ein wiederholtes INSERT gefahrlos (ON CONFLICT),
-- und nur mit dem fremden Zeitstempel stimmt die Sendezeit.
CREATE TABLE IF NOT EXISTS message (
    id          UUID         PRIMARY KEY,
    room_id     UUID         NOT NULL,
    sender_id   VARCHAR(255) NOT NULL,
    sender_name VARCHAR(255) NOT NULL,
    content     TEXT         NOT NULL,
    sent_at     TIMESTAMPTZ  NOT NULL
);

-- Passt genau auf die Abfrage des späteren Lesepfads:
-- die letzten 50 Nachrichten eines Raums, die neuste zuerst.
CREATE INDEX IF NOT EXISTS idx_message_room_sent_at ON message (room_id, sent_at DESC);
