package com.example.animalfetch;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The app's logic: downloads pictures from the upstream APIs and stores them via {@link PictureRepository}.
 * <p>
 * Download rules: 10 s connect timeout, 30 s per request, redirects are followed, and a response only
 * counts as a picture if it is HTTP 2xx with an {@code image/*} content type. Any failure becomes an
 * {@link ApiException} with status 502 (Bad Gateway), because the problem is upstream, not in the request.
 */
public class PictureService {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final PictureRepository repository;

    public PictureService(PictureRepository repository) {
        this.repository = repository;
    }

    /**
     * Downloads {@code count} pictures one after another, then saves them in one transaction.
     * All-or-nothing: if any download fails, nothing is saved and a 502 is raised.
     */
    public List<Picture> fetchAndSave(Animal animal, int count) {
        List<Picture> pictures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            pictures.add(download(animal, animal.randomPictureUrl(ThreadLocalRandom.current())));
        }
        return repository.saveAll(pictures);
    }

    /**
     * Latest stored picture of the animal. Just delegates to the repository, so the Router only
     * depends on this class (and tests only need to mock this one).
     */
    public Optional<Picture> latest(Animal animal) {
        return repository.findLatest(animal);
    }

    /**
     * Downloads one picture and returns it unsaved. The content type is stored without parameters
     * (e.g. {@code "image/jpeg; charset=..."} becomes {@code "image/jpeg"}).
     *
     * @throws ApiException 502 on network errors, non-2xx responses or non-image content
     */
    private Picture download(Animal animal, URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "animal-fetch/1.0")
                .build();
        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new ApiException(502, "Failed to download " + uri + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(502, "Interrupted while downloading " + uri);
        }
        if (response.statusCode() / 100 != 2) {
            throw new ApiException(502, "Upstream " + uri + " responded with HTTP " + response.statusCode());
        }
        String contentType = response.headers().firstValue("Content-Type").orElse("").split(";")[0].trim();
        if (!contentType.startsWith("image/")) {
            throw new ApiException(502, "Upstream " + uri + " returned non-image content '" + contentType + "'");
        }
        return new Picture(null, animal, contentType, response.body(), uri.toString(), null);
    }
}
