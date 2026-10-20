// The container's own liveness answer: Docker's HEALTHCHECK and deploy.sh (W1b) call it, and Caddy routes it in W1c.
// Read at request time, never prerendered: the sha is the image's GIT_SHA env, set by deploy.sh.
export const dynamic = "force-dynamic";

export function GET() {
  return Response.json(
    { service: "medioo-web", sha: process.env.GIT_SHA ?? "unknown" },
    { headers: { "cache-control": "no-store" } },
  );
}
