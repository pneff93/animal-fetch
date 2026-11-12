# animal-fetch

A small Java 21 microservice that fetches random pictures of **cats, dogs, or bears**, stores them in PostgreSQL, and serves the most recently stored picture for each animal. It has no framework: the HTTP server is the JDK `HttpServer`, downloads use the JDK `HttpClient`, and the database layer is plain JDBC. The only libraries are the PostgreSQL driver and Jackson. On startup the app creates its table from `schema.sql` if it doesn't exist yet.

## Picture sources

| Animal | Source | Note |
|---|---|---|
| Cat  | https://cataas.com | **Replaces https://placekitten.com**, which is offline (HTTP 521). cataas comes from the public-apis list. |
| Dog  | https://place.dog | |
| Bear | https://placebear.com | |

placebear.com returns the same image every time for the same dimensions, so each request asks for a random width and height between 200 and 600 px. This gives a variety of pictures from every provider.

## Prerequisites

- **Docker** with Docker Compose. Needed in both modes, because the PostgreSQL database is shipped as a compose service.
- **JDK 21**, only for the non-containerized run. You don't need Maven installed: the project includes the Maven Wrapper (`./mvnw`, or `mvnw.cmd` on Windows).

## Run containerized

```bash
docker compose up -d
```

This starts PostgreSQL and the app in the background. The app image is rebuilt on every `up`, so code changes are always picked up. Docker caches the build steps, so this is fast when nothing changed. Then open http://localhost:8080 for the UI, or use the API described below. To follow the app logs, run `docker compose logs -f app`.

To stop it, run `docker compose down`. To also delete the stored pictures, run `docker compose down -v`.

## Run non-containerized

The app runs on your machine, and only PostgreSQL runs in Docker:

```bash
docker compose up -d postgres     # starts only the database, on localhost:5432
./mvnw clean package              # compiles, runs the tests, builds target/animal-fetch.jar
java -jar target/animal-fetch.jar
```

Then open http://localhost:8080.

If the containerized app is running, stop it first with `docker compose stop app`, because both use port 8080. If PostgreSQL isn't reachable, the app logs a hint and exits.

## REST API

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/animals/{animal}/pictures?count=N` | Fetches `N` pictures (default 1, minimum 1, no upper limit) and stores them. Returns **201** with JSON metadata. |
| `GET` | `/api/animals/{animal}/pictures/latest` | Returns the **image bytes** of the most recently stored picture for that animal. |
| `GET` | `/` | Simple UI. |

`{animal}` is one of `cat`, `dog`, or `bear` (case-insensitive).

Examples:

**POST: fetch and store pictures**

```bash
# Fetch 3 bear pictures from placebear.com and store them
curl -X POST 'http://localhost:8080/api/animals/bear/pictures?count=3'
```

Response `201 Created`, with metadata only (the image bytes are not included):

```json
[
  {"id": 1, "animal": "bear", "contentType": "image/jpeg", "sizeBytes": 23817,
   "sourceUrl": "https://placebear.com/412/287", "createdAt": "2026-11-12T10:00:00.123Z"},
  ...
]
```

**GET: latest stored picture**

```bash
# Save the latest stored bear picture to a file
curl -o bear.jpg http://localhost:8080/api/animals/bear/pictures/latest

# Same request, but also print the status line and headers
curl -s -D - -o bear.jpg http://localhost:8080/api/animals/bear/pictures/latest
```

Response `200 OK`, with the image itself as the body:

```
HTTP/1.1 200 OK
Date: Thu, 12 Nov 2026 13:33:48 GMT
Content-type: image/jpeg
Content-length: 18561
```

You can also open http://localhost:8080/api/animals/bear/pictures/latest directly in a browser to see the picture. If no picture of that animal is stored yet, the response is `404` with `{"status": 404, "error": "No bear picture stored yet"}`.

How errors behave:
- Errors come back as JSON: `{"status": 400, "error": "..."}`.
- An unknown animal or an invalid `count` returns **400**.
- Asking for the latest picture when none is stored returns **404**.
- If an upstream picture API fails, the request returns **502**.
- Any other path or method returns **404**.

Fetching is **all-or-nothing**. The pictures are downloaded one after another, then saved in a single transaction. If any download fails, nothing is saved. All `N` images are held in memory until the save, so a very large `count` uses a lot of memory.

## Tests

```bash
./mvnw test
```

17 unit tests (JUnit 5 and Mockito) also run as part of `./mvnw clean package` and the Docker build. They need no database and no internet access.

What is tested:
- **`AnimalTest`**: parsing `cat`/`dog`/`bear` (any casing; unknown values give a 400), and the provider URL for each animal with width and height between 200 and 600 px.
- **`RouterTest`**: both endpoints through a real JDK `HttpServer`, with the service mocked. Covers 201 with JSON metadata, `count` defaulting to 1, 400 for an invalid `count` or unknown animal, the latest picture's bytes and content type, 404 when nothing is stored or the route is unknown, 502 for upstream failures, a generic 500 for unexpected errors, and serving the UI.

## Known limitations

- **No upper limit on `count`.** All `N` images are held in memory until they're saved, and they're downloaded one after another, so a large `count` is slow and memory-hungry.
- **No connection pool.** Each database call opens a new connection. That's fine for this load, not for heavy traffic.
- **Fixed port and dev credentials.** The HTTP port is always 8080. The database credentials (`animals`/`animals`) are for local use only.
- **Depends on third-party APIs.** If a picture provider is down, fetching that animal returns 502.
- **No authentication.** Anyone who can reach port 8080 can fetch and read pictures.
