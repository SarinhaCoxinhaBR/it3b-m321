package ch.benedict.m321.batchwriter.listener;

import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;

/**
 * Macht die Testdatenbank für den batch-writer unerreichbar und wieder
 * erreichbar, wie in Szenario S7.
 *
 * Den Container zu stoppen geht im Test nicht: Testcontainers vergäbe beim
 * Neustart einen anderen Port, und die Datasource zeigte ins Leere. Darum
 * sperrt diese Klasse die Datenbank und beendet alle offenen Verbindungen.
 * Für den batch-writer ist das dasselbe wie ein Ausfall: jede neue
 * Verbindung scheitert, jede bestehende ist tot.
 */
class DatabaseOutage {

    private final PostgreSQLContainer<?> postgresContainer;

    /** Übernimmt den laufenden PostgreSQL-Container aus der Testkonfiguration. */
    DatabaseOutage(PostgreSQLContainer<?> postgresContainer) {
        this.postgresContainer = postgresContainer;
    }

    /** Sperrt neue Verbindungen und beendet alle bestehenden. */
    void begin() throws IOException, InterruptedException {
        String database = postgresContainer.getDatabaseName();
        runAsAdministrator("ALTER DATABASE " + database + " ALLOW_CONNECTIONS false");
        runAsAdministrator("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '" + database + "'");
    }

    /** Lässt wieder Verbindungen zu. */
    void end() throws IOException, InterruptedException {
        String database = postgresContainer.getDatabaseName();
        runAsAdministrator("ALTER DATABASE " + database + " ALLOW_CONNECTIONS true");
    }

    /**
     * Führt SQL mit psql im Container aus, verbunden mit der Datenbank
     * "postgres". Mit der gesperrten Datenbank selbst ginge es nicht mehr.
     */
    private void runAsAdministrator(String sql) throws IOException, InterruptedException {
        String user = postgresContainer.getUsername();
        ExecResult result = postgresContainer.execInContainer(
                "psql", "-U", user, "-d", "postgres", "-v", "ON_ERROR_STOP=1", "-c", sql);
        if (result.getExitCode() != 0) {
            throw new IllegalStateException("psql failed: " + result.getStderr());
        }
    }
}
