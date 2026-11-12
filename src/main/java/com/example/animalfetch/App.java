package com.example.animalfetch;

import com.sun.net.httpserver.HttpServer;
import org.postgresql.ds.PGSimpleDataSource;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * Entry point: wires the app together and starts the HTTP server on port 8080.
 * <p>
 * Startup steps:
 * <ol>
 *   <li>Configure the PostgreSQL connection from {@code DB_URL}, {@code DB_USER} and {@code DB_PASSWORD}
 *       (defaults match the compose database exposed on localhost).</li>
 *   <li>Create the table from {@code schema.sql} if it doesn't exist yet. If the database is unreachable,
 *       log a hint and exit with code 1 instead of starting a server that can't work.</li>
 *   <li>Build the object graph by hand (no DI framework): Repository, then Service, then Router.</li>
 *   <li>Start the JDK HttpServer. Each request runs on its own virtual thread, so blocking calls
 *       (upstream downloads, JDBC) don't tie up a small thread pool.</li>
 * </ol>
 */
public final class App {

    private static final Logger log = Logger.getLogger(App.class.getName());
    private static final int PORT = 8080;

    private App() {
    }

    public static void main(String[] args) throws IOException {
        String dbUrl = env("DB_URL", "jdbc:postgresql://localhost:5432/animals");
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(dbUrl);
        dataSource.setUser(env("DB_USER", "animals"));
        dataSource.setPassword(env("DB_PASSWORD", "animals"));

        try {
            createSchema(dataSource);
        } catch (SQLException e) {
            log.severe("Cannot connect to database at " + dbUrl + " (" + e.getMessage() + ")."
                    + " Is PostgreSQL running? Start it with: docker compose up -d postgres");
            System.exit(1);
        }

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", new Router(new PictureService(new PictureRepository(dataSource))));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        log.info("animal-fetch listening on http://localhost:" + PORT);
    }

    /**
     * Runs {@code src/main/resources/schema.sql} (packaged in the jar). The script only uses
     * {@code IF NOT EXISTS}, so it is safe to run on every start; the PostgreSQL driver accepts
     * several statements in one {@code execute} call.
     */
    private static void createSchema(PGSimpleDataSource dataSource) throws SQLException, IOException {
        try (InputStream in = App.class.getResourceAsStream("/schema.sql");
             Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            if (in == null) {
                throw new IllegalStateException("schema.sql not found on the classpath");
            }
            statement.execute(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /** Reads an environment variable, falling back to {@code defaultValue} when unset or blank. */
    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
