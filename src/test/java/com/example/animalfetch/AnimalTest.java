package com.example.animalfetch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link Animal}: parsing the {animal} path segment and building the upstream picture URLs.
 * No network involved, as only the URLs are checked, not fetched.
 */
class AnimalTest {

    // Fixed seed, so the "random" sizes are the same on every run and a failure is reproducible.
    private final Random random = new Random(42);

    /** The URL segment may use any casing and stray whitespace, e.g. /api/animals/DOG/... */
    @Test
    void parsesCaseInsensitively() {
        assertEquals(Animal.CAT, Animal.parse("cat"));
        assertEquals(Animal.DOG, Animal.parse("DOG"));
        assertEquals(Animal.BEAR, Animal.parse(" Bear "));
    }

    /**
     * Anything that is not cat/dog/bear must become a 400 (ApiException), not a 500.
     * Runs once per value: null, "", "fox", and "cats" (close, but not exact).
     */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"fox", "cats"})
    void rejectsUnknownAnimalsWith400(String value) {
        ApiException e = assertThrows(ApiException.class, () -> Animal.parse(value));
        assertEquals(400, e.status());
    }

    /**
     * Each animal must point to its provider with a width/height in the allowed range.
     * Many iterations so the random sizes cover the bounds (200 and 600) and everything between.
     */
    @Test
    void buildsProviderUrlsWithSizesInRange() {
        for (int i = 0; i < 200; i++) {
            assertUrl(Animal.CAT, "^https://cataas\\.com/cat\\?width=(\\d+)&height=(\\d+)$");
            assertUrl(Animal.DOG, "^https://place\\.dog/(\\d+)/(\\d+)$");
            assertUrl(Animal.BEAR, "^https://placebear\\.com/(\\d+)/(\\d+)$");
        }
    }

    /**
     * Checks one generated URL: it must match the provider's format, and both captured numbers
     * (regex groups 1 and 2 = width and height) must be within MIN_SIZE..MAX_SIZE.
     */
    private void assertUrl(Animal animal, String regex) {
        String url = animal.randomPictureUrl(random).toString();
        Matcher m = Pattern.compile(regex).matcher(url);
        assertTrue(m.matches(), "unexpected URL " + url);
        for (int group = 1; group <= 2; group++) {
            int size = Integer.parseInt(m.group(group));
            assertTrue(size >= Animal.MIN_SIZE && size <= Animal.MAX_SIZE, "size out of range in " + url);
        }
    }
}
