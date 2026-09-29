package ch.benedict.m321.batchwriter;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prüft gegen einen echten PostgreSQL, dass schema.sql beim Start genau die
 * Tabelle aus PLANUNG.md 3.7 anlegt. Die Spaltennamen sind ein fester Punkt,
 * an dem das Prüfskript der Lehrperson ansetzt.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SchemaIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Spalten in der Reihenfolge aus PLANUNG.md 3.7, jede mit dem Typ aus
     * der Spezifikation. Ein Tippfehler im Namen fiele sonst erst im
     * Prüfskript auf.
     */
    @Test
    void createsMessageTableWithColumnsFromPlanning() {
        String sql = """
                SELECT column_name || ' ' || data_type
                FROM information_schema.columns
                WHERE table_name = 'message'
                ORDER BY ordinal_position
                """;
        List<String> columns = jdbcTemplate.queryForList(sql, String.class);

        List<String> expected = List.of(
                "id uuid",
                "room_id uuid",
                "sender_id character varying",
                "sender_name character varying",
                "content text",
                "sent_at timestamp with time zone");
        assertEquals(expected, columns);
    }

    /**
     * Keine Spalte hat einen Default. Ein DEFAULT now() auf sent_at würde den
     * Moment des Schreibens festhalten statt den des Sendens.
     */
    @Test
    void hasNoColumnDefaults() {
        String sql = """
                SELECT count(*)
                FROM information_schema.columns
                WHERE table_name = 'message' AND column_default IS NOT NULL
                """;
        Integer columnsWithDefault = jdbcTemplate.queryForObject(sql, Integer.class);

        assertEquals(0, columnsWithDefault);
    }

    /**
     * Der Index für die Abfrage «die letzten Nachrichten eines Raums» ist da,
     * mit sent_at absteigend.
     */
    @Test
    void createsIndexForHistoryQuery() {
        String sql = "SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_message_room_sent_at'";
        String definition = jdbcTemplate.queryForObject(sql, String.class);

        assertTrue(definition.contains("(room_id, sent_at DESC)"), definition);
    }
}
