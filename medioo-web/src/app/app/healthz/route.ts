// The container's own liveness answer: Docker's HEALTHCHECK and deploy.sh (W1b) call it, and Caddy routes it in W1c.
// Read at request time, never prerendered. Two shas, from two places: `sha` is GIT_SHA, which deploy.sh passes at
// `docker run`; `build` is BUILD_SHA, baked into the image by CI (Dockerfile ARG). deploy.sh requires both to equal
// the tag it deployed, so the answer proves which image is running, not only what the container was told.
export const dynamic = "force-dynamic";

export function GET() {
  return Response.json(
    { service: "medioo-web", sha: process.env.GIT_SHA ?? "unknown", build: process.env.BUILD_SHA ?? "unknown" },
    { headers: { "cache-control": "no-store" } },
  );
}
