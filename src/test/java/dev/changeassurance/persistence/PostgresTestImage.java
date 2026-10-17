package dev.changeassurance.persistence;

import org.testcontainers.utility.DockerImageName;

/** Same multi-platform PG17/pgvector image as Compose. No host volumes are attached. */
public final class PostgresTestImage {
    private PostgresTestImage() { }
    public static final DockerImageName IMAGE = DockerImageName.parse(
            "pgvector/pgvector:0.8.7-pg17-bookworm@sha256:ac08538c6f8b9904c33c8224c5e5706dbe760aca29db1d096972b4052c22a75d")
            .asCompatibleSubstituteFor("postgres");
}
