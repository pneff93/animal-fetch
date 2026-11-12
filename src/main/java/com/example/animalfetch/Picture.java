package com.example.animalfetch;

import java.time.Instant;

/**
 * A picture of an animal, as downloaded and as stored in the {@code pictures} table.
 *
 * @param id          database id; {@code null} until saved
 * @param animal      which animal it shows
 * @param contentType MIME type reported by the upstream API, e.g. {@code image/jpeg}
 * @param data        the image bytes
 * @param sourceUrl   the upstream URL it was downloaded from
 * @param createdAt   when it was saved (set by the database); {@code null} until saved
 */
public record Picture(Long id, Animal animal, String contentType, byte[] data, String sourceUrl, Instant createdAt) {
}
