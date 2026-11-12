package com.example.animalfetch;

import java.net.URI;
import java.util.Locale;
import java.util.random.RandomGenerator;

/**
 * Supported animals and where to fetch their pictures:
 * cats from cataas.com (placekitten.com is offline), dogs from place.dog, bears from placebear.com.
 * placebear returns the same image for the same size, so every URL gets a random width and height.
 */
public enum Animal {
    CAT, DOG, BEAR;

    // Range (inclusive, in pixels) for the random width and height of each requested picture.
    static final int MIN_SIZE = 200;
    static final int MAX_SIZE = 600;

    /** Lower-case name as used in URLs, JSON and the database. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Builds the upstream URL for one picture of this animal, with a random width and height
     * between {@link #MIN_SIZE} and {@link #MAX_SIZE}. The generator is passed in so tests can use a fixed seed.
     */
    public URI randomPictureUrl(RandomGenerator random) {
        int width = random.nextInt(MIN_SIZE, MAX_SIZE + 1);
        int height = random.nextInt(MIN_SIZE, MAX_SIZE + 1);
        return switch (this) {
            case CAT -> URI.create("https://cataas.com/cat?width=" + width + "&height=" + height);
            case DOG -> URI.create("https://place.dog/" + width + "/" + height);
            case BEAR -> URI.create("https://placebear.com/" + width + "/" + height);
        };
    }

    /** Parses an animal case-insensitively; unknown values are a 400. */
    public static Animal parse(String value) {
        for (Animal animal : values()) {
            if (animal.name().equalsIgnoreCase(value == null ? "" : value.trim())) {
                return animal;
            }
        }
        throw new ApiException(400, "Unknown animal '" + value + "'. Supported: cat, dog, bear");
    }
}
