package com.example.animalfetch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The single HTTP handler of the app: it receives every request, decides which route matches,
 * calls {@link PictureService}, and writes the response.
 * <p>
 * The JDK {@code HttpServer} has no routing of its own. {@code App} registers this handler for "/",
 * which means "every path", and this class matches method + path itself:
 * <pre>
 * POST /api/animals/{animal}/pictures?count=N   fetch N pictures and store them (201, JSON metadata)
 * GET  /api/animals/{animal}/pictures/latest    latest stored picture of that animal (image bytes)
 * GET  /                                        simple UI (src/main/resources/static/index.html)
 * </pre>
 * Anything else, including a known path with the wrong method, is a 404.
 * Errors are always returned as JSON: {@code {"status": 400, "error": "..."}}.
 */
public class Router implements HttpHandler {

    private static final Logger log = Logger.getLogger(Router.class.getName());

    // URL patterns. The group ([^/]+) captures the {animal} path segment, e.g. "bear".
    private static final Pattern FETCH = Pattern.compile("^/api/animals/([^/]+)/pictures$");
    private static final Pattern LATEST = Pattern.compile("^/api/animals/([^/]+)/pictures/latest$");
    // Finds "count=<value>" at the start of the query string or after an "&", e.g. "x=1&count=5".
    private static final Pattern COUNT_PARAM = Pattern.compile("(?:^|&)count=([^&]*)");

    /**
     * JSON view of a saved picture, returned by the fetch endpoint. It deliberately leaves out the
     * image bytes (they would make the response huge) and reports their size instead.
     */
    record PictureJson(long id, String animal, String contentType, int sizeBytes, String sourceUrl, Instant createdAt) {
        static PictureJson of(Picture p) {
            return new PictureJson(p.id(), p.animal().id(), p.contentType(), p.data().length, p.sourceUrl(), p.createdAt());
        }
    }

    private final PictureService service;

    // Jackson mapper for JSON responses. The JavaTimeModule plus disabling timestamps writes
    // Instant values as ISO-8601 strings ("2026-11-12T10:00:00Z") instead of numbers.
    private final ObjectMapper json = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public Router(PictureService service) {
        this.service = service;
    }

    /**
     * Entry point the HttpServer calls for every request (on its own virtual thread).
     * Translates exceptions into HTTP error responses, so routes can simply throw.
     */
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // try-with-resources closes the exchange at the end, which completes the response
        // and frees the connection, even if something below fails.
        try (exchange) {
            try {
                route(exchange);
            } catch (ApiException e) {
                // Expected errors (400 bad input, 404 not found, 502 upstream failure):
                // the message is meant for the client.
                sendError(exchange, e.status(), e.getMessage());
            } catch (Exception e) {
                // Anything unexpected (e.g. a database error) is a bug or outage: log the details
                // for us, but don't leak internals to the client.
                log.log(Level.SEVERE, "Unexpected error for " + exchange.getRequestURI(), e);
                sendError(exchange, 500, "Internal server error");
            }
        }
    }

    /** Matches the request against the routes and runs the first one that fits. */
    private void route(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();   // path only, without the "?query" part
        Matcher m;

        if (method.equals("POST") && (m = FETCH.matcher(path)).matches()) {
            // Fetch & save: validate input first (both throw a 400 if invalid), then let the service
            // download and store. The response lists the saved pictures as JSON metadata.
            Animal animal = Animal.parse(m.group(1));
            int count = parseCount(exchange.getRequestURI().getRawQuery());
            List<PictureJson> saved = service.fetchAndSave(animal, count).stream().map(PictureJson::of).toList();
            sendJson(exchange, 201, saved);
        } else if (method.equals("GET") && (m = LATEST.matcher(path)).matches()) {
            // Latest picture: return the raw image bytes with their original content type
            // (e.g. image/jpeg), so a browser or <img src="..."> can display it directly.
            Animal animal = Animal.parse(m.group(1));
            Picture picture = service.latest(animal)
                    .orElseThrow(() -> new ApiException(404, "No " + animal.id() + " picture stored yet"));
            send(exchange, 200, picture.contentType(), picture.data());
        } else if (method.equals("GET") && path.equals("/")) {
            // UI: index.html is packaged inside the jar (from src/main/resources/static/), so it is
            // loaded from the classpath. This works the same with `java -jar` and in the container.
            try (InputStream in = Router.class.getResourceAsStream("/static/index.html")) {
                send(exchange, 200, "text/html; charset=utf-8", in.readAllBytes());
            }
        } else {
            throw new ApiException(404, "No route for " + method + " " + path);
        }
    }

    /**
     * Reads the {@code count} query parameter from the raw query string (e.g. {@code "count=3"}).
     * Missing or empty means 1. Anything that is not an integer >= 1 is a 400. There is no upper limit.
     */
    static int parseCount(String rawQuery) {
        Matcher m = COUNT_PARAM.matcher(rawQuery == null ? "" : rawQuery);
        if (!m.find() || m.group(1).isEmpty()) {
            return 1;
        }
        try {
            int count = Integer.parseInt(m.group(1));
            if (count >= 1) {
                return count;
            }
        } catch (NumberFormatException ignored) {
            // not a number (or too large for int): fall through to the error below
        }
        throw new ApiException(400, "count must be an integer >= 1, was '" + m.group(1) + "'");
    }

    /** Serializes {@code body} to JSON and sends it. */
    private void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        send(exchange, status, "application/json", json.writeValueAsBytes(body));
    }

    /** Sends the standard error body {@code {"status": ..., "error": ...}}. */
    private void sendError(HttpExchange exchange, int status, String message) throws IOException {
        sendJson(exchange, status, Map.of("status", status, "error", message));
    }

    /**
     * Writes a complete response. With the JDK HttpServer the order matters: headers first,
     * then {@code sendResponseHeaders} (status + exact body length), then the body itself.
     */
    private static void send(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
