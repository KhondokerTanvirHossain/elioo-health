package com.elioo.baymax.storage;

import org.testcontainers.utility.DockerImageName;

/**
 * The container images the acceptance tests run against, in one place and pinned by digest.
 *
 * <p><b>Why a mirror.</b> On 2026-09-25 quay.io began answering {@code unauthorized: access to the requested
 * resource is not authorized} for {@code quay.io/minio/minio}, every tag. The runner proved it was quay and
 * not the network: {@code postgres:17-alpine} and {@code testcontainers/ryuk:0.7.0} pulled fine in the same
 * job. Four acceptance tests — every one that needs MinIO — failed with {@code ContainerFetchException}, and
 * because the image is pinned on {@code main} since PR #5, <b>every merge was blocked, hotfixes included</b>.
 * The images are now mirrored to GHCR, which this repo already uses for the deployable.</p>
 *
 * <p><b>Why the same MinIO version rather than a different S3 fake.</b> These tests are about our S3 adapter,
 * not about MinIO. Swapping the fake would change what they prove, and a test whose meaning changed while
 * nobody was looking is worse than a slow one.</p>
 *
 * <p><b>Why by digest.</b> A tag can be repointed at different bytes; a digest cannot. The digest below is
 * the image that was actually running when these tests last passed, so a mirror that drifted — or a tag
 * someone re-pushed — fails the pull instead of silently testing against something else. The tag is kept
 * beside it for readability only; the digest is what Docker resolves.</p>
 *
 * <p><b>Why one constant and not four.</b> Each of the four MinIO tests declared its own copy of the image
 * string. Four copies of a version are four chances to update three of them.</p>
 */
public final class TestImages {

    /** MinIO, mirrored from {@code quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z}. */
    public static final String MINIO =
            "ghcr.io/khondokertanvirhossain/elioo-health/minio@sha256:"
                    + "14cea493d9a34af32f524e538b8346cf79f3321eff8e708c1e2960462bd8936e";

    /**
     * The same image presented to Testcontainers as a substitute for {@code minio/minio}, which its
     * {@code MinIOContainer} expects by name. Without this the container refuses an unfamiliar image.
     */
    public static DockerImageName minio() {
        return DockerImageName.parse(MINIO).asCompatibleSubstituteFor("minio/minio");
    }

    private TestImages() {
    }
}
