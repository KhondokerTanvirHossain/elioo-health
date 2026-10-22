import { notFound } from "next/navigation";
import { CATALOG } from "@/catalog/flag";
import { signedInPage } from "../shell";
import "./shell-test.css";

/**
 * The shell with its slots outlined (plan W4), for the board and the layout checks (e2e/shell.spec.ts): signedInPage
 * exactly as a screen calls it, with `main` and `aside` drawn as labelled boxes. It exists only in a catalog build
 * (MEDIOO_CATALOG=1); everywhere else it is a 404 before anything is read (catalog-off.spec.ts, and deploy.sh's
 * catalog probe over WEB_CATALOG_PAGES). The labels are the slots' identifiers, not copy. W5 replaces it with the
 * chat page.
 */
export default async function ShellTestPage() {
  if (!CATALOG) notFound();
  return signedInPage({
    tab: "chat",
    main: (
      <div className="shell-test__slot" data-shell-slot="main">
        <h1 className="shell-test__name" lang="en">
          shell-test main
        </h1>
      </div>
    ),
    aside: (
      <div className="shell-test__slot" data-shell-slot="aside">
        <p className="shell-test__name" lang="en">
          shell-test aside
        </p>
      </div>
    ),
  });
}
