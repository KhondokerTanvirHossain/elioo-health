import { act, cleanup, render } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { BANNER_ONLY_COOKIE, clearBannerOnly, holdOnBannerRefresh, markBannerOnly } from "@/components/safety/NowBanner/banner-only";
import { POLL_MS, ReadingProgress } from "./ReadingProgress";

const apiGet = vi.fn();
vi.mock("@/api/browser", () => ({ apiGet: (...args: unknown[]) => apiGet(...args) }));
const refresh = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh }) }));

const DOC = "5e9edf6d-0a2a-4913-8675-9d07a4100003";
const status = (s: string) => ({ kind: "ok", status: 200, body: { status: s } });

beforeEach(() => {
  vi.useFakeTimers();
  apiGet.mockReset();
  refresh.mockReset();
});
afterEach(() => {
  cleanup();
  clearBannerOnly();
  vi.useRealTimers();
});

const tick = () => act(async () => void (await vi.advanceTimersByTimeAsync(POLL_MS)));

describe("ReadingProgress", () => {
  it("pollsStatusAndRefreshesWhenDone", async () => {
    expect(POLL_MS).toBe(5_000);
    apiGet.mockResolvedValueOnce(status("RECEIVED")).mockResolvedValueOnce(status("PROCESSING")).mockResolvedValue(status("DONE"));
    render(<ReadingProgress documentId={DOC} />);
    expect(apiGet, "nothing before the first five seconds").not.toHaveBeenCalled();
    await tick();
    await tick();
    expect(apiGet).toHaveBeenCalledTimes(2);
    expect(refresh).not.toHaveBeenCalled();
    await tick();
    expect(apiGet).toHaveBeenCalledTimes(3);
    expect(refresh).toHaveBeenCalledTimes(1);
    // Every request is the status, which records nothing, never the paper itself (plan Review Focus 4).
    expect(apiGet.mock.calls.map((c) => c[0])).toEqual(Array(3).fill(`/app/api/documents/${DOC}/status`));
    // And it stops.
    await tick();
    await tick();
    expect(apiGet).toHaveBeenCalledTimes(3);
    expect(refresh).toHaveBeenCalledTimes(1);
  });

  it("aPaperGoneMeanwhileIsRefreshedOnceToo", async () => {
    apiGet.mockResolvedValue({ kind: "refused", status: 404, reason: "NOT_FOUND" });
    render(<ReadingProgress documentId={DOC} />);
    await tick();
    await tick();
    expect(apiGet).toHaveBeenCalledTimes(1);
    expect(refresh).toHaveBeenCalledTimes(1);
  });

  it("aSignedOutAnswerStopsThePoll", async () => {
    apiGet.mockResolvedValue({ kind: "signed-out" });
    render(<ReadingProgress documentId={DOC} />);
    await tick();
    await tick();
    expect(apiGet).toHaveBeenCalledTimes(1);
    expect(refresh).not.toHaveBeenCalled();
  });

  it("theResultsRefreshIsNeverBannerOnly", async () => {
    // A stale seen tap on this reading page wrote the banner-only marker (banner-only.ts) and the paper finished before it
    // was cleared. The poll's refresh is the one read the page wants: carried with the marker, the server would send an
    // empty main, the holder would keep "being read", and the poll has stopped, so the page would never move on.
    const marker = () => document.cookie.split("; ").find((c) => c.startsWith(`${BANNER_ONLY_COOKIE}=`)) ?? null;
    window.history.replaceState(null, "", `/app/documents/${DOC}`);
    const release = holdOnBannerRefresh({ path: `/app/documents/${DOC}`, patient: null });
    markBannerOnly();
    release();
    expect(marker(), "guard: the marker is set while the paper is read").not.toBeNull();
    const atRefresh: Array<string | null> = [];
    refresh.mockImplementation(() => void atRefresh.push(marker()));
    apiGet.mockResolvedValue(status("DONE"));
    render(<ReadingProgress documentId={DOC} />);
    await tick();
    expect(refresh).toHaveBeenCalledTimes(1);
    expect(atRefresh[0], "the marker was on the result's refresh").toBeNull();
  });
});
