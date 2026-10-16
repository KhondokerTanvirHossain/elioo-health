package com.elioo.baymax.storage;

import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The S3 server the storage tests run against, in one place and pinned by digest.
 *
 * <p><b>Why this image.</b> MinIO withdrew its public images: {@code quay.io/minio/minio} answers
 * "unauthorized" for every tag since 2026-09-25, and {@code minio/minio} is gone from Docker Hub (404). A
 * mirror pushed to GHCR from an Apple-silicon Mac held only {@code linux/arm64}, which the {@code linux/amd64}
 * CI runner cannot use. {@code bitnamilegacy/minio} is real MinIO, pulls anonymously, and the pinned digest is a
 * multi-arch index with both {@code linux/amd64} and {@code linux/arm64}. All four storage test classes passed
 * against it.
 *
 * <p><b>Known cost.</b> {@code bitnamilegacy} is frozen — Bitnami moved its free images there in 2025 and no
 * longer updates them. That is acceptable for a test fixture that holds synthetic data on a throwaway
 * container. If it disappears, {@code rustfs/rustfs} was verified as the fallback (same digest is served from
 * Docker Hub and quay.io).
 *
 * <p><b>Why by digest.</b> A tag can be repointed at different bytes; a digest cannot.
 */
public final class TestImages {

    /** {@code bitnamilegacy/minio}, multi-arch index digest (linux/amd64 + linux/arm64). */
    public static final String MINIO =
            "docker.io/bitnamilegacy/minio@sha256:6dabb4a2088c9a79908de3bc05f4586c23ad2182c8908e7e3acbf61c1467fb20";

    /**
     * A MinIO container ready for the storage tests.
     *
     * <p>The Bitnami image runs as a non-root user (uid 1001) with no {@code /data} directory, so the default
     * {@code MinIOContainer} command fails with "file access denied". Its writable data directory is
     * {@code /bitnami/minio/data}; the entrypoint passes a {@code server ...} command straight to {@code minio},
     * so MinIOContainer's credentials and health check work unchanged.
     */
    public static MinIOContainer minioContainer() {
        return new MinIOContainer(DockerImageName.parse(MINIO).asCompatibleSubstituteFor("minio/minio"))
                .withCommand("server", "--console-address", ":9001", "/bitnami/minio/data");
    }

    private TestImages() {
    }
}
