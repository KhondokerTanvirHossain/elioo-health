import { afterEach, describe, expect, it, vi } from "vitest";
import { GET } from "./route";

// deploy.sh (W1b) requires sha AND build to equal the tag it deployed: sha is the GIT_SHA it passes at `docker run`,
// build the BUILD_SHA baked into the image by CI. They must be reported separately, each from its own variable, or
// the probe cannot tell a right container from a right-looking one running another image.
describe("/app/healthz", () => {
  afterEach(() => vi.unstubAllEnvs());

  it("reports the run-time sha and the image's build sha, each from its own variable", async () => {
    vi.stubEnv("GIT_SHA", "run-sha");
    vi.stubEnv("BUILD_SHA", "image-sha");
    const response = GET();
    expect(response.status).toBe(200);
    expect(response.headers.get("cache-control")).toBe("no-store");
    expect(await response.json()).toEqual({ service: "medioo-web", sha: "run-sha", build: "image-sha" });
  });

  it("says unknown for a variable that is not set, never another field's value", async () => {
    vi.stubEnv("GIT_SHA", "run-sha");
    vi.stubEnv("BUILD_SHA", undefined);
    expect(await GET().json()).toEqual({ service: "medioo-web", sha: "run-sha", build: "unknown" });
    vi.stubEnv("GIT_SHA", undefined);
    vi.stubEnv("BUILD_SHA", "image-sha");
    expect(await GET().json()).toEqual({ service: "medioo-web", sha: "unknown", build: "image-sha" });
  });
});
