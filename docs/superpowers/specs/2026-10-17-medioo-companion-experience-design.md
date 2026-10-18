# Medioo experience — the health companion (design spec)

**Status: approved by Tanvir (PO), 2026-10-17.** The experience as drawn in the two design artifacts below, and the six
decisions put to him: **D1** the companion (chat) is core MVP scope; **D2** inbound-first sign-in code (the family texts
"কোড" on WhatsApp); **D4** "what is this medicine for?" stays refused (DR-16); **D5** the code-computed urgency shows at
once, before any review; **D8** code answers unlimited, 6 model-worded answers per family-month; **D13** voice notes get a
fixed reply plus the red-flag line now, speech-to-text later. In his words: "I agree all." The other §11 items carry their
recommendation as the working default and are confirmed at the start of the phase that needs them. Standing PO direction
(2026-10-16): the pipeline must work well fully automated first; the reviewer workflow and portal come later. "Done" means
Tanvir has tried it on his own phone in production.

## Design artifacts (claude.ai, private to Tanvir; share from each page's Share menu)

| Artifact | Link | What it holds |
|---|---|---|
| Medioo Experience (design canvas) | https://claude.ai/artifact/X9xDXZEL2FgU5hv5bRQT2i | Journey map; 22 web screens in journey order with key alternate states (40 boards, linked for click-through); 8 WhatsApp conversation boards covering all 23 situations in §6 |
| Medioo Design System | https://claude.ai/artifact/F7Kj5uHkfv4nmPWVrDhimZ | Brand book (voice, never-say list, foundations, safety rules in UI), tokens v3 light + dark, 25 components with live previews and usage rules |

The tokens are also kept in the repo as [2026-10-17-medioo-tokens-v3.json](2026-10-17-medioo-tokens-v3.json) (the same
file the design system serves), so implementation never depends on the artifacts being reachable. They evolve DR-14's
`baymax.css`; `DesignTokensTest` still governs the shipped stylesheet. All sample data in the spec and the artifacts is
fictional (DR-36). Development plan: [../plans/2026-10-17-medioo-roadmap.md](../plans/2026-10-17-medioo-roadmap.md).

How this was made: a seven-track deep dive (product rules, web audit, WhatsApp today and platform limits, why the
MedScribe PoC felt better, healthcare UX for Bangladesh, AI companion guardrails, design principles), a v1 spec, three
reviews (clinical safety, UX/accessibility/Bangladesh, feasibility on `main` @ `e4494c1`), and this revision. What
changed and what was declined is in §13. Paths are relative to the repo root.

---

## 0. The problem in one page

| What Tanvir sees | Why (evidence) |
|---|---|
| "Hi" on WhatsApp gets silence | `WaIntakeService.onText` answers only the literal বিস্তারিত/detail/bistarito (`baymax/src/main/java/com/elioo/baymax/wa/application/service/WaIntakeService.java:97-119`). Every other text returns `Outcome.ACCEPTED` and sends nothing (`:108`), and a unit test locks that silence in (`WaIntakeServiceTest`, `anOrdinaryTextIsAcknowledgedWithoutAReply`). Voice, stickers and button taps are dropped (`WaWebhookHandler.java:112-113`). |
| The product doc already promised a reply to "Hi" | `docs/PRODUCT.md:204-206` (§5.1). Chat is in scope (`docs/PRODUCT.md:125-127`) but missing from the MVP-in list (`:216-229`). |
| Nobody can log in to the web | The only OTP adapter writes the code to the log at INFO (`web/adapter/out/delivery/LogOtpDelivery.java:21`), selected by `BAYMAX_OTP_DELIVERY:log` (`medscribe-ai/src/main/resources/application.properties:124`). The copy promises a code will be sent. Only the hash is stored (`WebAuthService.java:82`), so a code cannot be fetched later. Families exist only through the admin API (`BaymaxFamilyRouter`). |
| A clean screenshot was sent back for a retake | Section-level gate (DR-27). Retake pages print developer-facing English reasons (`Document.java:11`; rendered raw at `WebUiHandler.java:418`). There is no retake button. |
| The detail explanation is weaker than MedScribe | MedScribe let the model interpret everything, unchecked (`DefaultPromptTemplateEngine.java:40-126`; chat at temperature 0.7 with no output check, `LlmInsightAdapter.java:722-731`). Medioo's detail is value lines rephrased (`ExplanationService.java:270-287`). |
| The website looks basic | Admin-style lists. Urgency is invisible: `TimelineEntry` has no urgency, and **no table stores a document's urgency at all**; it lives only on `outbound_message` rows (`ExplanationService.java:116, 182-184`). The landing page overflows at 360 px (`baymax.css:51`). |
| *Found in review:* the "বিস্তারিত" path double-sends and can bypass the gate | `detail()` composes, saves **and delivers** (`ExplanationService.java:69, 200-221`), then `onText` sends the body again (`WaIntakeService.java:103-105`). With the gate on, a PENDING body would be sent by `onText` itself. |
| *Found in review:* "within range" can be claimed without a parsed comparison | `canSayWithinNormalRange` checks that range strings exist and reads the model's `flag` (`ExplanationService.java:235-258`). `criticalKind` returns NONE for an unparsed value or a one-sided range (`UrgencyService.java:345-350`). So "LDL 250, range <100" with a blank flag can be called routine. This is **live today** (§12 Phase 0). |

**The answer:** one companion brain behind web and WhatsApp that *always replies*, answers from the family's own
records, and keeps every fixed rule by putting code, not the model, in charge of numbers, medicines, urgency, patient
identity and refusals. The model's job is wording, and only over facts code has already checked. Push (nudges) stays
the moat (`docs/PRODUCT.md:98`); chat is added to it.

---

## 1. Design principles

1. **Every message gets a reply. Silence is a defect.** No inbound type ends without an answer, on either channel
   (`WaIntakeService.java:23-25`; NN/g, https://www.nngroup.com/articles/ai-chatbots-design-guidelines/).
2. **Code decides, the model words.** Urgency, numbers, medicines, diagnoses, patient identity, refusals and emergency
   replies are produced by code. The model may only phrase over code-built references and never writes a digit, a
   number word, a timing word, a condition name or a medicine (`docs/MEDIOO.md:40`; §7.1 step 7).
3. **Urgency is always visible and only goes up.** Every surface shows the **stored**, code-computed level (§2 F1),
   **whatever the review state**. Only model wording waits for review. Nothing can soften, omit, restate or delay it
   (HC2, `docs/MEDIOO.md:23`). Urgency appears only as the fixed `urgency.*` strings (`messages_bn.properties:52-53`)
   or their PO-approved short and remote forms.
4. **"Inside the range" must be proven by code.** A value is INSIDE only when the value, the low and the high all parse
   and the value lies between them. Everything else is UNKNOWN ("কাগজে মিলিয়ে দেখুন"), never teal and never "normal".
5. **Every fact shows its receipt.** A value, medicine line or doctor's note is shown beside a crop that contains its
   own text (DR-12; HC3, `docs/MEDIOO.md:24`). Sources lower reliance on wrong answers (Kim et al., CHI 2025,
   https://arxiv.org/abs/2502.08554v1).
6. **"Written on the paper" and "Medioo's words" never look alike.** Verbatim blocks carry "কাগজে লেখা"; model text
   carries "Medioo-র কথা" (DR-16, `docs/DECISIONS.md:224-243`; DR-26 `:437`).
7. **The paper goes to the pharmacy, not the transcription.** Medicine lines are shown crop-first. Medioo never offers
   its typed text as something to dispense from.
8. **Unsure means ask, never guess.** Low confidence → retake. Unclear patient → "কার?". Unclear test → "কোন পরীক্ষা?".
   A model suggestion about the patient becomes a confirm chip, never a lookup (HC3; PRODUCT §3.4).
9. **The patient is always named, and every lookup is by patient and by viewer.** Each answer's first line names the
   patient (DR-19). Use "আপনার" only in self mode, where the patient is the reader (§2 F8). Data is read only through the
   viewer's visibility (`TimelineService.java:156-165`). Wrong-patient is the most harmful proxy error.
10. **Calm, warm, honest, and never reassuring beyond the paper.** Never "চিন্তার কিছু নেই". ROUTINE gets no chip and no
    status word (MedlinePlus, https://medlineplus.gov/lab-tests/how-to-understand-your-lab-results/). Medioo says "আমি
    ডাক্তার নই" when it matters (`docs/PRODUCT.md:268`). A human reviewer is "একজন", never "ডাক্তার".
11. **Know where the reader is.** The main persona is often abroad (D17). Emergency actions, times and greetings
    depend on the viewer's number and zone, not on an assumption that the reader stands beside the patient (§2 F7).
12. **Bangla first, readable by a 70-year-old.** Body 17 px. Any style that can wrap has a Bangla line-height of at
    least 1.5. Nothing Bangla below 14 px, 48 px targets, 4.5:1 text contrast, 3:1 control borders, never colour alone,
    page zoomable (HIG https://developer.apple.com/design/human-interface-guidelines/accessibility; WCAG 2.2
    https://www.w3.org/TR/WCAG22/). English is an explicit toggle, never sniffed (`docs/MEDIOO.md:76`).
13. **Values keep the digits printed on the paper.** Lab values, units and ranges are never locale-converted. UI dates
    and counts use Bangla digits with full month names (DR-17 `docs/DECISIONS.md:257`).
14. **Light enough for a BDT 15,000 Android on a slow network.** Server-rendered and works without JS. **First load is
    at most 200 KB excluding crops** (PO limit, `docs/MEDIOO.md:76`): fonts via `local()` first, JS at most 40 KB and
    only on enhanced pages, images downscaled before upload (https://web.dev/articles/performance-budgets-101).
15. **Push stays polite, in the recipient's day.** Nudge caps per DR-18 (`docs/DECISIONS.md:259-274`), delivered
    09:00–20:00 **in the recipient's zone** [PO, amends DR-18], never NOW (NOW is not a nudge), opt-out on every one.
16. **A human is always reachable, and only routes that work are promised.** "সাহায্য" goes to a person; WhatsApp
    policy requires an escalation path (https://whatsappbusiness.com/policy/). Never promise a person who cannot be
    reached (`messages_bn.properties:41-42`).
17. **Medioo is a records service with a voice, not an open AI assistant.** Off-topic questions are politely declined,
    which keeps Medioo outside Meta's AI-Provider ban (Terms §4.7,
    https://www.facebook.com/legal/Meta-Terms-for-WhatsApp-Business-Platform).
18. **Safety paths never depend on the model, the allowance or the network.** Red-flag, urgency, medicine and refusal
    handling run in code, with zero metered calls, before any quota, classifier or timeout.

---

## 2. Foundations the screens depend on (new in v2)

Every screen below reads from these. Each one is a prerequisite named in the phasing (§12).

**F1 — Stored urgency (V16).** Today urgency exists only on `outbound_message` rows. If `explain()` throws, nothing is
stored (`DocumentIntakeService.java:97-100`), and a later `detail()` can reassess higher (follow_up_past_due,
`UrgencyService.java:100-104`). Proposal: `document.urgency`, `document.urgency_reasons` and `document.urgency_assessed_at`.
They are written by **one central writer**, called every time `UrgencyService.assess` runs (extraction finish,
explanation, detail, the nudge job), as `max(old, new)`. A DB trigger rejects any lowering. This asserts the rule once,
where every writer meets it (CLAUDE.md "a fix scoped to the path… "). Timeline, result screens, chat and WhatsApp
read this column, never a message row. Testcontainers test: an attempt to lower fails at the database.

**F2 — Per-value verdict from `assess()`.** Today the banner comes from `assess()`, which applies a live per-marker
threshold first and skips the stopgap (`UrgencyService.java:64-71`). The rows would come from `criticalKind`, which is
stopgap-only (`:345-362`), so the two can disagree once a threshold goes active. Proposal: `assess()` returns, per
observation, `{observation_id, verdict: INSIDE|OUTSIDE|CRITICAL|UNKNOWN, level, reason}`. The reason carries
`|threshold:<source>` or `|stopgap` (`:127-139`) and is stored next to F1. Value rows, result-now's "what caused it"
rows and chat record cards render **only** from this verdict. The tri-state rule (principle 4) lives here, not in the
UI. Tests: (a) every row's reason equals an entry in `urgency_reasons` (assert the path, not the outcome); (b)
`'> 89'`, `'130 / 80'`, `'1,23'`, `'Positive'`, a one-sided `'<100'` and a missing range are all UNKNOWN; (c)
`canSayWithinNormalRange` requires INSIDE for every value (§12 Phase 0).

**F3 — One gate-state table.** Gate states are RELEASED, PENDING, APPROVED, REJECTED and FAILED_SAFETY (`OutboundMessage.java:27`). Reject never
delivers (`ReviewGateService.java:19, 64-71`). A safety failure saves `body = null` (`ExplanationService.java:188-190`).
`latestDeliverable` returns only released or approved rows that have a body (`PostgresOutboundMessageAdapter.java:93-95`).

| Gate state of the latest explanation | Urgency banner / line | Values (cards with crops) | Model wording | Copy |
|---|---|---|---|---|
| none yet (being read) | none until DONE | none | none | reading-progress |
| RELEASED / APPROVED | **stored F1 level** | yes | yes | normal |
| PENDING | **stored F1 level, at once** (D5) | yes | no | "Medioo-র ব্যাখ্যাটা একজন মিলিয়ে দেখছেন — সাধারণত [N] ঘণ্টার মধ্যে; রাত হলে সকালে।" |
| REJECTED / FAILED_SAFETY | **stored F1 level** | yes | never | "এই কাগজের লেখা ব্যাখ্যা দেওয়া হবে না। কাগজে যা লেখা আছে নিচে দিলাম — প্রশ্ন থাকলে ডাক্তারকে দেখান।" Never "দেখা হচ্ছে". |

On WhatsApp the same rule holds: a PENDING NOW or THIS_WEEK sends the fixed urgency notice **at once** as its own code
message (§6 W16) [PO, amends DR-13's "Nothing PENDING is ever delivered", `docs/DECISIONS.md:172`. The pending body
is still never delivered; a separate code notice is]. Reviewer SLA [PO, D25]: re-notify the reviewer after 15 minutes
on a pending NOW. After 60 minutes, send the code-only template (urgency + "কাগজে দেখুন" link), with no model text.

**F4 — Compose without delivering.** Split `ExplainDocumentUseCase` into `composeChecked(documentId, kind)` (a
safety-checked body with no save and no delivery) and `release(message)`. Cache the latest DETAIL per document. Web
chips and companion answers use `composeChecked`. `onText` never sends a body itself: PENDING gets the F3 template.
Tests in both gate modes (production runs `gate=off`, `application.properties:140`, which shows only the double-send,
never the bypass).

**F5 — Retake reason codes (V16).** Add `document.status_reason_code` with values `no_text | low_overall |
expected_section_empty:<section> | low_section:<section>`. It is set at the four `finish()` calls
(`DocumentExtractionService.java:242-259`), and each code maps to Bangla copy. `statusReason` stays developer-facing
and is never rendered.

**F6 — The viewer model.** A viewer is an owner account or a share member, and each share member has their own
account (§3.20). Threads, family statements (D10), NOW acknowledgements (D14) and conversation state are keyed by
**(viewer, patient)**, never by family. Chat context is built only through `records.visiblePatient` /
`documents.findVisible` for that viewer (`TimelineService.java:156-165`). The reference resolver checks that every
`{{obs|dx|doc|rx:id}}` belongs to **the active patient**. Test: Ma's share member asks about Baba by name and gets
"এই নামে আপনার দেখার অনুমতি থাকা কেউ নেই".

**F7 — Viewer locale.** The country comes from the viewer's number (+880 or not). The zone comes from the country
code, can be changed in account settings, and falls back to Asia/Dhaka. The patient's calendar (follow-ups, "আজ/কাল")
stays in Dhaka time, and a non-+880 viewer sees "(ঢাকার সময়)". This drives the emergency action variant (§3.11,
§3.14), nudge delivery hours and greetings (no time-of-day greetings anywhere).

**F8 — Name grammar and self mode.** A code helper builds `{patient_poss}` and `{patient_obj}`. A Bangla name ending in
a vowel or vowel sign (া ি ী ু ূ ে ৈ ো ৌ) takes "র": আম্মার, আব্বুর, নানুর. A name ending in a consonant or hasanta
takes "এর": রহিমের. "কে" always attaches: আম্মাকে. A Latin-script name keeps a hyphen: Rina-র. Bundles never write
`{patient}-এর` (today `messages_bn.properties:65-69`). Self mode: when relation = নিজে, every patient reference renders
as "আপনার" / "আপনাকে". Tests: আম্মা, রহিম, আব্বু, Rina, a hasanta ending, and self.

**F9 — Robust processing and inbound.** (a) An `inbound_message` table with a unique provider message id gives
idempotency against Meta re-delivery and also serves as chat history (Testcontainers, per the CLAUDE.md
unique-constraint rule). (b) A startup sweep re-runs RECEIVED/PROCESSING documents (`DocumentRecordPort.idsWithStatus`),
because processing is a detached in-memory subscription (`DocumentIntakeService.java:89-114`) and every merge
redeploys. (c) `conversation_state` holds a pending media id, so a WhatsApp photo waiting for "কার?" is downloaded
after the answer.

**F10 — Every outbound passes the gate stage with its own kind.** HC4 (`docs/MEDIOO.md:25`) says every outbound passes
the review-gate stage, and that stage is config-driven. Chat answers, urgency notices and emergency replies pass
`OutboundMessageGate.decide` as `Kind.CHAT`, `Kind.URGENCY_NOTICE` and `Kind.EMERGENCY`, each with its own policy.
EMERGENCY and URGENCY_NOTICE are fixed to release, and code asserts that the policy cannot be set to park them. This
satisfies HC4 without amending it [PO to confirm this reading, D40].

---

## 3. Information architecture (web, mobile first)

```
/                      landing (public)  · /demo  sample family, read-only, no sign-in [PO D38, phase 7]
/app                   sign-in: number  →  /app/verify  code (inbound-first, §4.3)
                        └─ new number (phase 5) → /app/welcome (name + terms) → /app/patients/new (add parent)
/app/home              ── TAB 1  চ্যাট (companion home + thread)           [default tab]
/app/timeline          ── TAB 2  কাগজপত্র (per active patient, chronological)
   /app/documents/{id}            result summary → "সব দেখুন" → detail
   /app/documents/{id}/all        document detail (values, medicines, doctor wrote, pages)
   /app/patients/{id}/trends/{marker}
/app/family            ── TAB 3  পরিবার (patients, sharing, reminders, settings)
   /app/patients/{id}/profile · /share · /reminders · /app/account
sheets (overlay; each also has a no-JS URL): add-report · page tray · value detail · patient switcher · confirm delete
```

- **Three bottom tabs, always labelled:** চ্যাট · কাগজপত্র · পরিবার. "টাইমলাইন" was renamed because it is social-media
  jargon for older users. The tab bar hides while the composer has focus and on `companion-thread`.
- **Patient switcher in the header** ("আম্মা ▾"). The active patient scopes chat, timeline and trends. Active-patient
  state is per viewer and per channel, and expires after 30 minutes of silence (F6).
- **Share member:** the same tabs. Whether the camera button shows depends on role: "শুধু দেখবেন" (view only) or
  "কাগজ পাঠাতে পারবেন" (can send papers) [PO D30]. Delete is never available.
- Literal routes before `{id}` (`CLAUDE.md`; `BaymaxWebUiRouter.java:67`). All chat endpoints are under `/app`
  (`SessionAuthFilter.java:74-80`). Back from a document returns to where it came from (fixes `WebUiHandler.java:407`).
- Timeline orders by document date, falling back to upload date with a label. This needs a composite cursor (date,
  created_at, id) plus an index (`TimelineService.java:65-73`, size M).

---

## 4. Screens (22 for the design canvas)

Copy is direction for Tanvir's wife, who owns the wording (`messages_bn.properties:1-3`), not final text. Glossary
(§7.4) applies to every screen.

### 4.1 `landing` — first visit
- **Purpose:** explain in 20 seconds what Medioo does for a family, then start sign-in.
- **Content:** keep the chat-thread story. Hero line: "পরিবারের সব রিপোর্ট মনে রাখে। জিজ্ঞেস করলে বুঝিয়ে বলে। সময় হলে
  নিজেই জানায়।" The animated thread shows a photo → a reply with an urgency card and a source chip → a question → a
  grounded answer with a record card. Sections: the polythene bag; "it asks back"; urgency cards; "check it yourself";
  what Medioo doesn't do; promises; Mio; footer with disclaimer. Primary CTA "শুরু করুন" → `/app`; secondary "নমুনা
  পরিবার দেখুন" → `/demo` once D38 ships. Never a `wa.me` link on the landing page (`docs/MEDIOO.md:76`).
- **States:** bn / en; reduced motion (static thread); 360 px with no horizontal scroll (fix `baymax.css:51`).
- **Safety:** the voice chip comes out until BMX-7 ships (`docs/MEDIOO.md:76`). Rewrite `landing.f.msg`, which shows
  Medioo explaining what a medicine is for, forbidden by DR-16 (`ui_bn.properties:50`). Landing values use the Latin
  digits as printed ("1.1 → 1.3 → 1.5", not "১.১ → ১.৩ → ১.৫", `ui_bn.properties:22, 43`). Never claim Bangladesh data
  residency (HC7). Keep the disclaimer.

### 4.2 `sign-in-number` — number entry
- **Purpose:** one field, no password.
- **Content:** h1 "Medioo-তে ঢুকুন". **One field with no fixed prefix**, "আপনার WhatsApp নম্বর". It accepts
  `01XXXXXXXXX` (treated as BD), `+<country>…` and `00<country>…`, Bangla digits ০-৯, spaces and dashes (Ridmik and
  Gboard Bangla emit them). It shows the normalised number back: "+880 1712-345678 — ঠিক আছে?". Primary "পরের ধাপ".
  Line: "যে নম্বরে WhatsApp চালান, সেটাই দিন".
- **States:** default; invalid format (inline, client side, reveals nothing); login switched off (`app.err.loginoff`);
  loading. No rate-limit message on the web, because the web sends nothing in phase 1a (D36).
- **Safety:** no number or code in query strings (DR-23). The page stores only the HMAC plus the last three digits in
  the login cookie, for the masked display.

### 4.3 `sign-in-code` — get the code from WhatsApp (inbound-first, phase 1a)
- **Purpose:** finish sign-in. The family asks Medioo for the code on WhatsApp; nothing is pushed.
- **Content:** "এখন WhatsApp-এ Medioo-কে লিখুন: **কোড**". Primary button "WhatsApp খুলে কোড আনুন", a deep link
  `https://wa.me/<BAYMAX_WA_PUBLIC_NUMBER>?text=কোড` [PO D37: the pilot number from `BAYMAX_WA_PUBLIC_NUMBER`, "never
  on the landing page" (`docs/MEDIOO.md`); this is the sign-in page]. Under it: "WhatsApp-এ শুধু Send চাপুন — কোড সাথে সাথে চলে আসবে".
  Then one 6-digit field (`autocomplete=one-time-code`, numeric keypad, folds Bangla digits, 20 px tabular digits);
  primary "ঢুকুন"; "কোড ১০ মিনিট চলবে"; "অন্য নম্বর দিন". The masked number reads "+880 17•• •••171".
- **States:** waiting ("কোডের অপেক্ষায়…"; with JS, a 5 s poll changes it to "কোড পাঠানো হয়েছে — WhatsApp দেখুন" once
  a code was issued for this phone hash after the page opened); wrong ("কোডটা মেলেনি — যে নম্বর থেকে WhatsApp
  করেছেন, সেটাই কি এখানে দিয়েছেন?"); expired; burned after 5 tries (`WebAuthService.java:128-134`); success →
  `companion-home`; number with no account → "এই নম্বরে এখনো Medioo খোলা হয়নি" (phase 1a). From phase 5 it goes to
  `welcome-consent` via a `signup_intent` row instead (§4.4).
- **Copy rule, tested in both directions:** the page says the code was sent (`login.code.sent`, "কোডটা আপনার
  WhatsApp-এ পাঠানো হয়েছে") only when WhatsApp accepted the automatic send; every other state never says it and
  shows the one-tap button (PO amendment 2026-10-18, DR-39; there is no authentication template).
- **Safety:** `issueCodeFor(verifiedSenderNumber)` mints a fresh code inside the inbound window, only for the sender,
  within the existing rate limit (5 codes made per number per rolling hour, whatever their state — amended 2026-10-18 after review; it first counted live codes only). Replies go only to allowlisted
  numbers (`WaIntakeService.java:134-139`). One composite `OtpDeliveryPort`: WhatsApp is primary. When the send fails,
  the INFO line carries no code and the code is at DEBUG only. Test: a successful WhatsApp delivery writes no code to
  any log line. Today `LogOtpDelivery` logs it at INFO (`:21`) and cannot coexist with another adapter (`:16`).
  WhatsApp's own reply text: "এই কোড কাউকে দেবেন না। Medioo কখনো ফোন করে কোড চায় না।"

### 4.4 `welcome-consent` — new family, terms (phase 5)
- **Purpose:** the first consent moment, layered and just in time (`docs/PRODUCT.md:243`).
- **Content:** Mio says "স্বাগতম! আপনাকে কী নামে ডাকব?" (name field). A three-line card: "Medioo ডাক্তারি কাগজ গুছিয়ে
  রাখে আর সহজ বাংলায় বলে — Medioo ডাক্তার নয়, ওষুধ বদলানোর কথা বলে না" · "আপনি আর আপনি যাদের যোগ করবেন, শুধু
  তাঁরাই দেখবেন" · "চাইলে ২৪ ঘণ্টার মধ্যে সব মুছে ফেলা হয়". Link "পুরো শর্ত পড়ুন"; primary "রাজি আছি, শুরু করি".
  Optional invite code (D9).
- **States:** default; name missing; not invited ("এখন শুধু আমন্ত্রিত পরিবার"); saving.
- **Data path:** `verify` today returns 401 when there is no family (`WebAuthService.java:118-123`), and
  `terms_accepted_at` is NOT NULL (V4). Proposal: a short-TTL `signup_intent` row (number + phone hash) created at
  verify, a pre-account cookie, new `audit_event` kinds, and `signup_intent` added to both deletion paths. Size M.
- **Safety:** `terms_accepted_at` is written once and is immutable (V4 trigger).

### 4.5 `add-parent` — whose health, proxy consent
- **Purpose:** create a patient profile with proxy consent (PRODUCT §6.2; Bangladesh PDPO 2025,
  https://www.thedailystar.net/tech-startup/news/bangladeshs-personal-data-protection-ordinance-2025-key-takeaways-4015401).
- **Content:** "কার স্বাস্থ্যের খোঁজ রাখব?". Field "যে নামে ডাকেন" (e.g. আম্মা). Relation chips: মা · বাবা · শ্বশুর ·
  শাশুড়ি · নানা/নানি · দাদা/দাদি (পিতামহ) · স্বামী/স্ত্রী · সন্তান · নিজে · অন্য কেউ (লিখুন). Age accepts approximate
  input ("৭০-এর মতো"). Sex is optional (DR-30). Conditions are optional and come later (asked, never inferred). One
  consent sentence with one tap: "আম্মা জানেন যে তাঁর ডাক্তারি কাগজ আমি Medioo-তে রাখছি". Optional "কাছের মানুষ" (who
  is usually with her, name + number), used by the remote emergency action (§4.11). Primary "আম্মার ফাইল খুলুন".
- **States:** first patient; second patient over the free limit (`max-patients=1`, `application.properties:150`, with
  Bangla copy); validation; self (relation নিজে → self mode F8; the consent row becomes "এগুলো আমার নিজের কাগজ"); saved
  → `companion-home`.
- **Safety:** `proxy_consent_at` is immutable and creation is refused without it (`FamilyAccountService.java:71`).

### 4.6 `companion-home` — চ্যাট tab, the daily door
- **Purpose:** the "always ready" companion. It shows what needs attention and invites a photo or a question.
- **Content (top to bottom):** header (wordmark, patient switcher, language toggle). **Attention stack**, highest
  first, each with one action: NOW (solid red header) › THIS_WEEK (amber) › waiting for retake › a follow-up the doctor
  wrote due within 7 days › being read. Every card's level comes from F1. Greeting bubble ("হ্যালো, তানভীর।"; no
  time-of-day greetings, F7). It **leads with any NOW or THIS_WEEK from the last 30 days this viewer has not
  acknowledged**, in stack order, with the date and the `urgency.*` line; only otherwise does it name the latest
  paper. Then three suggestion chips ("শেষ রিপোর্টটা বুঝিয়ে বলো" · "শেষ প্রেসক্রিপশনে কী লেখা" · "ডাক্তারকে দেখানোর
  আগে"), the recent thread, and a docked composer (camera · "কিছু জিজ্ঞেস করুন…" · send).
- **States:** no patient → `add-parent`; no document → Mio empty state "আম্মার যেকোনো রিপোর্ট বা প্রেসক্রিপশনের ছবি
  পাঠান — পুরনো হলেও চলবে"; several patients → the greeting names each and asks "কার?". NOW present → the card pins on
  top and collapses to a 48 px red strip while the thread scrolls; it is never dismissible. "দেখেছি" shrinks it to that
  strip for 7 days **for this viewer only** (D14, F6). Share member → by role. Offline → the composer stays typable,
  holds the draft and sends when the network is back ("নেটওয়ার্ক ফিরলে পাঠাব"). A safety template that matched offline
  is shown from the client's cached copy at once (§7.1 step 1).
- **Safety:** attention order is code urgency order. The greeting is a template with zero model calls. Suggestions
  never propose dose questions. The first session shows "আমি Medioo — ডাক্তার নই" once.

### 4.7 `add-report-sheet` — upload
- **Purpose:** get a readable photo, PDF or screenshot into the right patient's file.
- **Content:** bottom sheet. "কার কাগজ?" chips show **name + relation + age** ("আম্মা · মা · ৭০"), preselected only
  when there is one patient. Three big rows: "ছবি তুলুন" (`capture=environment`), "গ্যালারি থেকে (একাধিক পাতা)"
  (`multiple`), "PDF বা স্ক্রিনশট". Three tips with pictograms. Quota line (DR-24).
- **Client-side downscale (JS):** long edge about 2000–2400 px, JPEG about 0.85 (roughly 400–700 KB). **The size is
  chosen only after** running `scripts/replay-extraction-gate.py` and the crop-content check on downscaled versions of
  the synthetic set, so readability is not traded for speed.
- **States:** default; >1 patient with none chosen (send disabled, "কার কাগজ বেছে নিন"); view-only share member
  (explained); free cap reached (before upload); too large without JS ("ছবি WhatsApp-এ পাঠান — ওখানে ছোট হয়ে যায়");
  unsupported type.
- **Safety:** never guesses the patient (`messages_bn.properties:79`).

### 4.8 `page-tray` — multi-page review
- **Content:** "৩টি পাতা — একই রিপোর্টের?" (একটা রিপোর্ট / আলাদা আলাদা); 3:4 thumbnails, ▲▼ buttons to reorder, ×,
  "+ আরও পাতা"; max 10 (`application.properties:178`); primary "পাঠান".
- **States:** one page (tray skipped); 10-page limit; local load failure; no-JS fallback.
- **Safety:** grouping is the family's explicit choice.

### 4.9 `reading-progress` — honest waiting
- **Content:** patient + thumbnail. First an upload step: "পাঠানো হচ্ছে — ৩টির ২টি পাতা" with a real byte progress bar
  and "নেটওয়ার্ক ধীর — পাতা হারাবে না" (files kept, per-page retry). Then three steps from real states: "পেয়েছি"
  (RECEIVED) → "পড়ছি" (PROCESSING) → "তৈরি" (DONE) (`Document.java:49`). There is no crop-verification state, so none is
  shown. "চলে গেলেও সমস্যা নেই — কাগজপত্রে থাকবে". After 5 minutes: "স্বাভাবিকের চেয়ে বেশি সময় লাগছে — হয়ে গেলে
  কাগজপত্রে দেখাবে" (the F9 sweep makes that true).
- **WhatsApp notice of a web upload:** a NOW result is always sent on WhatsApp too (code notice, F10). THIS_WEEK
  defaults to on, with a checkbox "WhatsApp-এও জানাবেন". ROUTINE defaults to off. A per-document `notify_whatsapp`
  flag; today every RELEASED message goes to WhatsApp whatever the source (`DocumentIntakeService.java:94`) [PO D32].
- **States:** uploading; in progress (JS poll every 5 s; no-JS `<meta refresh>` on this page only); network failure;
  taking long; DONE → result; NEEDS_RETAKE → `needs-retake`; FAILED → specific Bangla message + "আবার পাঠান".
- **Safety:** no result text before DONE; no reassurance while waiting.

### 4.10 `result-summary` — the result (ROUTINE / THIS_WEEK)
- **Purpose:** answer "is anything wrong, what do I do next" on one screen, then invite questions.
- **Content:** header: patient chip, document type, full date, facility. **Urgency banner from F1** —
  THIS_WEEK: amber, calendar icon, `urgency.this_week` verbatim (`messages_bn.properties:53`). **ROUTINE: no banner,
  no chip, no status word**; the header says only "তুলে রাখা হয়েছে". **"Medioo-র কথা"**: the phrased explanation, shown
  only when F3 allows model wording. **"যেগুলো সীমার বাইরে"**: value rows rendered from the F2 verdict, each with its
  crop. **"কাগজে মিলিয়ে দেখুন"**: UNKNOWN values (no bar, no teal). **"ডাক্তার লিখেছেন"**: diagnosis lines verbatim
  with crop (DR-26). **"ওষুধ"**: crop-first medicine rows (§4.16). **"পরের ধাপ"**: what to bring, from code. **Ask
  bar**: chips "এই পরীক্ষাগুলো কী?" · "আগের বারের সাথে তুলনা" · "ডাক্তারকে দেখানোর আগে" + composer. Links: "সব মান ও
  মূল কাগজ দেখুন" and "ভুল দেখছেন?".
- **States:** ROUTINE with nothing outside (template, zero model calls). THIS_WEEK. **PENDING** (F3: banner at once,
  values, "Medioo-র ব্যাখ্যাটা একজন মিলিয়ে দেখছেন — সাধারণত [N] ঘণ্টার মধ্যে; রাত হলে সকালে।", N measured during week
  0 and stated in the viewer's zone). **REJECTED / FAILED_SAFETY** (F3: banner, values, "এই কাগজের লেখা ব্যাখ্যা দেওয়া
  হবে না…"). Unverified values (DR-28/31): "কিছু মান মিলিয়ে দেখতে পারিনি — মূল কাগজ দেখুন". A value with no written
  range: "রিপোর্টে সীমা লেখা নেই", never "স্বাভাবিক সীমা –" (`ExplanationService.java:321-331`). Family flagged it wrong:
  "পরিবার ভুল বলেছেন — যাচাই হচ্ছে", with urgency unchanged.
- **Safety:** banner and rows share F1/F2, never the model's `flag` (`ExtractionPromptBuilder.java:87`). "সীমার
  মধ্যে" appears only on INSIDE. The "সব কিছু স্বাভাবিক সীমার মধ্যে" line only when every value is INSIDE (F2c).
  Medicines never appear in model text.

### 4.11 `result-now` — urgent NOW
- **Purpose:** the one screen where calm gives way to clear action (NHS care cards,
  https://service-manual.nhs.uk/design-system/components/care-cards).
- **Content:** solid `status-now-solid` header with octagon icon and `urgency.now` verbatim
  (`messages_bn.properties:52`). It is on first paint, never animated and never dismissible, whatever the gate state
  (F3). Body (soft red) shows **one rendering per reason prefix** (`UrgencyService.java:59-123`):
  - `value_critical:*` → the value row(s) with crop, from F2.
  - `unverified_value_critical:*` → "এই কাগজের একটা মান আমরা মিলিয়ে দেখতে পারিনি, কিন্তু সেটা বিপদসীমায় হতে পারে —
    মূল কাগজটা এখনই ডাক্তারকে দেখান।" + full page image. **Never the number.**
  - `text_emergency_phrase` → "কাগজে 'জরুরি' বা 'ভর্তি' লেখা আছে।" + page image.
  - THIS_WEEK reasons shown below if present: `follow_up_past_due` → the follow-up row; `diagnosis_present` → the
    diagnosis lines.
  Then the red-flag questions "অজ্ঞান? শ্বাস নিতে কষ্ট? বুকে ব্যথা?" and "হ্যাঁ হলে এখনই নিকটস্থ হাসপাতালের জরুরি বিভাগে
  যান". "সাথে নিয়ে যান: এই রিপোর্ট + শেষ প্রেসক্রিপশন" (code list). "আমি ডাক্তার নই". The rest of the result follows.
- **Primary action by viewer (F7):**
  - +880 viewer: the primary action is the `urgency.now` instruction itself. The "৯৯৯-এ ফোন করুন" button (`tel:999`)
    appears **only under the red-flag questions** ("হ্যাঁ হলে"), because a stopgap NOW (`BaymaxProperties.java:192-196`,
    "wrong in both directions") must not send a stable chronic creatinine to the national emergency line.
  - non-+880 viewer: the primary is "আম্মার কাছে যিনি আছেন, তাঁকে এখনই ফোন করুন — তাঁকে ৯৯৯-এ ফোন করতে বা আম্মাকে
    হাসপাতালে নিতে বলুন". It calls the "কাছের মানুষ" if set, otherwise opens the contacts. There is **no `tel:999`**.
    The text reads "বাংলাদেশে জরুরি নম্বর ৯৯৯ — বিদেশের ফোন থেকে এই নম্বরে লাগবে না". A "বাড়ির কাউকে পাঠান" button
    shares a value-free text through WhatsApp: who, "go to the emergency department now", what to bring.
  - [PO D28] a patient-naming wording: `urgency.now` "এখনই আম্মাকে ডাক্তারের কাছে নিয়ে যান। দেরি করবেন না।" and
    `urgency.now.remote`.
- **Tests:** a +44 viewer gets no `tel:999`, a +880 viewer gets it (both directions). Each reason prefix renders its own
  block (assert the block id, not "a red banner").
- **Safety:** red is used for NOW-level urgency and emergency on every surface (§8). No 16263 (funding crisis,
  https://thefinancialexpress.com.bd/health/natl-telehealth-helpline-16263-faces-shutdown-amid-funding-crisis).
  999 confirmed under D11.

### 4.12 `needs-retake` — couldn't read it clearly
- **Content:** warn-tint card, camera icon. "এই ছবিটা থেকে ঠিকমতো পড়তে পারিনি — আন্দাজে কিছু বলব না।" Then what was
  unreadable, in Bangla, mapped from `status_reason_code` (F5): `no_text` → "ছবিতে লেখা খুঁজে পাইনি";
  `expected_section_empty:values` → "মানগুলোর অংশটা পড়া যায়নি"; `low_overall` → "পুরো ছবিটা ঝাপসা". Then the page
  image (no region; the pipeline does not know one). Tips escalate on retakes 1, 2 and 3 (`messages_bn.properties:34-43`).
  Primary "আবার তুলুন" (same patient); secondary "অন্য কাগজ পাঠান". "এটা আপনার দোষ নয় — আর এটা গোনা হবে না" (DR-24).
- **States:** first, second, third retake; screenshot/PDF retaken (replay the gate first, D21).
- **Removed in v2:** partial read. NEEDS_RETAKE persists nothing but the document row (`docs/MEDIOO.md:37`;
  `DocumentExtractionService.java:242-259`), and HC3 says retake, not answer. If a partial read is ever proposed as a DR,
  it must never show ROUTINE, "সীমার মধ্যে" or teal, and its header must say "সব মান পড়া যায়নি — বাকিগুলো না দেখে কিছু
  বলা যাবে না".
- **Held back:** "ছবিটা ৭ দিন পর মুছে যাবে" ships only with the DR-25 purge job (`docs/DECISIONS.md:417-425`). There is no
  such job today; the only `@Scheduled` is `NudgeScheduler`.
- **Safety:** no English, no confidence number, no answer from a NEEDS_RETAKE document, no human offer until help
  routes (D18).

### 4.13 `companion-thread` — asking about it
- **Purpose:** the intelligent companion: any question, any phrasing, grounded in this patient's records.
- **Content:** header with patient chip and a scope chip when opened from a document. Family bubbles on the right,
  Medioo on the left. **Answer anatomy:** (1) first line names the patient; (2) an optional short "Medioo-র কথা" bubble
  (≤60 words, model or template). It contains **no digits, number words, timing words, condition names or medicine
  names**. (3) Record cards rendered by code: a value row from F2, a crop-first medicine row, a "ডাক্তার লিখেছেন" quote.
  Each has a source chip "১২ সেপ্টেম্বরের ল্যাব রিপোর্ট ↗". (4) The urgency banner from F1 for every cited THIS_WEEK/NOW
  document, prefixed with its date when older than this week ("১২ মার্চ ২০২৪-এর রিপোর্টে:"). (5) 2–3 follow-up chips.
  (6) "ভুল মনে হচ্ছে?". Typing state "Medioo আম্মার কাগজ দেখছে…". The full answer appears at once after checks; no
  streaming. History is per (viewer, patient) (F6).
- **States:** greeting; value answer; trend answer; prescription block(s) (§7.3); dose-change refusal; which patient?
  (chips with relation + age); which test?; nothing in the papers; document PENDING / REJECTED / FAILED_SAFETY (F3:
  urgency + cards, no model text); off-topic decline; failed-closed fallback ("এটার উত্তর এখন নিশ্চিত করে দিতে পারছি
  না" + the code facts + urgency); timeout after 20 s (model answers only); allowance used up (model answers only;
  code answers continue); emergency → `emergency-card`; symptom → symptom template; visit prep (per-finding code
  templates).
- **Safety:** §7. Medicines are never in the prompt and never in model text. Chronic flags are never set from chat.
  Family statements are stored only after a tap, in their own words (D10).

### 4.14 `emergency-card` — red flag typed in chat
- **Content:** replaces the normal answer. Solid red header "এটা জরুরি হতে পারে". The +880 viewer gets: "এখন যদি এমন
  হয়, এখনই আম্মাকে নিকটস্থ হাসপাতালের জরুরি বিভাগে নিয়ে যান, অথবা ৯৯৯-এ ফোন করুন। দেরি করবেন না।" with buttons
  "৯৯৯-এ ফোন করুন" · "আম্মার কাগজ খুলুন". The non-+880 viewer gets the remote variant (§4.11): "আম্মার কাছে যিনি আছেন,
  তাঁকে এখনই ফোন করুন…" with "কাছের মানুষকে ফোন করুন" and "বাড়ির কাউকে পাঠান", and no `tel:999`. Then "সাথে নিন:
  ওষুধগুলো আর শেষ প্রেসক্রিপশন" and "আমি ডাক্তার নই — এই মুহূর্তে আমার উত্তরের জন্য অপেক্ষা করবেন না।"
- **Category variants (D11):** overdose / double dose / wrong medicine gets its own template (no "ask your
  prescriber"). Self-harm gets its own PO- and clinician-approved template. Hypoglycaemia gets its own wording for an
  insulin user.
- **States:** patient known; patient unknown (generic, still immediate); repeated (stays for N turns, no softer
  follow-up); inside onboarding or a "কার?" prompt (fires anyway).
- **Safety:** detected in code (§7.2) before patient lookup, classifier, allowance, timeout or gate. Zero model calls are
  asserted on the metered client, with `handler_path=red_flag`. Passes the gate stage as `Kind.EMERGENCY` (F10). The
  reviewer is notified over a channel that exists (D18). Symptom checkers miss more than 40% of emergencies
  (https://www.ncbi.nlm.nih.gov/pmc/articles/PMC9131144/).

### 4.15 `timeline` — কাগজপত্র tab
- **Content:** patient switcher; "এখন যা দেখার" strip (compact attention stack); filters সব · ল্যাব · প্রেসক্রিপশন; month
  headers ("সেপ্টেম্বর ২০২৬"). Items at least 88 px tall: thumbnail with LAB/Rx badge; title "ল্যাব রিপোর্ট · আম্মা";
  subhead "১২ সেপ্টেম্বর · নমুনা ডায়াগনস্টিক সেন্টার · ১৪টি মান, ১টি সীমার বাইরে". Urgency chip from F1: NOW and
  THIS_WEEK use their short PO-approved forms; **ROUTINE gets no chip**. Pagination ("আরও দেখুন", 20/page). "ট্রেন্ড
  দেখুন" when a DR-17 marker has 2 or more readings.
- **States:** empty; being read (skeleton + "পড়ছি…"); retake; failed; review state chip "একজন মিলিয়ে দেখছেন"
  **beside** the urgency chip, never instead of it; REJECTED/FAILED_SAFETY (urgency chip only); upload date used
  ("(আপলোডের তারিখ)"); share member.
- **Safety:** no model summary on the timeline.

### 4.16 `document-detail` — everything on the paper
- **Content:** segmented control মান · ওষুধ · ডাক্তার লিখেছেন · মূল কাগজ (no-JS anchors).
  - **মান:** every value from F2: range bar only when INSIDE/OUTSIDE/CRITICAL with both limits parsed; the written range
    text (`ref_text`); a chip with icon + text; crop → `value-detail-sheet`.
  - **ওষুধ:** **crop first and large**, then the verbatim line (name · dose · frequency · timing · duration as written)
    as a smaller caption, with no colour or status. The tab header says "ফার্মেসিতে মূল প্রেসক্রিপশন বা এই ছবিটা দেখান
    — টাইপ করা লেখা নয়". If any line is unverified: "একটা লাইন পড়া যায়নি — মূল কাগজ দেখুন".
  - **ডাক্তার লিখেছেন:** each item verbatim with crop.
  - **মূল কাগজ:** pages full width, "বড় করে দেখুন" full-screen viewer, pinch zoom.
  Footer: "ভুল পড়েছে?" · "কাগজটা মুছুন" (dark, confirm sheet).
- **States:** unverified notice (DR-28); empty section hidden; share member (no delete); deleted → toast and back.
- **Safety:** verbatim escaping; signed image URLs per request (`WebUiHandler.java:639-660`); referral stays hidden
  (`:718-724`) unless the PO rules otherwise.

### 4.17 `value-detail-sheet` — one value, close up
- **Content:** large crop (pinch zoom + "বড় করে দেখুন"); value and unit as written (28 px); "রিপোর্টে লেখা সীমা
  0.7–1.3"; chip with text; "এই পরীক্ষা কী মাপে" — one sentence from the clinician-signed glossary (D3), labelled "সাধারণ
  তথ্য" and hidden until D3 is signed; earlier readings of the same marker and unit; "ট্রেন্ড দেখুন"; "ভুল পড়েছে?".
  (The v1 "add to question list" action is removed; the list holds only the family's own words, D10.)
- **States:** no written range; UNKNOWN verdict (no bar, "কাগজে মিলিয়ে দেখুন"); no glossary entry; one reading.
- **Safety:** the glossary says what a test measures, never what this result means, and has no digits.

### 4.18 `trends` — chronic markers over time
- **Content:** marker picker (DR-17 markers the patient has, `docs/DECISIONS.md:245-257`). Line chart drawn with server
  SVG geometry or quantised classes (no inline style; CSP). A date on every point, each report's written range as a
  band at its own point, and one code sentence ("পর পর তিনবার বেড়েছে" / "আগের বারের চেয়ে কম এসেছে"), never
  better/worse. A list of readings with source chips.
- **States:** one reading; mixed units (only same-unit plotted); ranges disagree ("রিপোর্টভেদে সীমা আলাদা"); three-rise
  pattern → **the exact `nudge.trend` action line "এ সপ্তাহের মধ্যে একজন ডাক্তার দেখান।"**
  (`messages_bn.properties:68`), so one surface does not soften another. When any point's date is
  `document_date_uncertain` or falls back to upload date, **the direction sentence is dropped**.
- **Safety:** no predictions or targets (`DefaultPromptTemplateEngine.java:346-354` excluded); never NOW from a trend
  (DR-18).

### 4.19 `reminders` — what's due
- **Content:** "সামনে যা আছে": follow-ups the doctor wrote (verbatim instruction + date + crop) and BMX-8 reminders.
  "Medioo কীভাবে মনে করায়": at most 2 a week, between 09:00 and 20:00 your time (F7, D29), stop any time. A switch
  "আম্মার রিমাইন্ডার" that can turn reminders back on (needs a new `NudgeOptOutPort` method). Past reminders.
- **States:** nothing due; off; deferred, never dropped (DR-20); WhatsApp not connected.
- **Safety:** no medicine-time reminders (DR-16). Buttons are "ঠিক আছে" · "পরে মনে করান" · "রিমাইন্ডার চাই না"
  ("বন্ধ করুন" is a forbidden phrase, `BaymaxProperties.java:186`).

### 4.20 `share-family` — add a sibling
- **Content:** "আম্মার কাগজ আর কাকে দেখাবেন?"; number + name ("ভাইয়া"); role [PO D30]: "শুধু দেখবেন" or "কাগজ পাঠাতে
  পারবেন". Neither role can delete. Each person has their own login and receives the same messages (D19). Member list
  (আমন্ত্রিত · যুক্ত · শেষ দেখেছেন ১৪ অক্টোবর) with "সরিয়ে দিন". The owner sees uploads by others on home: "রুমানা একটা
  কাগজ পাঠিয়েছেন".
- **States:** none; one member (v1 limit one, `FamilyAccountService.java:94-107`); invite pending; member left; viewer is
  a share member ("আপনাকে যোগ করেছেন তানভীর" + "আমাকে সরিয়ে দিন").
- **Feasibility:** share members cannot sign in today. Login needs a `family_account` (`WebAuthService.java:80,
  118-123`), `web_session.family_id` is NOT NULL (V8), and delivery goes only to `family.whatsappNumber`
  (`WhatsAppMessageDelivery.java:50`). Phase 5 adds sibling accounts, a per-recipient delivery table,
  `removeShareMember` and share-member recognition (size L).

### 4.21 `patient-profile` + account — পরিবার tab
- **Content:** patient cards; per patient: name, relation, age, sex, "কাছের মানুষ", "দীর্ঘমেয়াদি অসুখ" checkboxes
  (asked, never inferred), who can see, consent record, reminders, "আম্মার সব তথ্য মুছুন" (dark). Account: name, number,
  **time zone** (F7), ভাষা, "আপনার তথ্য কোথায় থাকে" (D16), সাহায্য, শর্তাবলি, সাইন আউট, "সবকিছু মুছে ফেলুন"
  (two-step, "২৪ ঘণ্টার মধ্যে, শেষ হলে জানাব").
- **States:** saved; delete confirm (names exactly what goes); in progress/done; share member (no delete).
- **Safety:** destructive buttons are dark, never red. Delete sweeps every family/patient table
  (`FamilyDeletionAcceptanceTest` derives the list from `information_schema`). Each new table (`chat_message`,
  `conversation_state`, `inbound_message`, `signup_intent`, `delivery_recipient`, `value_verdict`) is named in the phase
  that adds it and added to both hand-written delete paths (`PostgresHealthRecordAdapter.java:134-166`).

### 4.22 `demo-family` — sample family, read-only [PO D38, phase 7]
- **Purpose:** let Tanvir show Medioo to a friend without sign-up.
- **Content:** a banner "নমুনা পরিবার — আসল তথ্য নয়". A fictional family (DR-36) with three synthetic reports (one
  THIS_WEEK, one NOW, one retake) and a prescription. A scripted chat runs against the demo records with zero model calls.
- **Safety:** no write paths; no WhatsApp; fictional facility names only.

---

## 5. The full web journey (first visit to daily use)

The persona is a fictional family member. In production, any number other than Tanvir's waits for the Phase 5 entry
criteria (§12).

| # | Step | The family member | Medioo | Screens |
|---|---|---|---|---|
| 1 | Discover | Opens the link on an Android phone | Landing tells the story; "শুরু করুন" (and the demo, later) | landing |
| 2 | Sign in | Types his WhatsApp number, taps "WhatsApp খুলে কোড আনুন", presses Send on "কোড", types the code | Code minted for the verified sender in-window; nothing pushed | sign-in-number, sign-in-code |
| 3 | Consent (new, phase 5) | Name, three lines, "রাজি আছি" | Terms logged once | welcome-consent |
| 4 | Add a parent | "আম্মা · মা · ৭০"; consent sentence; optional "কাছের মানুষ" | Ma's file, proxy consent logged | add-parent |
| 5 | First upload | Photographs a 2-page CBC, "একই রিপোর্ট" | Downscaled, sent with progress, one document | add-report-sheet, page-tray |
| 6 | Waiting | Sees the upload bar, then "পেয়েছি → পড়ছি"; may leave | Honest steps; NOW always also on WhatsApp | reading-progress |
| 7 | Result | Reads the amber banner, the out-of-range rows with crops, "what to bring" | F1 banner, F2 rows, wording only if released | result-summary / result-now / needs-retake |
| 8 | Check | Taps a crop | Large crop, written range, what the test measures | value-detail-sheet |
| 9 | Ask | "খারাপ নাকি?", "আগের বারের চেয়ে বেড়েছে?" | Patient named, record cards, banner, never good/bad | companion-thread |
| 10 | Emergency (rare) | "আম্মার বুকে ব্যথা" | Emergency card in ≤2 s; the abroad viewer gets the call-the-person-nearby action | emergency-card |
| 11 | Papers | Weeks later, scrolls Ma's papers by month | Ordered by document date; NOW/THIS_WEEK chips; ROUTINE has no chip | timeline |
| 12 | At the pharmacy | Opens the prescription | **Shows the crop large: "ফার্মেসিতে মূল প্রেসক্রিপশন বা এই ছবিটা দেখান — টাইপ করা লেখা নয়"** | document-detail |
| 13 | Trends | HbA1c across three reports | Same-unit points, written ranges, direction only when dates are certain | trends |
| 14 | Reminders | Sees the follow-up date | WhatsApp reminder the day before, in his own daytime | reminders |
| 15 | Share | Adds his sister in Dhaka as "কাগজ পাঠাতে পারবেন" | Sister gets a notice and her own login | share-family |
| 16 | Owner abroad, sister uploads | Sister photographs Ma's new report on WhatsApp; the owner in London opens the app | "রুমানা একটা কাগজ পাঠিয়েছেন" on home; the owner reads and asks; times in "(ঢাকার সময়)" | companion-home, result-summary, companion-thread |
| 17 | Daily use | Opens the app, types "Hi" | Leads with any unacknowledged NOW/THIS_WEEK, else names the latest paper | companion-home |
| 18 | Settings | Ticks "ডায়াবেটিস" for Ma; stops and re-enables reminders; deletes an old report | Saved; opt-out honoured; hard delete | patient-profile |

---

## 6. The WhatsApp journey (same brain, channel adaptations)

Channel rules: plain text today (`WaGraphClient.java:92-105`). Add reply buttons (≤3, ≤20 chars), lists, mark-read +
typing (confirm they exist on the pinned `v21.0`, `application.properties:121`, or bump the version), and
interactive/button replies, which are dropped today (`WaWebhookHandler.java:112-113`)
(https://developers.facebook.com/docs/whatsapp/cloud-api/messages/interactive-reply-buttons-messages). Bubble cap 600
chars (`application.properties:142`), asserted on the **final** text after every insertion (CLAUDE.md). Pacing ≤1
message per 6 s. **HC3 on WhatsApp:** outbound is text only (`WaMessagingPort.java:10-21`), so until `sendImage` exists or
the PO rules (D24), companion answers carrying a value, trend or medicine line send **no number** on WhatsApp. They send
"আম্মার ১২ সেপ্টেম্বরের রিপোর্টের মানগুলো ছবিসহ এখানে: [link]". (Today's explanations already send numbers with only a
link, so the ruling covers them too.)

| # | Moment | User | Medioo |
|---|---|---|---|
| W1 | "Hi" from a known family | "Hi" / "সালাম" / "kemon acho" | Mark-read + typing. Mirrors the greeting ("হ্যালো!"; "ওয়ালাইকুম আসসালাম" only if the user said সালাম). **Leads with any unacknowledged NOW/THIS_WEEK from the last 30 days** (date + `urgency.*` verbatim), else names the latest paper. Buttons [রিপোর্ট পাঠাই] [শেষটা বুঝিয়ে বলো] [প্রশ্ন করি]. Template, zero model calls. |
| W2 | "Hi" from an unknown number (phase 5) | "Hi" | Onboarding: who Medioo is → name → "কার স্বাস্থ্যের কাগজ রাখব? নাম, সম্পর্ক আর বয়স" → terms + proxy consent buttons → "যেকোনো রিপোর্ট বা প্রেসক্রিপশনের ছবি পাঠান". **The red-flag check runs on every onboarding turn.** Before phase 5: `wa.unknown`. |
| W3 | A sibling writes (phase 5) | "Hi" | Recognised through their own account and share link; greeting names the shared patient; role-based actions; [আমাকে সরিয়ে দিন]. |
| W4 | A photo | One photo | **Text ack always** (`wa.received`, `messages_bn.properties:77`), after the free-tier check. No ✅ reaction: a green tick under a medical report can read as "result OK". |
| W5 | Several photos / a PDF | 3 photos in 20 s | Debounce ~30 s into one document (state survives deploys, F9); if unclear [একই রিপোর্টের পাতা] [আলাদা রিপোর্ট]. PDF ack says "ফাইলটা". |
| W6 | Which patient? (phase 5) | Photo, family has Ma and Baba | "কার কাগজ?" [আম্মা · মা · ৭০] [আব্বা · বাবা · ৭৬]. Media id held in `conversation_state`, downloaded after the answer. |
| W7 | The result | — | Short explanation, the `urgency.*` line, verbatim diagnosis and medicines (split, DR-26), buttons [বিস্তারিত] [ডাক্তারকে দেখানোর আগে] [কাগজে দেখুন]. NOW: fixed text + red-flag questions; 999 by viewer country (F7). |
| W8 | Follow-up in any phrasing | "আরো বলো", "bujhlam na", "creatinine ki normal?" | Same pipeline (§7). The first line names the patient. Values only as a link until D24. "বিস্তারিত" keeps working. |
| W9 | Medicine or dose question | "ওষুধটা কি অর্ধেক করব?" | Routed before the classifier (§7.1 step 1c). Refusal + "শেষ প্রেসক্রিপশনে যা লেখা — ছবিসহ: [link]" + "যিনি প্রেসক্রিপশন দিয়েছেন সেই ডাক্তারকে জিজ্ঞেস করুন". Zero model calls. |
| W10 | Emergency words | "ammu oggan hoye gese" | Emergency template in ≤2 s, first, `Kind.EMERGENCY`; remote variant for non-+880. |
| W11 | Voice note | 🎤 | "ভয়েস মেসেজ এখনো শুনতে পারি না — লিখে পাঠাবেন, বা কাগজের ছবি দিন।" + the approved red-flag line (`messages_bn.properties:32`). Later: STT (D13). |
| W12 | Thanks / 👍 / sticker | 👍 | If this viewer's last outbound was NOW/THIS_WEEK: "ঠিক আছে। মনে করিয়ে দিই: {urgency.*}" verbatim. Otherwise a one-line close. |
| W13 | Failure after receipt | — | A specific reply each time ("১০ পাতার বেশি…", "এই ফাইল খুলতে পারছি না…", "ছবিটা নামাতে পারিনি…"). |
| W14 | Retake | — | Escalating copy from F5 codes; "এটা গোনা হবে না". |
| W15 | Free cap | Photo | One message before any ack. |
| W16 | Under review | (none needed) | At once when PENDING and NOW/THIS_WEEK: the code notice "আম্মার নতুন রিপোর্ট পড়া হয়েছে। {urgency.*} Medioo-র ব্যাখ্যাটা একজন মিলিয়ে দেখছেন।" + red-flag line (`Kind.URGENCY_NOTICE`) [PO, amends DR-13]. Asked again later: the same template. Never the unreviewed body. REJECTED / FAILED_SAFETY: urgency + "কাগজে দেখুন" link, never "দেখা হচ্ছে". |
| W17 | Reminder in-window | — | Session message within caps, [ঠিক আছে] [পরে মনে করান] [রিমাইন্ডার চাই না]. |
| W18 | Reminder outside the window | — | Utility template without values or medicines; `nudge.silence` reworded or dropped (Marketing ~20× cost, https://developers.facebook.com/documentation/business-messaging/whatsapp/templates/template-categorization.md). |
| W19 | Late result outside the window | — | **Urgency-bearing value-free utility templates**: NOW "আম্মার নতুন রিপোর্টে এমন কিছু আছে যা এখনই একজন ডাক্তারকে দেখানো দরকার। [দেখুন]"; THIS_WEEK "…যা এ সপ্তাহের মধ্যে একজন ডাক্তারকে দেখানো দরকার। [দেখুন]"; ROUTINE `report_ready`. Test: no NOW/THIS_WEEK result goes out through a template lacking its urgency line. Templates are submitted to Meta during phase 1b. |
| W20 | Login code | "কোড" | `issueCodeFor(sender)`: a fresh code, in-window, this number only. A rate-limited sender is told so here (they have proved the number). Phase 2: authentication template. |
| W21 | Help / human | "সাহায্য" | Routed to the reviewer (D18). The reply states a time only when the route works. |
| W22 | Stop / delete | "বন্ধ", "সব মুছে দাও" | Whole-message rules only. Opt-out by category; delete two-step, owner only. |
| W23 | Off-topic | "আজ বৃষ্টি হবে?" | Polite scoped decline. |

First-contact surfaces: ice breakers ("রিপোর্ট পাঠাতে চাই", "শেষ রিপোর্টটা বুঝিয়ে বলো", "শেষ প্রেসক্রিপশনে কী লেখা",
"সাহায্য দরকার") and commands `/report`, `/prescription`, `/papers`, `/help`
(https://developers.facebook.com/documentation/business-messaging/whatsapp/business-phone-numbers/conversational-components.md).

---

## 7. Companion behaviour spec (one brain, two channels)

### 7.1 Pipeline (the order is part of the safety design)
0. **Idempotency + normalise.** Drop a duplicate provider message id (F9). NFKC, fold digits (`DocumentFacts.fold`),
   lowercase Latin, apply the Banglish table. Load the viewer (F6) and locale (F7).
1. **Safety in code, before anything else, with zero model calls, outside the allowance and timeout:**
   - **1a. Red flags** (§7.2) over the normalised text of the last N turns → `emergency-card` / W10. This runs before
     patient lookup and inside onboarding and "কার?" prompts. If the lookup fails, the generic template is used.
   - **1b. Symptom words** (ব্যথা, কষ্ট, জ্বর, বমি, রক্ত, সাড়া দিচ্ছে না, খিঁচুনি, পড়ে গেছে, মাথা ঘোরে + Banglish
     + English) → the symptom template (D11). It always carries the red-flag escalation line and never relates the
     symptom to a stored value or medicine. A classifier `mentions_symptom=false` can never suppress this.
   - **1c. Medicines.** Any turn that names one of the **active patient's stored medicines**, or a medicine word
     (ওষুধ, ট্যাবলেট, ক্যাপসুল, সিরাপ, ইনসুলিন, ইনজেকশন, ডোজ, tab, cap, tablet, dose, medicine), goes to template or
     code handlers (prescription block, dose refusal, purpose refusal) with **zero model calls**. A double or wrong
     dose is a red flag (1a), not a refusal.
2. **Rules, whole normalised message only:** greetings, thanks, help, the detail words, delete, stop, language, "কোড",
   button payloads (exact ids). "বিস্তারিত — আম্মার খুব জ্বর" is longer than the rule, so it falls through (step 1b has
   already fired).
3. **Classifier** (small model, ambiguous text only): `{intent, document_ref, marker_ref, patient_hint}`. It may only
   raise toward symptom/emergency handling. `patient_hint` becomes a confirm chip, never a lookup.
4. **Resolve the patient in code** against stored names and relations visible to this viewer. If ambiguous, ask with
   chips (name + relation + age). Every lookup after this is by patient id. (Today `WaIntakeService.java:101` reads
   `latestDoneDocument(family)`, safe only while `max-patients=1`. Fix before phase 5.)
5. **Handler** (§7.3). Code builds facts as references (`{{obs:id}}`, `{{fu:id}}`, `{{dx:id}}`, `{{doc:id}}`) from F1/F2,
   for the active patient only.
6. **Optional model wording** over the references, for `Kind.CHAT` answers whose cited documents are RELEASED/APPROVED
   (F3). References render as record cards, not inline numbers.
7. **Check the final text actually sent** (after every insertion; CLAUDE.md):
   - existing `MessageSafetyCheck` (forbidden phrases, length; `MessageSafetyCheck.java:42-93`), with English entries
     added to every list;
   - model text: zero digits; **zero number words or quantifiers** (দুই, তিন… দেড়, আড়াই, অর্ধেক, গুণ, দ্বিগুণ, ভাগ,
     শতাংশ, প্রায়, twice, double, half, triple, times, percent, fold; whole tokens, so "একজন ডাক্তার" passes). Today's
     check is `\d+` only (`:67`);
   - **condition words:** model text may not name a condition (ডায়াবেটিস, কিডনির রোগ, হার্টের রোগ, ক্যান্সার, জন্ডিস,
     রক্তশূন্যতা, CKD, anaemia…) unless it is in this turn's code-inserted `{{dx}}` or glossary line. Today this rule
     exists only in the prompt (`ExplanationService.java:347`);
   - **timing/urgency words** (এখনই, আজই, এ সপ্তাহে, পরের ভিজিটে, পরে, সময় করে, তাড়া নেই, দেরি, later, next visit,
     no rush, soon, today) are allowed only inside the appended `urgency.*`/emergency strings, which are stripped
     first, like verbatim stripping (`:52-62`);
   - **no stored medicine name** of the patient in model text;
   - every reference resolves to the active patient and this turn's context (F6);
   - **urgency presence:** the sent text contains the exact `urgency.*` string for the highest stored level among the
     cited documents. This replaces the proxy `doctor_first` check, which passes on any "ডাক্তার" (`:86-92`);
   - reassurance blacklist ("চিন্তার কিছু নেই", "ভালো আছে", "স্বাভাবিক", "nothing to worry", "fine", "normal");
   - templates are code constants with a declared digit allowlist (৯৯৯, ২৪). A derived test checks that every
     companion template key passes the check against an empty fact set (shape of `CopyInstructionsHaveHandlersTest`).
   One regeneration naming the violations, then fail closed to a code template. Never silence. Every guard is replayed
   against today's checker and must fail **on the defect** first ("number_word_not_allowed:দেড়", not an NPE).
8. **Gate awareness = F3.** Urgency always comes from F1. Model wording is used only for RELEASED/APPROVED content.
9. **Code appends the urgency line/banner** for each cited NOW/THIS_WEEK document, dated when older than this week.

### 7.2 Red-flag detection (D11, clinician + PO sign-off before production)
- **Matching:** stem/substring over the normalised text, not whole-token. The document-text matcher
  (`UrgencyService.java:172-181`) is right for documents and wrong for chat, because it misses "অজ্ঞান হয়ে গেছেন" and
  "শ্বাস নিতে কষ্ট". Add a Banglish variant table (oggan/ogyan/ojnan/behush, buke betha/batha, shash kosto). **Over-firing
  is the safe direction**, so the conditional phrasing ("এখন যদি এমন হয়…") makes a past-tense mention cost one message.
- **Required categories:** chest pain · breathlessness · unresponsive/fainting · stroke signs (face, arm, speech) ·
  seizure · major bleeding / vomiting blood / black stool · sudden severe headache · fall with head injury · fever with
  confusion or stiff neck · anaphylaxis (swelling of lips/throat after a medicine or food) · hypoglycaemia (sweating,
  shaking or confusion in an insulin user) · overdose or a wrong/double dose ("ভুল করে দুটো খেয়ে ফেলেছে") · self-harm
  (own approved template).
- **Test:** derived, over the phrase list × the inflection table × Banglish variants. Each must fire with
  `handler_path=red_flag` and zero metered calls. A negative set ("বুকের এক্স-রে রিপোর্ট") asserts its decision
  explicitly.

### 7.3 Intents (T template, C code facts, M model wording)
| Intent | Examples | H | Model calls |
|---|---|---|---|
| Red flag | বুকে ব্যথা, oggan, ভুল করে দুটো খেয়েছে | T | 0 |
| Symptom (other) | মাথা ঘোরে, জ্বর | T (+ red-flag line) | 0 |
| Greeting | Hi, হ্যালো, সালাম | T | 0 |
| Thanks / OK | ধন্যবাদ, ok, 👍 | T (+ urgency repeat) | 0 |
| Help | কী করতে পারো | T | 0 |
| Upload | photo, "কীভাবে পাঠাবো" | C | extraction only |
| Detail / explain | বিস্তারিত, আরো বলো, ki likhse | C (+M if released) | 0–1 |
| Clarify last | বুঝলাম না | M (same refs) / T | 0–1 |
| Value question | creatinine ki normal? | C (+M) | 0–1 |
| Prescription content | মা কোন ওষুধ খায়? | C: **one block per prescription**, headed "শেষ প্রেসক্রিপশনে (৩ সেপ্টেম্বর, নমুনা ক্লিনিক) যা লেখা", never merged or deduplicated; "লেখা মেয়াদ শেষ হয়ে থাকতে পারে" when `course_ending` has passed; "একটা লাইন পড়া যায়নি — মূল কাগজ দেখুন" when any line is unverified; [পুরনো প্রেসক্রিপশন দেখুন] | 0 |
| Medicine change / dose / purpose | অর্ধেক করব? Napa কীসের? | T+C | 0 |
| Compare / trend | আগের চেয়ে কমেছে? | C (+M) | 0–1 |
| Doctor's note meaning | "HTN" মানে কী? | C + signed glossary (D3) | 0 |
| Visit prep | কাল ডাক্তারের কাছে যাব | C: per-finding code templates + what to bring + the family's own saved questions | 0 |
| General health | ডায়াবেটিসে ভাত চলবে? | T | 0 |
| Off-topic | আবহাওয়া | T | 0 |
| Which patient | বাবার রিপোর্ট | C | 0 |
| Account / share / language / opt-out | ভাইকে যোগ করো, English | C | 0 |
| Delete | সব মুছে দাও | C two-step | 0 |
| Wrong / feedback | ভুল | C: marker "পরিবার ভুল বলেছেন — যাচাই হচ্ছে", to reviewer; changes no value or urgency | 0 |
| Onboarding / login code | Hi (unknown), কোড | T+C | 0 |

### 7.4 Tone and copy glossary
- Always "আপনি" to the reader. The patient by entered name via F8, or "আপনার" in self mode. Mirror greetings without
  assuming religion ("হাই" → "হ্যালো"). No time-of-day greetings.
- One idea per line, 4–6 lines on WhatsApp, ≤60 words before the cards on the web, bullets rather than "১.".
- Empathy without reassurance: "চিন্তা হওয়াটা স্বাভাবিক" yes; "চিন্তার কিছু নেই" never.
- **Glossary (one term each):** কাগজ / রিপোর্ট / প্রেসক্রিপশন, never "নথি". The patient's name, never "রোগী" (rewrite
  `ui_bn.properties:76, 96-129, 166-174`, e.g. "আম্মার জন্য বন্ধ করুন"). "রিপোর্টে লেখা সীমা", never "ছাপা সীমা" or
  "স্বাভাবিক সীমা" (rewrite `messages_bn.properties:46-48`). UNKNOWN is "কাগজে মিলিয়ে দেখুন"; no range is "রিপোর্টে
  সীমা লেখা নেই", never "তুলনা নেই" (which reads as "peerless"). The out-of-range section is "যেগুলো সীমার বাইরে", never
  "যা চোখে পড়ার মতো". The reviewer is "একজন", never "ডাক্তার". Use full month names ("১২ অক্টোবর"), and "সবকিছু" as one
  word. The tab is "কাগজপত্র".
- **English** is PO-approved fixed strings for every urgency, emergency, refusal and symptom template, never a model
  translation (`messages_en.properties:1` says "for logs and review" today). The safety lists work in English too:
  `doctorFirstMarkers` is Bangla-only (`BaymaxProperties.java:190`).

### 7.5 Memory
Always: patient card (name, relation, age, family-ticked conditions), compact document index (latest 30, with F1
levels). Focus documents chosen by code: explicit reference › open page › latest DONE › marker series
(`NudgeDataPort.observationsOf`); at most 3, DONE only. The last 6 turns of **this viewer's** thread plus code-held
state (active patient with a 30-minute expiry, document, pending clarification, language). Never: medicines, raw OCR,
unverified values, model summaries, other viewers' threads. Tables `chat_message`, `conversation_state` and
`inbound_message` carry `family_id`, `patient_id` and `viewer_id`; no message text at INFO.

### 7.6 Cost and speed (re-derived)
Per document: extraction ≈ $0.0253 (`docs/MEDIOO.md:34`) + explanation ≈ $0.004 (`:40`) + Vision $0.0015/page
(`:109`). That is ≈ $0.032 for a 2-page document, so 3 documents ≈ $0.096. Model answer ≈ $0.021 **uncached** (Sonnet 5,
low effort, https://platform.claude.com/docs/en/about-claude/pricing, fetched 2026-10-16). There is no prompt caching:
`LlmRequest` is one user and one system prompt (`elioo-llm/.../LlmRequest.java:22-32`), and elioo-llm sends no
`cache_control`. Classifier ≈ $0.0008 × ~30 turns ≈ $0.024. Against the $0.30 family-month target
(`docs/PRODUCT.md:289`): 0.30 − 0.096 − 0.024 = 0.18 → **≈ 8 model answers per month with 3 documents, ≈ 7 with 4**.
(v1's "15 × $0.021 = $0.24" was arithmetically wrong; it is $0.315.) Recommendation D8: **6 model-worded answers per
family-month**, code answers unlimited, measured via `ai_call_log` purpose `chat` with a `classify` sub-tag (or V16 adds
`classify` to the CHECK). Caching is a phase 6 option with its own measurement. Chat needs its own client config,
because effort is per client (`AnthropicLlmClient.java:94-99`). SLOs: safety and template replies p95 ≤2 s; model
answers p50 ≤6 s, p95 ≤12 s, 20 s timeout to the code fallback.

### 7.7 Example conversations (fictional; "Demoprol" is the repo's test name, `docs/MEDIOO.md:39`)

**E1 — Web, "Hi", latest document THIS_WEEK and not yet acknowledged (template, $0)**
> তানভীর: Hi
> Medioo: হ্যালো, তানভীর। আম্মার ১২ সেপ্টেম্বরের ল্যাব রিপোর্টে একটা মান রিপোর্টে লেখা সীমার বাইরে।
> [banner] এ সপ্তাহের মধ্যে একজন ডাক্তার দেখান।
> [রিপোর্টটা দেখুন] [শেষ প্রেসক্রিপশনে কী লেখা] [নতুন কাগজ পাঠাই]
> *Test: greeting + unacknowledged THIS_WEEK contains `urgency.this_week` verbatim.*

**E2 — WhatsApp, unknown number (phase 5 onboarding)**
> U: Hi
> M: হ্যালো! আমি Medioo — পরিবারের ডাক্তারি কাগজ গুছিয়ে রাখি আর সহজ বাংলায় বুঝিয়ে বলি। আমি ডাক্তার নই। শুরু করতে আপনার নামটা লিখবেন?
> U: রুমানা
> M: ধন্যবাদ, রুমানা। কার স্বাস্থ্যের কাগজ রাখব? নাম, সম্পর্ক আর বয়স লিখুন — যেমন "আব্বা, বাবা, ৭২"।
> U: আব্বা বাবা ৭২
> M: আব্বার জন্য ফাইল খুলছি। দুটো কথা নিশ্চিত করুন: আপনি Medioo-র শর্তে রাজি, আর আব্বা জানেন যে তাঁর কাগজ আপনি এখানে রাখছেন। [রাজি] [শর্তগুলো দেখি]
> U: [রাজি]
> M: হয়ে গেছে। এখন আব্বার যেকোনো রিপোর্ট বা প্রেসক্রিপশনের ছবি পাঠান — পুরনো হলেও চলবে।

**E3 — Value question, web (code facts + glossary; banner from F1)**
> U: আম্মার ক্রিয়েটিনিন কত? খারাপ নাকি?
> M (Medioo-র কথা): আম্মার ১২ সেপ্টেম্বরের রিপোর্টে এই পরীক্ষাটা রিপোর্টে লেখা সীমার বাইরে। ভালো না খারাপ, সেটা আমি বলতে পারি না — আমি ডাক্তার নই।
> [কাগজে লেখা] Creatinine 1.8 mg/dL · রিপোর্টে লেখা সীমা 0.7–1.3 · সীমার বেশি ▲ — ১২ সেপ্টেম্বরের ল্যাব রিপোর্ট ↗
> [সাধারণ তথ্য, signed glossary] ক্রিয়েটিনিন পরীক্ষা দিয়ে ডাক্তাররা সাধারণত দেখেন কিডনি কেমন কাজ করছে।
> [banner] এ সপ্তাহের মধ্যে একজন ডাক্তার দেখান। সাথে নিন: এই রিপোর্ট আর আগের প্রেসক্রিপশনগুলো।
> *On WhatsApp the card is replaced by "মানগুলো ছবিসহ এখানে: [link]" until D24.*

**E4 — Dose change plus a symptom (step 1b fires, then 1c; $0)**
> U: আম্মার প্রেশারের ওষুধটা কি অর্ধেক করে দেব? মাথা ঘোরে।
> M: আম্মার মাথা ঘোরার কথা বললেন। এখন যদি আম্মা অজ্ঞান হয়ে যান, বুকে ব্যথা বা শ্বাসকষ্ট হয়, কথা জড়িয়ে যায় — দেরি না করে নিকটস্থ হাসপাতালের জরুরি বিভাগে যান।
> ওষুধ কমানো, বাড়ানো বা বন্ধ করা — এই সিদ্ধান্ত আমি দিতে পারি না; যিনি প্রেসক্রিপশন দিয়েছেন সেই ডাক্তারই ঠিক করবেন।
> [কাগজে লেখা — ৩ সেপ্টেম্বরের প্রেসক্রিপশন, ছবি আগে ↗] Tab. Demoprol 5 · 1+0+0 · খাবারের পরে
> [আমার কথাটা প্রশ্নের তালিকায় রাখুন]
> *(If tapped, the list stores "আপনি বলেছিলেন, ১৭ অক্টোবর: আম্মার মাথা ঘোরে". Medioo writes no question linking the symptom to the medicine. The symptom template's timing for non-red-flag symptoms waits for D11.)*

**E5 — Emergency, +880 viewer (code, no model, `Kind.EMERGENCY`)**
> U: আম্মার হঠাৎ কথা জড়িয়ে যাচ্ছে, ডান হাত তুলতে পারছে না
> M: এটা জরুরি হতে পারে। এখনই আম্মাকে নিকটস্থ হাসপাতালের জরুরি বিভাগে নিয়ে যান, অথবা ৯৯৯-এ ফোন করুন। দেরি করবেন না।
> সাথে নিন: আম্মার ওষুধগুলো আর শেষ প্রেসক্রিপশন। আমি ডাক্তার নই — এই মুহূর্তে আমার উত্তরের জন্য অপেক্ষা করবেন না। [৯৯৯-এ ফোন করুন]

**E5b — Same, viewer on a +44 number**
> M: এটা জরুরি হতে পারে। আম্মার কাছে যিনি আছেন, তাঁকে এখনই ফোন করুন — তাঁকে ৯৯৯-এ ফোন করতে বা আম্মাকে হাসপাতালের জরুরি বিভাগে নিতে বলুন। দেরি করবেন না।
> বাংলাদেশে জরুরি নম্বর ৯৯৯ — বিদেশের ফোন থেকে এই নম্বরে লাগবে না। [রুমানাকে ফোন করুন] [বাড়ির কাউকে পাঠান]

**E6 — Trend, ambiguous word (ask, then code series; THIS_WEEK document)**
> U: আগের বারের চেয়ে সুগার কমেছে?
> M: আম্মার কাগজে "সুগার" বলতে দুটো পরীক্ষা আছে। কোনটা? [HbA1c] [Fasting glucose]
> U: [HbA1c]
> M: আম্মার HbA1c, কাগজে যা লেখা:
> [কাগজে লেখা] ১০ জুন — 8.9 % ↗ · ১২ সেপ্টেম্বর — 8.1 % ↗ · দুটোই রিপোর্টে লেখা সীমার বাইরে
> আগের বারের চেয়ে কম এসেছে।
> [banner] ১২ সেপ্টেম্বরের রিপোর্টে: এ সপ্তাহের মধ্যে একজন ডাক্তার দেখান। [ট্রেন্ড দেখুন]
> *(The direction sentence is code, and it is dropped if either date is uncertain. There is no "পরের ভিজিটে".)*

**E7 — Medicine purpose (refused under DR-16; step 1c, $0)**
> U: Napa কীসের ওষুধ?
> M: আম্মার কোন ওষুধ কীসের জন্য দেওয়া হয়েছে, সেটা যিনি প্রেসক্রিপশন দিয়েছেন তিনিই ঠিকঠাক বলতে পারবেন। প্রেসক্রিপশনে যা লেখা: [crop-first row ↗]

**E8 — Document under review, NOW (F3)**
> U: আম্মার নতুন রিপোর্টটা কেমন?
> M: আম্মার ১৫ অক্টোবরের রিপোর্ট পড়া হয়েছে।
> [NOW banner] এখনই ডাক্তারের কাছে যান। দেরি করবেন না।
> [কাগজে লেখা] Potassium 7.4 mmol/L · রিপোর্টে লেখা সীমা 3.5–5.1 · সীমার অনেক বেশি ↗
> Medioo-র ব্যাখ্যাটা একজন মিলিয়ে দেখছেন — সাধারণত ২ ঘণ্টার মধ্যে; রাত হলে সকালে।
> *(Test: a pending NOW shows `urgency.now` verbatim and no model text.)*

**E9 — Off-topic and general health**
> U: আজ ঢাকায় বৃষ্টি হবে? → M: এটা আমি বলতে পারব না — আমি শুধু আম্মার স্বাস্থ্যের কাগজ নিয়ে সাহায্য করি।
> U: ডায়াবেটিসে কি ভাত খাওয়া যাবে? → M: খাবারের নিয়ম মানুষভেদে আলাদা — আম্মার জন্য কী ঠিক, ডাক্তার বা পুষ্টিবিদ বলবেন। [আমার প্রশ্নটা তালিকায় রাখুন]

**E10 — Banglish "didn't understand" (clarify; values only in cards, urgency only appended)**
> U: bujhlam na, aro sohoj kore bolo
> M (Medioo-র কথা): সহজ করে বলি: আম্মার রক্তের একটা পরীক্ষা এবার রিপোর্টে লেখা সীমার চেয়ে বেশি এসেছে। কোন পরীক্ষা আর কত, নিচে কাগজে যা লেখা।
> [কাগজে লেখা] Creatinine 1.8 mg/dL · রিপোর্টে লেখা সীমা 0.7–1.3 ↗
> [banner] এ সপ্তাহের মধ্যে একজন ডাক্তার দেখান।

**E11 — Voice note** → "ভয়েস মেসেজ এখনো শুনতে পারি না — প্রশ্নটা লিখে পাঠাবেন? কাগজ হলে ছবি পাঠান।" + red-flag line.

**E12 — Which patient** → "কার রিপোর্টের কথা বলছেন? [আম্মা · মা · ৭০] [আব্বা · বাবা · ৭৬]"

**E13 — Thanks after a NOW**
> U: ok thanks → M: ঠিক আছে। মনে করিয়ে দিই — আম্মার ১৫ অক্টোবরের রিপোর্টের জন্য: এখনই ডাক্তারের কাছে যান। দেরি করবেন না।

**E14 — Double dose (red flag, not a refusal)**
> U: আব্বা ভুল করে প্রেশারের ওষুধ দুটো খেয়ে ফেলেছে
> M: এটা জরুরি হতে পারে। এখনই আব্বাকে নিকটস্থ হাসপাতালের জরুরি বিভাগে নিয়ে যান, অথবা ৯৯৯-এ ফোন করুন। কোন ওষুধ কতটা খেয়েছেন, সেই পাতা বা প্রেসক্রিপশন সাথে নিন। [৯৯৯-এ ফোন করুন] *(wording under D11)*

### 7.8 Tests the companion needs (CLAUDE.md rules)
Both directions are written before the code, and each guard is replayed red **on the defect** against today's source:
- emergency fires (derived phrase × inflection × Banglish set) / "বুকের এক্স-রে রিপোর্ট" decision asserted;
- **pending NOW shows `urgency.now` verbatim and no model text** / released NOW carries it too;
- REJECTED and FAILED_SAFETY never show "দেখা হচ্ছে";
- +44 viewer gets no `tel:999` / +880 viewer does;
- `'> 89'`, `'Positive'`, `'<100'` are UNKNOWN / `'1.0'` inside `0.7–1.3` is INSIDE;
- row reason = `urgency_reasons` entry (`|threshold:` vs `|stopgap`);
- number word "দেড়" rejected / "একজন ডাক্তার" passes; condition word rejected unless code-inserted;
- stored medicine name in the user's text → zero model calls / an unrelated word does not route;
- final text contains the urgency string for the highest cited level / a "ডাক্তার"-only body fails;
- share member cannot reach another patient by name; one viewer's "দেখেছি" does not shrink another's NOW card;
- prescription answer: one block per prescription, never merged / unverified line notice shown;
- no NOW/THIS_WEEK result through a template without its urgency line;
- phase-1a sign-in page never contains "পাঠিয়েছি"; a successful WhatsApp OTP delivery writes no code to any log;
- safety intents: zero metered calls even with the allowance at 0 and the model timing out (`handler_path=red_flag`);
- a derived test that every Meta inbound `type` has a non-silent handler;
- Testcontainers for chat tables, `inbound_message` uniqueness, the F1 no-lowering trigger and the deletion sweep.
Synthetic Bangla conversation set only. Usability sessions with 5–8 caregivers before trusting it (Bean et al., Nature
Medicine 2026, https://arxiv.org/pdf/2504.18919).

---

## 8. Design tokens (v3, evolving DR-14)

Source of truth [2026-10-17-medioo-tokens-v3.json](2026-10-17-medioo-tokens-v3.json); all 56 checked contrast pairs pass in light and dark.
- Keep DR-14: teal `#06788a`, ink `#0a2e4d`, paper `#f8f6f1`, amber `#92400e` THIS_WEEK, red `#b91c1c`, destructive
  dark (`docs/DECISIONS.md:174-222`).
- **Red is used for NOW-level urgency, critical value rows and the emergency card, on any surface** (home card,
  timeline chip, chat banner, result header). The wordmark dot is the only decorative red.
- **Value status is tri-state from F2:** `status-inside` (teal, INSIDE only) · OUTSIDE uses the **soon** tint with ink
  text and a ▲/▼ icon, CRITICAL the **now** tint · `status-unknown` (no bar, "কাগজে মিলিয়ে দেখুন"). **The v2 blue
  `status-watch` is removed**, so a row and the banner it feeds share one colour.
- **ROUTINE has no chip and no colour.**
- `outline #7d8a88` for control borders; `text-muted #4f5f5c`; `primary-pressed #05606e`; solid NOW header (amber vs red
  is ΔE 4.8 under deuteranopia).
- **Type:** every Bangla style that can wrap has line-height ≥1.5. Display 34/52, title-1 28/42, title-2 22/34, title-3
  20/32, headline 17/27, body 17/28, label-large 17/26, label 15/24, label-small 14/22. Single-line chips may sit at 1.4
  only with `chip-pad-y` 6 px. Fonts: `local("Noto Sans Bengali")` first (Android's system Bangla font), self-hosted
  subset fallback, `font-display: swap`; Latin Noto Sans 36 KB. **Total first load ≤200 KB excluding crops; JS ≤40 KB.**
- Targets 48 px; tab bar 64 px + safe area (hidden while composing); composer 56 px; `now-strip-h` 48 px.
- CSP: a **Baymax-path policy** (`font-src 'self'; script-src 'self'; connect-src 'self'`) rather than an edit to
  `STRICT_CSP` / `MEDSCRIBE_CSP` in medscribe-ai (`SecurityHeadersFilter.java:29-39`; DR-2). `style-src 'self'` blocks
  `style=""`, so range pins and trend points use SVG attributes or quantised classes. `/assets` needs `woff2` and `js`
  entries (`WebUiHandler.java:63, 207-209`). `DesignTokensTest` forbids colour literals, so SVG fills come from classes.

## 9. Component guidelines (summary)
See the structured `components` list. The essentials: a value row from F2 (bar only on parsed two-sided ranges);
crop-first medicine row; urgency banner (never dismissible, animated or delayed; dated on old documents); review-state
chip beside, never instead of, urgency; chat bubble + record card + source chip; composer (offline-tolerant); attention
card; timeline item; bottom tab bar (hides while composing); patient chip (name + relation + age); sheet; toast (never
for urgency, errors or retakes); empty state; retake card; progress steps; consent row (one sentence); trend chart;
language toggle; emergency action (country-aware).

## 10. Accessibility and platform notes
- Works without JS. JS adds chat send, polling, sheets, tray reordering, downscale and upload progress; it is shipped only
  on those pages. Tests asserting no `<script` (`BaymaxWebUiRouterTest.java:43`) become "no inline script".
- `<meta name=viewport content="width=device-width, initial-scale=1, viewport-fit=cover">`, **never `maximum-scale` or
  `user-scalable=no`** (WCAG 1.4.4). Every crop has "বড় করে দেখুন". `color-scheme`, `theme-color`, rem sizing, reflow at 200%.
- Test at 360×640 with the keyboard open: the tab bar is hidden and the NOW card is a 48 px strip, so the thread stays
  visible.
- No values or diagnoses in WhatsApp first lines or template bodies (shared phones; Sambasivan SOUPS 2018,
  https://www.usenix.org/conference/soups2018/presentation/sambasivan).

---

## 11. Open decisions for the product owner (each with a recommendation)

| # | Question | Recommendation | Why |
|---|---|---|---|
| D1 | Companion (chat) core MVP scope? | **Yes, new DR-37**; push stays the moat. | PO asked; PRODUCT §3.3 scopes chat. |
| D2 | How does the sign-in code reach the family? | **Phase 1a, free (PO amendment 2026-10-18, DR-39):** when the web sign-in names an account number that the allowlist permits and that wrote to Medioo within the service window (23 h margin inside WhatsApp's 24 h), the code goes to that WhatsApp automatically as a free-form message, and the page says "sent" only if the Graph API accepted it; otherwise the page shows the one-tap button (`wa.me/…?text=কোড`), the family texts "কোড" and `issueCodeFor(sender)` replies in-window. **Never a template** (paid) — this replaces "Phase 2: authentication template". Composite OTP port; no code in any log above DEBUG; never an open sender; at most 5 codes per number per rolling hour on both paths, counted whatever became of them (used, burned, expired), so 5 sends and 25 guesses an hour at most. | A stored code cannot be fetched (only its hash, `WebAuthService.java:82`); a push fails outside 24 h and for non-allowlisted numbers, and the open form would become a sender to arbitrary numbers. |
| D3 | May Medioo say what a test measures / what an abbreviation means? | **Yes, from a clinician-signed table only**, one meaning per entry, never ambiguous ones (MS, DM, CA, PID), labelled "সাধারণ তথ্য". **This amends DR-26** ("never explained, paraphrased"). | The safe half of what made MedScribe feel smart. |
| D4 | "What is this medicine for?" | **Keep refusing (DR-16)**; fix `landing.f.msg`; amend PRODUCT §3.4. | DR-16 is later and specific. |
| D5 | Show the fixed urgency before review, on web **and WhatsApp**? | **Yes**: the stored urgency always shows; on WhatsApp a separate `Kind.URGENCY_NOTICE` goes at once. **Amends DR-13's "Nothing PENDING is ever delivered"** in wording only: the pending body still never goes. | With gate-mode urgency (`application.properties:141`), only NOW/THIS_WEEK are ever PENDING, so hiding urgency while pending hides exactly the urgent documents. Missed urgents must be 0 (`docs/PRODUCT.md:283`). |
| D6 | Emergency replies and the gate | **Pass the gate stage as `Kind.EMERGENCY` with a never-park policy**, reviewer notified. | Delay is the harm. |
| D7 | Chat answers in weeks 0–2 | **`Kind.CHAT` through the gate stage**; code-only answers release; model wording only over released content; 100% after-the-fact review + "ভুল". | "Always ready" without bypassing HC4. |
| D8 | Chat allowance | **Code answers unlimited; 6 model-worded answers per family-month**, measured. | Re-derived §7.6: ≈7–8 fit $0.30 with 3–4 documents, uncached. |
| D9 | Self-serve sign-up in the pilot | **Yes, behind an invite list**, phase 5. | Referral-only cohorts. |
| D10 | Store what the family says | **Only after a tap, in their own words, "আপনি বলেছিলেন, তারিখ", per viewer**, for the question list only; Medioo writes no linking questions. | DR-26; prevents E4-style symptom↔medicine links. |
| D11 | Red flags | **Clinician + PO sign off**: categories and matching (§7.2), a self-harm template, the symptom-template timing ("আজই…"), the 999 wording and the abroad variant. **Entry criterion for phase 3.** | Deterministic rules; nobody has signed off triage timing yet. |
| D12 | CSP | **Baymax-path policy** with self fonts/scripts, no inline. | DR-2 keeps MedScribe's filter as-is. |
| D13 | Voice notes | Fixed reply + red-flag line now; STT later. | Never silence. |
| D14 | NOW acknowledgement | **Per viewer**; never dismissible on the document; home strip 7 days. | One sibling's tap must not hide it from another. |
| D15 | Templates outside the window | Value-free utility templates **including urgency-bearing NOW and THIS_WEEK twins**; submit in phase 1b. | A late NOW must not arrive as a neutral "report ready". |
| D16 | "Where is my data?" | An approved honest script. | Never improvised. |
| D17 | Diaspora users | Allow foreign numbers; BDT-only pricing; clarify `docs/PRODUCT.md:36`. | The persona is often abroad. |
| D18 | Human help + reviewer notification | Route "সাহায্য" and reviewer alerts to the reviewer's WhatsApp, via a utility template or a documented "reviewer keeps the window open" procedure. | `LogReviewerNotification` is the only adapter today; a WhatsApp push fails outside 24 h. |
| D19 | Sibling messaging | Share members get their own account and the same messages via a per-recipient delivery table. | `outbound_message` holds one wamid (V14). |
| D20 | Multi-photo grouping | Web tray; WhatsApp ~30 s debounce + question. | One paper = one document. |
| D21 | Screenshot/PDF retakes | Replay the harness before any gate change. | DR-27. |
| D22 | Tokens | **Approve v3** (§8). | Measured contrast, colour-blind and Bangla clipping fixes. |
| D23 | Doc hygiene | Record DR-21, DR-27–30, DR-33–35; fix DR-14 text. | Exist only in code comments. |
| D24 | HC3 on WhatsApp | **Add `sendImage` to `WaMessagingPort`** (crop attached), the phase 3 prerequisite for WhatsApp value/medicine answers. The alternative is a ruling that a link to the crop satisfies HC3. Today's explanations already send numbers with only a link, so the ruling covers them too. | HC3 is a hard constraint; propose, don't assume. |
| D25 | Reviewer SLA | Re-notify after 15 min on a pending NOW; after 60 min send the code-only template. | A parked NOW must not wait for a sleeping reviewer. |
| D26 | Can a reviewer correction lower urgency? | **Not in v1**: F1 never lowers; a confirmed misread is handled manually by the PO and recorded. | HC2 "never downgraded by any post-processing". |
| D27 | ROUTINE label | **No chip, no status word**; only "তুলে রাখা হয়েছে". | A prescription with a new diagnosis is ROUTINE (`UrgencyService.java:110-118`); "নিয়মিত" beside "Parkinson Disease" repeats the 2026-09-21 incident. |
| D28 | `urgency.now` wording | Name the patient ("এখনই আম্মাকে ডাক্তারের কাছে নিয়ে যান। দেরি করবেন না।") + `urgency.now.remote`; short chip forms `urgency.*.short`. | Today's text assumes the reader goes. |
| D29 | Nudge hours | **Deliver in the recipient's zone (09:00–20:00)**; the patient's calendar stays Dhaka. **Amends DR-18.** | 09:00 Dhaka is 04:00 in London (BST). |
| D30 | Share roles | Add "কাগজ পাঠাতে পারবেন" (upload, no delete), chosen at invite; default for the WhatsApp sibling. | Owner abroad, paper in Dhaka. PRODUCT §3.7 only forbids delete. |
| D31 | WhatsApp document links | A document-scoped signed token, 24 h, read-only, one document, same pattern as the opt-out HMAC. | The iPhone in-app browser lacks the Safari cookie; DR-23's redaction landed (`docs/DECISIONS.md:376-388`). |
| D32 | WhatsApp notice for web uploads | NOW always; THIS_WEEK default on; ROUTINE default off; per-document flag. | HC2 "when uncertain, escalate". |
| D33 | Self mode + relation chips | Approve self mode (F8) and the Bangladesh relation set. | "নিজে" broke principle 8; দাদা is ambiguous. |
| D34 | Missed-urgent gaps | **One-sided ranges ("<100", "> 500") and qualitative positives (HBsAg, Dengue NS1) can never raise urgency today** (`UrgencyService.java:349`). Ask a clinician for rules; until then they show UNKNOWN. | Target 0 missed urgents (`docs/PRODUCT.md:283`). |
| D35 | Live false-reassurance gap | **Ship in phase 0:** `canSayWithinNormalRange` requires every value INSIDE. | `ExplanationService.java:235-258` reads the model's flag, not a parsed comparison. |
| D36 | Rate-limit message | Tell only on WhatsApp, after the sender has proved the number; the web says nothing. | Keeps the identical-response contract (`WebAuthService.java:29-35`). |
| D37 | wa.me link on the sign-in page | **Yes on `/app` only**, never landing; or get a dedicated business number before phase 5. | It is Tanvir's own number (`docs/MEDIOO.md:76`). |
| D38 | Demo mode | Yes, fictional family, read-only, zero model calls (phase 7). | Lets Tanvir show it to friends. |
| D39 | English strings | PO approves English urgency/emergency/refusal/symptom strings as family-facing. | `messages_en.properties:1` is "for logs and review". |
| D40 | HC4 reading | Confirm that chat/emergency/urgency kinds passing `OutboundMessageGate.decide` with their own policy satisfies HC4. | HC4 says "passes the stage (config-driven)" (`docs/MEDIOO.md:25`). |

---

## 12. Phasing — each phase ends with something Tanvir tries on his own phone in production

Every phase is a PR to `main` (deploys to production), behind config where risky, synthetic documents only. **Until
the Phase 5 entry criteria are met, every "try" uses Tanvir's number alone** (allowlist, `docs/MEDIOO.md:118`).

**Phase 0 — "Medioo never ignores me" + fixes to what is live (WhatsApp, S–M).**
F4 split (`composeChecked`/`release`, DETAIL cache, `onText` never sends a body), tested in both gate modes. D35
(`canSayWithinNormalRange` needs INSIDE). Text router: whole-message rules, greeting template, honest fallback for any
other text ("এটা এখনো বুঝতে পারছি না — রিপোর্টের ছবি পাঠান, বা লিখুন বিস্তারিত"). **Every fallback and voice/sticker
reply carries the already-approved red-flag line** (`messages_bn.properties:32`) until the full emergency template
ships under D11. Detail synonyms; failure replies; text ack always; `inbound_message` idempotency (F9a); handling for
interactive/button/sticker/voice; Graph v21 check for typing and mark-read; the `কোড` rule + `issueCodeFor`.
*Try:* send "Hi", "kemon acho", "details", a voice note, a sticker, a DOCX, the same message twice and "কোড". Every one
is answered once, and the detail arrives once.

**Phase 1a — "I can sign in" (S–M).** `sign-in-number` and `sign-in-code` restyled on today's `baymax.css`,
inbound-first (D2), masked number, honest states, the composite OTP port, and the "never পাঠিয়েছি" and "no code in
logs" tests. *Try:* open medioo.eliooo.org/app, type his number, tap "WhatsApp খুলে কোড আনুন", Send, type the code,
see Ma's page.

**Phase 1b — "Foundations and the look" (L).** V16: F1 stored urgency + no-lowering trigger + central writer, F2
per-value verdict, F5 reason codes. Tokens v3, `local()` fonts, Baymax-path CSP, bottom tabs, কাগজপত্র with urgency
chips from F1 (no ROUTINE chip), document-date cursor, copy glossary sweep (রোগী/নথি/ছাপা সীমা). **Submit the Meta
templates now** (urgency twins, report_ready, follow_up, OTP authentication), since approval takes weeks. *Try:* see
Ma's papers with red/amber chips and nothing on routine ones, toggle English, page weight in the build report under
200 KB.

**Phase 2 — "The result is clear and checkable" (L). Entry: D5 decided.** `result-summary` and `result-now` with all
F3 states, every reason rendering and the country-aware action (F7); `needs-retake` with F5 copy and a retake button
(no partial read; the 7-day line only with the DR-25 purge job, built here with a Testcontainers test);
`document-detail` crop-first medicines; `value-detail-sheet` (glossary hidden until D3); `reading-progress` with three
steps, "taking longer" and the startup sweep (F9b); DR-26 clinical context in detail. *Try:* upload a synthetic lab
report; read the banner; tap a crop; retake a blurred photo; open a synthetic NOW from a +44 test viewer and see no 999
button.

**Phase 3 — "It answers my questions" (companion v1, code only, both channels, L). Entry: D11 signed; reviewer
notification adapter (D18); `Kind.CHAT`/`EMERGENCY` in the gate (F10); D24 decided for WhatsApp values.**
`companion-home`, `companion-thread`, `emergency-card`; steps 0–5 and 7–9 of §7.1 with zero model calls; intents:
greeting, latest report, value, prescription blocks, dose/purpose refusal, red flag, symptom, help, off-topic; F6
viewer-keyed tables; F8 name helper. *Try:* "আম্মার ক্রিয়েটিনিন কত?", "ওষুধটা অর্ধেক করব?", "Napa কীসের?", "আম্মার বুকে
ব্যথা", "ok thanks" after a NOW, on the web and on WhatsApp. Same answers; sources tappable; emergency in ≤2 s.

**Phase 4 — "Uploading is easy" (M).** `add-report-sheet`, `page-tray`, downscale (size chosen by the replay harness
first), byte progress, WhatsApp debounce (F9c), PDF ack, `notify_whatsapp` (D32). *Try:* a 2-page report as two
WhatsApp photos and three web photos on mobile data: one document each, a progress bar, one ack.

**Phase 5 — "My family can join" (L). Entry criteria, all required: `BAYMAX_GATE_MODE=urgency`; DR-23 verify token
rotated; D5 pending-state UI shipped; multi-message contract settled (`docs/MEDIOO.md:118`).** `signup_intent`,
`welcome-consent`, `add-parent` (relation chips, self mode, "কাছের মানুষ"), WhatsApp onboarding; `max-patients` raised;
which-patient chips (W6) and the patient-id fix to `WaIntakeService.java:101`; sibling accounts, the per-recipient
delivery table, `removeShareMember`, share roles (D30); recipient time zones (D29). Each new table is added to both
delete paths. *Try:* add a second (test) number to the allowlist, onboard it by saying "Hi", add Baba, share Ma with the
second phone as "কাগজ পাঠাতে পারবেন", send a photo from it, see "…একটা কাগজ পাঠিয়েছেন" on the owner's home.

**Phase 6 — "It talks like a companion" (companion v2, model wording, L). Entry: every §7.1 step-7 check live and
replayed red on the defect; allowance (D8).** Reference grounding, signed glossary (D3), clarify-last, trend wording,
Haiku classifier, `chat`/`classify` metering, the after-the-fact review queue and "ভুল". *Try:* free-form Bangla and
Banglish ("bujhlam na", "kal doctor er kache jabo"); check every number against its crop; see `# chat` in the weekly
export.

**Phase 7 — "It keeps watch" (M–L).** `trends`, `reminders` (re-enable, recipient zone), approved templates (D15),
help routing (D18), voice STT (D13), visit one-pager, signed WhatsApp links (D31), demo family (D38). *Try:* receive a
follow-up reminder template in his own daytime, tap it, ask about the HbA1c trend, open the demo on a friend's phone.

---

## 13. Review log (v1 → v2)

Three reviews of v1: **C** clinical safety, **U** UX/accessibility/Bangladesh context, **F** feasibility on today's
code. Every must-fix is addressed. Code claims were re-checked at `e4494c1` before being accepted (`UrgencyService`,
`ExplanationService`, `MessageSafetyCheck`, `ReviewGateService`, `PostgresOutboundMessageAdapter`, `WaIntakeService`,
`WebAuthService`, `LogOtpDelivery`, `BaymaxProperties`, `application.properties`, `messages_bn/en`, `MEDIOO.md`,
`DECISIONS.md`, `PRODUCT.md`). One correction to a review: the pilot number is recorded at `docs/MEDIOO.md:76` with "never
on the landing page", which made the U1 deep link a PO decision (D37) rather than a free choice.

### Must-fix — all addressed
| # | Item | Where |
|---|---|---|
| C1 | Urgency hidden under review (4 places) | F3, §4.10, §4.15, §6 W16, E8, §7.8, D5 |
| C2 | REJECTED / FAILED_SAFETY states; reviewer SLA | F3, §4.10, §4.13, §4.15, W16, D25 |
| C3 | NONE painted unknowns teal; canSay gap | principle 4, F2, §8, D34, D35 (phase 0) |
| C4 | Rows vs banner disagree | F2, §4.10, §4.11, §7.8 |
| C5 | result-now with no showable row | §4.11 per-reason renderings |
| C6 | 999 for the abroad persona | F7, §4.11, §4.14, E5b, §7.8, D28 |
| C7 | Urgency words outside `urgency.*`; replace doctor_first proxy; rewrite E1/E6/E10 | §7.1 step 7, E1, E6, E10 |
| C8 | Number words and disease names | §7.1 step 7, visit prep as code templates (§7.3) |
| C9 | Medicines via the user's text | §7.1 step 1c, step 7 |
| C10 | E4 linking question | E4, D10, §4.17 |
| C11 | Red-flag matching, categories, symptom routing | §7.2, §7.1 step 1b, D11 |
| C12 | Medicine list implies a regimen | §7.3 prescription blocks, chip renamed |
| C13 | Pharmacy from transcription | principle 7, §4.16, journey step 12 |
| C14 | Late NOW on WhatsApp | W19, D15 |
| C15 | Leaks between family members | F6, §4.6, D10, D14, §7.5, §7.8 |
| C16 | Wrong-patient answers | §7.1 steps 3–4, F6, phase 5 |
| C17 | WhatsApp values without a crop | §6 intro, D24, phase 3 entry |
| C18 | ROUTINE chip reads as healthy | §4.10, §4.15, §8, D27 |
| C19 | Partial read reassures | §4.12 (removed, rules if ever proposed) |
| U1 | OTP promise never kept | §4.3 inbound-first, "never পাঠিয়েছি" test, D2, D37 |
| U2 | Emergency written for a reader beside the patient | §4.11, §4.14, "কাছের মানুষ", share button, D28 |
| U3 | Patient-name grammar | F8 |
| U4 | "নিজে" and relation chips | F8 self mode, §4.5, D33 |
| U5 | Time zone | F7, principle 15, §4.19, D29 |
| U6 | Uploader role for the sibling in Dhaka | §4.20, §3, journey step 16, D30 |
| U7 | E1 missing urgency | E1, W1, §4.6 |
| U8 | "তুলনা নেই", "যা চোখে পড়ার মতো" | §7.4 glossary, §4.10, preview fixed |
| U9 | Upload on slow data | §4.7 downscale (after replay), §4.9 progress |
| F1 | D2 impossible (hash only, window, allowlist, open sender) | §4.3, W20, D2 |
| F2 | Log adapter contradiction | §4.3 composite port + test |
| F3 | Phase 1 too big; কোড rule in phase 0 | §12 phases 0, 1a, 1b |
| F4 | No stored urgency | F1 |
| F5 | Review contradiction | F3 (same as C1) |
| F6 | `detail()` persists and delivers | F4, phase 0 |
| F7 | result-now reason renderings | §4.11 |
| F8 | needs-retake partial/region; reason codes | §4.12, F5 |
| F9 | DR-25 purge not built | §4.12 held back; built in phase 2 |
| F10 | Share members can't sign in or receive | §4.20, D19, phase 5 |
| F11 | Allowlist blocker | §5 note, §12 header and phase 5 entry criteria |
| F12 | HC3/HC4 | F10, D24, D40, §6 intro |
| F13 | Cost arithmetic and caching | §7.6, D8 |

### Should-fix — accepted
C: rules match the whole message (§7.1 step 2); thanks after NOW repeats urgency and ✅ removed (W4, W12, E13);
greeting leads with unacknowledged urgency (§4.6, W1); dated banners on old documents (§4.13, E6); stopgap NOW: 999
only under red-flag questions (§4.11); trends use the `nudge.trend` line and drop direction on uncertain dates (§4.18);
D3 amends DR-26 with a signed table (D3); English fixed strings and English safety lists (§7.4, D39); symptom timing
under D11 with the red-flag line on every symptom reply (§7.1 1b, D11); "ভুল" changes nothing on its own (§7.3, D26);
never "ডাক্তার দেখছেন" (§7.4); red reworded for every NOW surface (§8); safety paths independent of model, allowance
and timeout, tested (principle 18, §7.8); patient chips with relation + age (§4.7, W6, E12).
U: page budget 200 KB with `local()` fonts and JS ≤40 KB (principle 14, §8); Bangla line-heights (tokens v3); one range
phrase and Latin digits on landing values (§7.4, §4.1); officialese, the কাগজপত্র tab, full month names, সবকিছু (§7.4,
§3); review-wait timing and "একজন মিলিয়ে দেখছেন" (F3, §4.15); small-viewport layout and offline composer (§3, §4.6,
§10); single number field with Bangla digits (§4.2); demo mode and a fictional facility (§4.22, D38, §4.15); signed
WhatsApp links (D31); consent sentence, হাই→হ্যালো, record card in the preview, "আম্মার ফাইল খুলুন" (§4.5, §7.4,
preview); value colour logic, with blue removed (§8, tokens); pinch zoom and "বড় করে দেখুন" (§10, §4.16, §4.17).
F: emergency entry criteria and a reviewer channel (phase 3, D18); `signup_intent` (§4.4); reading-progress three steps,
taking-longer state and sweep (§4.9, F9); inbound idempotency (F9); which-patient moved to phase 5 (§12); prescription
blocks (§7.3); template digit allowlist and "রিমাইন্ডার চাই না" (§7.1 step 7, §4.19, W17); CSP, assets and SVG
(§8); page weight (§8); LLM plumbing, a chat client and `classify` metering (§7.6); small items: masked number in the
cookie (§4.2), rate-limit decision (D36), new port methods (§4.19, §4.20), composite cursor (§3); deletion sweep tables
named per phase (§4.21); WhatsApp platform specifics and early template submission (§6, phase 1b).

### Declined or changed, with reasons
- **U should-fix "ROUTINE chip → 'পরের ভিজিটে দেখান'": declined.** It conflicts with C18 and with the C7 timing-word
  rule. ROUTINE also covers documents whose values are UNKNOWN (unparsed positives, one-sided ranges; D34), where an
  instruction to wait for the next visit would be false reassurance. ROUTINE gets no chip (D27).
- **U should-fix W4 "reaction after the first 3 documents": changed.** The text ack is always sent and the reaction is
  dropped entirely. A tick under a medical report can read as "result OK" (C should-fix), and a free in-window text costs
  nothing.
- **F should-fix "notify_whatsapp default off for THIS_WEEK web uploads": changed** to default on for THIS_WEEK (off only
  for ROUTINE, always on for NOW). THIS_WEEK is the level the pilot gates and the reviewer watches; defaulting its reach
  off contradicts HC2's "when uncertain, escalate" (D32).
- **U must-fix "the code screen polls": kept as an optional enhancement only.** Inbound-first needs no poll to work; the
  poll just changes "কোডের অপেক্ষায়" to "কোড পাঠানো হয়েছে" and reveals nothing new.
- **F must-fix "web-initiated push to numbers that have an account, falling back to the instruction": not adopted in
  phase 1a.** Inbound-first alone is simpler, has no window or allowlist failure mode, and removes the open-sender risk
  entirely. A push returns only as the phase 2 authentication template.
- **C must-fix "partial read" rules and F must-fix "remove partial read": both applied.** Partial read is removed from
  v1, and the clinical rules are recorded for any future DR (§4.12).
