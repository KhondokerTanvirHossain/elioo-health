import type { ReactNode } from "react";

/**
 * The signed-in route group (plan W4): no read, no gate, no shell. A layout is not rendered again on a soft navigation,
 * so a shell or a NOW banner here would keep its first render; every signed-in page renders the shell itself through
 * `signedInPage` (./shell.tsx), which reads GET /app/api/me (and from W5 the NOW banner) on every navigation.
 */
export default function SignedInLayout({ children }: { children: ReactNode }) {
  return children;
}
