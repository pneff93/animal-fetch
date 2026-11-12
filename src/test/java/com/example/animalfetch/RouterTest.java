package com.example.animalfetch;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests the {@link Router}: routing, input validation, JSON output and error-to-status mapping.
 * <p>
 * Setup: a real JDK HttpServer is started on a random free port with the Router installed, and requests
 * are sent with a real HttpClient. Only {@link PictureService} is mocked (Mockito), so no database or
 * upstream API is needed. This tests the HTTP layer as a client would see it, without the rest.
 */
@ExtendWith(MockitoExtension.class)
class RouterTest {

    // Mockito creates a fake PictureService for each test; each test decides what it returns.
    @Mock
    PictureService service;

    HttpServer server;
    final HttpClient client = HttpClient.newHttpClient();
    String base;

    /** Starts a fresh server before every test. Port 0 means "pick any free port". */
    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new Router(service));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Stops the server after every test, so tests don't affect each other. */
    @AfterEach
    void stop() {
        server.stop(0);
    }

    /** Builds a saved picture with a fixed 3-byte "image" and fixed timestamp, so assertions are predictable. */
    private static Picture picture(long id, Animal animal) {
        return new Picture(id, animal, "image/jpeg", new byte[]{9, 8, 7}, "https://example/" + id,
                Instant.parse("2026-11-12T10:00:00Z"));
    }

    // Small helpers to send a POST / GET to the test server and read the response.

    private HttpResponse<String> post(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path)).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private <T> HttpResponse<T> get(String path, HttpResponse.BodyHandler<T> handler) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(), handler);
    }

    /** Happy path: the service returns 2 pictures, so the response is 201 with their metadata as JSON. */
    @Test
    void fetchReturns201WithMetadata() throws Exception {
        when(service.fetchAndSave(Animal.BEAR, 2)).thenReturn(List.of(picture(1, Animal.BEAR), picture(2, Animal.BEAR)));

        HttpResponse<String> res = post("/api/animals/bear/pictures?count=2");

        assertEquals(201, res.statusCode());
        assertEquals("application/json", res.headers().firstValue("Content-Type").orElseThrow());
        assertTrue(res.body().contains("\"id\":2"), res.body());
        assertTrue(res.body().contains("\"animal\":\"bear\""), res.body());
        assertTrue(res.body().contains("\"sizeBytes\":3"), res.body());
        assertTrue(res.body().contains("\"createdAt\":\"2026-11-12T10:00:00Z\""), res.body());
    }

    /** Without ?count the router asks the service for exactly 1 picture. Also shows the animal is case-insensitive. */
    @Test
    void fetchDefaultsCountToOne() throws Exception {
        when(service.fetchAndSave(Animal.CAT, 1)).thenReturn(List.of(picture(1, Animal.CAT)));

        assertEquals(201, post("/api/animals/CAT/pictures").statusCode());
        verify(service).fetchAndSave(Animal.CAT, 1);
    }

    /**
     * Zero, negative and non-numeric counts are rejected with 400 and a JSON error body.
     * verifyNoInteractions proves the service was never called, so nothing is downloaded for bad input.
     */
    @Test
    void fetchRejectsInvalidCount() throws Exception {
        assertEquals(400, post("/api/animals/dog/pictures?count=0").statusCode());
        assertEquals(400, post("/api/animals/dog/pictures?count=-3").statusCode());
        HttpResponse<String> res = post("/api/animals/dog/pictures?count=abc");
        assertEquals(400, res.statusCode());
        assertTrue(res.body().contains("\"error\""), res.body());
        verifyNoInteractions(service);
    }

    /** An unsupported animal in the path is a 400 with a helpful message. */
    @Test
    void fetchRejectsUnknownAnimal() throws Exception {
        HttpResponse<String> res = post("/api/animals/fox/pictures?count=1");
        assertEquals(400, res.statusCode());
        assertTrue(res.body().contains("Unknown animal"), res.body());
    }

    /** When a picture API fails, the service throws ApiException(502); the router passes status and message through. */
    @Test
    void upstreamFailureReturns502() throws Exception {
        when(service.fetchAndSave(Animal.DOG, 3)).thenThrow(new ApiException(502, "place.dog down"));

        HttpResponse<String> res = post("/api/animals/dog/pictures?count=3");

        assertEquals(502, res.statusCode());
        assertTrue(res.body().contains("place.dog down"), res.body());
    }

    /** Any other exception becomes a generic 500. The internal message ("db exploded") must not reach the client. */
    @Test
    void unexpectedErrorReturns500WithoutDetails() throws Exception {
        when(service.fetchAndSave(Animal.DOG, 1)).thenThrow(new IllegalStateException("db exploded"));

        HttpResponse<String> res = post("/api/animals/dog/pictures");

        assertEquals(500, res.statusCode());
        assertTrue(res.body().contains("Internal server error") && !res.body().contains("exploded"), res.body());
    }

    /** The latest endpoint returns the raw image bytes unchanged, with the stored content type. */
    @Test
    void latestReturnsImageBytes() throws Exception {
        when(service.latest(Animal.DOG)).thenReturn(Optional.of(picture(42, Animal.DOG)));

        HttpResponse<byte[]> res = get("/api/animals/dog/pictures/latest", HttpResponse.BodyHandlers.ofByteArray());

        assertEquals(200, res.statusCode());
        assertEquals("image/jpeg", res.headers().firstValue("Content-Type").orElseThrow());
        assertArrayEquals(new byte[]{9, 8, 7}, res.body());
    }

    /** No picture of that animal stored yet means 404. */
    @Test
    void latestReturns404WhenNothingStored() throws Exception {
        when(service.latest(Animal.CAT)).thenReturn(Optional.empty());

        assertEquals(404, get("/api/animals/cat/pictures/latest", HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    /** Unknown paths and known paths with the wrong method (GET on the POST-only fetch path) both give 404. */
    @Test
    void unknownRouteOrWrongMethodReturns404() throws Exception {
        assertEquals(404, get("/nope", HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(404, get("/api/animals/dog/pictures", HttpResponse.BodyHandlers.ofString()).statusCode());
        verifyNoInteractions(service);
    }

    /** GET / serves the UI page, which is loaded from the classpath (src/main/resources/static/index.html). */
    @Test
    void servesUi() throws Exception {
        HttpResponse<String> res = get("/", HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode());
        assertTrue(res.body().contains("<title>animal-fetch</title>"));
    }

    /**
     * Direct test of the query parsing, without HTTP: no query, an empty value or a missing count
     * means 1; count is found even after other parameters; 0 is a 400.
     */
    @Test
    void parseCount() {
        assertEquals(1, Router.parseCount(null));
        assertEquals(1, Router.parseCount("count="));
        assertEquals(1, Router.parseCount("other=5"));
        assertEquals(250, Router.parseCount("x=1&count=250"));
        assertEquals(400, assertThrows(ApiException.class, () -> Router.parseCount("count=0")).status());
    }
}
