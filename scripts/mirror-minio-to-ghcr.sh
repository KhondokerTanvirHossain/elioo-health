#!/usr/bin/env bash
#
# Mirror the MinIO test images to GHCR, pinned by digest.
#
#   scripts/mirror-minio-to-ghcr.sh
#
# WHY THIS EXISTS. quay.io began answering "unauthorized: access to the requested resource is not authorized"
# for quay.io/minio/minio on 2026-09-25, for every tag. The runner confirmed it: postgres:17-alpine and
# testcontainers/ryuk:0.7.0 pull fine from the same job, so Docker Hub is reachable and only quay is
# refusing. Four acceptance tests — every one that needs MinIO — then failed with ContainerFetchException,
# and because the image is pinned on main since PR #5, EVERY merge was blocked, hotfixes included.
#
# Mirroring keeps the same MinIO version rather than swapping the S3 fake: the tests are about our S3
# adapter, not about MinIO, and changing the fake would change what they prove.
#
# BY DIGEST, not by tag. A tag can be repointed at different bytes; a digest cannot. The digests below are
# the images that were actually running when the tests last passed, read from the local Docker cache.
#
# Needs a token with write:packages. The gh CLI token on the dev machine has only
# gist/read:org/repo/workflow, so `docker login` succeeds and the push is denied with
# "permission_denied: The token provided does not match expected scopes" — login proving nothing about
# write access is worth knowing before you trust one.
#
#   export GHCR_TOKEN=<a PAT with write:packages>
#   echo "$GHCR_TOKEN" | docker login ghcr.io -u <your-github-user> --password-stdin
#   scripts/mirror-minio-to-ghcr.sh
#
# Then make the packages PUBLIC in the repo's Packages settings, or CI needs credentials to pull them.
set -euo pipefail

OWNER="${GHCR_OWNER:-khondokertanvirhossain/elioo-health}"

# source image                                              -> mirrored name
IMAGES=(
  "quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z|sha256:14cea493d9a34af32f524e538b8346cf79f3321eff8e708c1e2960462bd8936e|minio"
  "quay.io/minio/mc:RELEASE.2025-08-13T08-35-41Z|sha256:a7fe349ef4bd8521fb8497f55c6042871b2ae640607cf99d9bede5e9bdf11727|mc"
)

for entry in "${IMAGES[@]}"; do
  IFS='|' read -r source digest name <<<"$entry"
  tag="${source##*:}"
  target="ghcr.io/$OWNER/$name:$tag"

  echo "==> $source"
  # The source is unpullable now, so this works from the LOCAL cache. If the cache is cold on your machine
  # and quay is still refusing, there is nothing to mirror from and this stops rather than pushing something
  # else under the same name.
  if ! docker image inspect "$source" >/dev/null 2>&1; then
    echo "    not in the local cache, and quay.io refuses to serve it." >&2
    echo "    Find a machine that still has it (docker images | grep minio) and run this there." >&2
    exit 1
  fi

  local_digest=$(docker image inspect "$source" --format '{{index .RepoDigests 0}}' 2>/dev/null | sed 's/.*@//')
  if [ -n "$local_digest" ] && [ "$local_digest" != "$digest" ]; then
    echo "    REFUSING: local digest $local_digest does not match the pinned $digest." >&2
    echo "    These are different bytes under the same tag. Do not mirror them as that version." >&2
    exit 1
  fi

  docker tag "$source" "$target"
  docker push "$target"
  pushed=$(docker buildx imagetools inspect "$target" --format '{{.Manifest.Digest}}' 2>/dev/null || echo "")
  echo "    pushed $target"
  [ -n "$pushed" ] && echo "    digest at GHCR: $pushed"
done

echo
echo "Now pin these digests in:"
echo "  docker-compose.yml"
echo "  baymax/src/test/java/.../S3StorageAdapterTest.java  (and the three other MinIO tests)"
echo "and make the GHCR packages public."
