package com.example.animalfetch;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Database access for pictures, using plain JDBC against the {@code pictures} table (see {@code schema.sql}).
 * <p>
 * This class only knows SQL; HTTP and upstream APIs are handled by {@link PictureService}.
 * There is no connection pool: every method opens its own connection from the {@link DataSource}
 * and closes it again, which is enough for this small service.
 */
public class PictureRepository {

    private final DataSource dataSource;

    public PictureRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Saves all pictures in one transaction: either all are stored or none (rollback on any error).
     * {@code RETURNING id, created_at} gives back the values the database generated, so the returned
     * pictures are complete without a second query.
     *
     * @return the saved pictures, in the same order, with {@code id} and {@code createdAt} filled in
     */
    public List<Picture> saveAll(List<Picture> pictures) {
        String sql = "INSERT INTO pictures (animal, content_type, data, source_url) VALUES (?, ?, ?, ?) RETURNING id, created_at";
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                List<Picture> saved = new ArrayList<>();
                for (Picture p : pictures) {
                    statement.setString(1, p.animal().id());
                    statement.setString(2, p.contentType());
                    statement.setBytes(3, p.data());
                    statement.setString(4, p.sourceUrl());
                    try (ResultSet rs = statement.executeQuery()) {
                        rs.next();
                        saved.add(new Picture(rs.getLong("id"), p.animal(), p.contentType(), p.data(), p.sourceUrl(),
                                rs.getTimestamp("created_at").toInstant()));
                    }
                }
                connection.commit();
                return saved;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save pictures", e);
        }
    }

    /**
     * Loads the most recently stored picture of the given animal, including its image bytes.
     * <p>
     * "Most recent" means the highest {@code id}, not the newest {@code created_at}: ids come from a
     * sequence and always increase, while pictures saved in the same transaction share the same
     * timestamp and would tie. The index {@code idx_pictures_animal_latest (animal, id DESC)} makes this a
     * single index lookup.
     *
     * @return the latest picture, or {@link Optional#empty()} if none of this animal is stored yet
     */
    public Optional<Picture> findLatest(Animal animal) {
        String sql = "SELECT id, content_type, data, source_url, created_at FROM pictures WHERE animal = ? ORDER BY id DESC LIMIT 1";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, animal.id());
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Picture(rs.getLong("id"), animal, rs.getString("content_type"),
                        rs.getBytes("data"), rs.getString("source_url"), rs.getTimestamp("created_at").toInstant()));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load latest picture", e);
        }
    }
}
