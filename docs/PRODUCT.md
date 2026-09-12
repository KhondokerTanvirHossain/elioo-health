# Baymax — Product Document, MVP v1.0

*Your family's health memory, on WhatsApp*

Owner: Khondoker Tanvir Hossain (Elio)

Status: Ideation locked — pre-development

Date: 12 September 2026

Codename note: “Baymax” is an internal codename only (Disney IP). Public brand name to be decided before launch.

> Engineering note (not part of the PO document): where engineering decisions diverge from the wording below,
> [DECISIONS.md](DECISIONS.md) wins. In particular DR-2: Comprehend Medical and Translate are *not used by Baymax*
> rather than removed; MedScribe keeps them.

## 0. Executive summary

> *Send a photo. Baymax reads it in Bangla, keeps it forever, and comes back to you on its own when something needs attention.*

Baymax is a WhatsApp-first health-memory assistant for Bangladeshi families managing a parent's care across paper reports, prescriptions and multiple hospitals. The family owner (typically the eldest child) sends photos of any medical document; Baymax extracts, explains in Bangla (text + voice), assigns an urgency level, files it to a per-patient timeline, and — the core differentiator — proactively nudges the family when a follow-up is due, a medicine changed, a chronic marker trends the wrong way, or a patient has gone quiet.

Core promise, in order: understood → never lost → acts without being asked.

### Locked decisions

| Area | Decision |
|-------------------|-------------------------------------------------------------------------------------------------------------------------------------------------|
| Ownership         | Elio (Tanvir, individual) — not Celloscope / Better Health. No company registration before pilot.                                               |
| Primary user      | Patient is the subject; the phone belongs to the family owner (eldest child). One WhatsApp number → multiple patient profiles.                  |
| Channel           | WhatsApp inbound; web timeline (OTP link). No native app in v1. Build order: web first, then WhatsApp. Pilot starts only when WhatsApp is live. |
| Language          | Bangla by default; replies as text + voice note.                                                                                                |
| Urgency model     | Three levels: routine / see a doctor this week / go now.                                                                                        |
| Safety            | May suggest what a document points to, always doctor-first. Never tells anyone to change a dose; never downgrades urgency.                      |
| Human-in-loop     | Configurable review gate, default off. Plug-and-play — attaching a reviewer is config, not a refactor.                                          |
| Pricing           | Freemium per family. Paid plan BDT 249/month. Free tier capped on both documents and features. Diaspora excluded from v1.                       |
| Payment           | Mock payment in v1; bKash/Nagad post-pilot.                                                                                                     |
| Pipeline          | Comprehend Medical and Translate removed. Single LLM extraction call; tiered models; object storage for images. Codification out of MVP.        |
| Sibling sharing   | In v1 — one extra number per patient profile.                                                                                                   |
| Pre-visit summary | Auto-sent when a follow-up date approaches.                                                                                                     |
| Data              | Delete on request within 24h. Foreign hosting (Mumbai) not disclosed proactively, never claimed otherwise; answered honestly if asked.          |
| Pilot             | 8 weeks, 2–3 families → 15–20 via referral. Handwritten prescription accuracy target ≥85%.                                                      |

## 1. Problem, personas and jobs to be done

### 1.1 Problem statement

A Bangladeshi patient's medical history lives in a polythene bag: paper reports and prescriptions from multiple hospitals, no continuity between them, no one holding the full picture — not the patient, not the family, not the next doctor. The result is repeated tests, missed rechecks, unnoticed trends, and visits to the wrong specialist. Existing digital health serves the app-literate urban patient; the majority can send a photo but cannot navigate an app.

The primary fear, validated with the pilot families: nobody understands the report or prescription, and it gets lost. Test repetition is a real but secondary pain.

### 1.2 Personas

**P1a — Self-managing patient.** Rahim, 52, shopkeeper, Mymensingh. Diabetes and hypertension. Android phone, WhatsApp/IMO for family, reads Bangla slowly. Keeps reports in a bag; sees whichever doctor is available; understands a report only as “doctor said thik ache.” Needs to know if a new report is bad and what to do before the next visit.

**P1b — Assisted patient (the dominant pilot pattern).** Rahim's mother, 74, Netrokona. Does not operate the phone; a child, grandchild, neighbour or pharmacy person sends the photo. Baymax must work when sender ≠ patient.

**P2 — Family owner (secondary user, the payer).** The eldest child, in Dhaka or another city, managing a parent's care remotely. Owns the WhatsApp number and the account. Wants the timeline and the alert, not the raw report. Keeps siblings informed.

**P3 — Receiving doctor (indirect).** Wants a one-page chronological summary, not the bag. If Baymax produces this well, doctors become a distribution channel.

### 1.3 Jobs to be done

- When a new report arrives, tell me if anything is wrong and what to do next, without waiting for a visit.
- Before I see a doctor, help me bring the right history so I am not sent away or made to repeat tests.
- Remind me what is due — recheck, medicine refill, follow-up.
- **Emotional:** stop me worrying about numbers I cannot read. Likely the biggest driver of stickiness.
- **Social:** let my family see the parent is being looked after. The bridge to P2 paying.

### 1.4 Structural consequence

> *The phone belongs to the eldest child, not the patient. Account model: one WhatsApp number → multiple patient profiles. Consent is by proxy from day one. Every message must be able to ask “Ma or Baba?”*

## 2. Value proposition and positioning

### 2.1 One-line

> *Send a photo. Baymax reads it in Bangla, keeps it forever, and comes back to you on its own when something needs attention.*

### 2.2 Value proposition canvas — family owner

| Customer side | Product side |
|-------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------|
| Jobs: interpret the report; decide urgency; prepare for the next visit; track parents across hospitals; keep siblings informed.                       | Products: WhatsApp intake; Bangla voice + text explanation; urgency tag; per-patient timeline; proactive nudges; sibling sharing; pre-visit summary. |
| Pains: cannot read reports; doctor gives 3 minutes; documents get lost; trends invisible across facilities; parent hides symptoms; guilt at distance. | Pain relievers: explanation within minutes; “already tested on X date”; three-level urgency; one-page pre-visit summary; nothing is ever lost.       |
| Gains: peace of mind on “is this bad”; one timeline to show any doctor; catch decline early; siblings on the same page.                               | Gain creators: timeline per parent; trend view for chronic markers; reminders for rechecks and refills; shareable summary.                           |

### 2.3 Positioning statement

For families managing a parent's health across paper and hospitals, Baymax is the health memory that lives on WhatsApp. Unlike hospital apps, pharmacy apps or ChatGPT, it works without an app, speaks Bangla, remembers everything, and comes back to you on its own — it tells you what to do next, not just what the report says.

### 2.4 Competitive frame

| Alternative | Why Baymax wins |
|------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Hospital apps / Praava-type              | Single-facility, app-literate, urban. Baymax is cross-facility and app-free.                                                                                                                                             |
| Arogga-type pharmacy apps                | Pharmacy + booking, no memory. Different job.                                                                                                                                                                            |
| ChatGPT / Gemini via photo               | The real substitute. Pull only — it does nothing unless asked, and families never ask. No memory, no Bangla voice, no urgency model, no accountability. Baymax's moat is initiative, continuity and safety, not the LLM. |
| Doctor-owned records (DoctorKoi/MedsTag) | Doctor-owned, single doctor. Baymax is patient-owned, all doctors.                                                                                                                                                       |

### 2.5 Trust claims to earn, not assert

- Never diagnoses on its own authority; always says when to see a doctor.
- Extracted values shown beside the original image so the family can verify.
- Encrypted, never sold, deletable on request.

## 3. Solution scope and safety model

### 3.1 Core loop — when a photo arrives

1. Acknowledge in seconds (“পেয়েছি, দেখছি…”); ask which patient if ambiguous.
2. Extract, then show key values beside a crop of the original. Never a number without its source.
3. Explain in Bangla, voice + text: what the document is, what is normal, what is not, in plain words.
4. Urgency tag: সাধারণ (routine) / এ সপ্তাহে ডাক্তার দেখান (this week) / এখনই যান (now).
5. File to the patient timeline; confirm “saved under Ma, 12 Sep.”

### 3.2 Proactive engine — the moat

- **Follow-up due:** “Dr. Halim said recheck creatinine in 4 weeks — that's next Tuesday.”
- **Medicine continuity:** a new prescription dropped or changed a medicine → flag it.
- **Trend:** a chronic marker moving the wrong way across three reports (v1: 5–6 markers, e.g. HbA1c, fasting glucose, creatinine, BP, lipids).
- **Silence:** no document in ~2 months for a chronic patient → gentle check-in. Never for acute.
- **Pre-visit:** auto-sent one-page summary when a follow-up date approaches.

### 3.3 Chat (pull)

Answers questions grounded in the patient's own timeline plus general medical knowledge. Never speculates beyond the documents.

### 3.4 What Baymax may and may not say

| May | Never |
|-------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------|
| Suggest what a report or prescription points to — always framed “this suggests X, confirm with a doctor” and grounded in what is written. | Tell anyone to start, stop or change a medicine or dose.   |
| Explain what a medicine is for, in plain Bangla.                                                                                          | Downgrade urgency. When uncertain, escalate.               |
| Say “see a doctor” plus exactly what to bring.                                                                                            | Interpret imaging beyond the radiologist's written report. |
| Say “I couldn't read this clearly — retake in daylight, flat.”                                                                            | Answer when extraction confidence is low.                  |

### 3.5 Escalation language

Always “see a doctor” plus what to bring. The “now” level additionally asks about consciousness, breathing and chest pain, and points to emergency services.

### 3.6 Review gate (human-in-loop) — plug-and-play

Every outbound message passes a pipeline stage that either auto-sends or parks in a review queue, driven by configuration: per urgency level, per patient, or global. Default off. The full system must work with no human attached; attaching a reviewer later is a config flip and a reviewer UI, not a refactor. The pilot runs with the gate on for “this week / now” messages during weeks 0–2.

### 3.7 Sibling sharing (v1)

The family owner can add one number per patient profile. Added numbers receive the same digests, can view the timeline, cannot delete.

## 4. Business model and unit economics

### 4.1 Payer and model

The family owner pays; the patient never does. Freemium per family, not per patient. Diaspora (USD pricing) is excluded from v1.

| Tier | Includes | Price |
|---------------|----------------------------------------------------------------------------------------------------------------------------------|-----------------|
| Free          | 1 patient profile, 3 documents/month, text-only explanation, timeline view. Enough to feel the “it came back to me” moment once. | BDT 0           |
| Family        | Unlimited patients and documents, Bangla voice, proactive nudges, sibling sharing, auto pre-visit summary.                       | BDT 249 / month |
| B2B2C (later) | Diagnostic centres, corporate HR bundles. Not v1.                                                                                | —               |

Nudges are the paywall — that is where the value lives.

### 4.2 Cost reality (measured 12 Sep 2026)

Current MedScribe pipeline costs ≈ \$0.55 per document (up to ≈ \$1.00 for a long lab report). Comprehend Medical is ~85% of that; Translate fans out 4 calls per row; Vision is called twice on the same bytes; SNOMED/ICD/RxNorm inference runs in duplicate. The LLM is nearly free. The \$0.072 figure returned by the API is hardcoded and must be replaced with real logging.

> *BDT 249 ≈ \$2/month. Cost target ≤ 25% of price → ≤ \$0.50 per paying family per month. At \$0.55/document the current pipeline cannot ship. The pipeline changes below are MVP architecture, not later optimisation.*

### 4.3 Pipeline decisions

- Comprehend Medical removed entirely. RxNorm is blind to Bangladeshi brands; SNOMED is invisible to the user.
- Translate removed. The LLM reads Bangla natively.
- Single LLM extraction call per document with structured output: document type, patient, date, key values with units and reference ranges, medicines, follow-up instructions, confidence.
- Tiered models: cheap model (Groq / open-weight) for extraction and explanation by default; strong model only when confidence is low or urgency ≥ “this week.”
- Images to object storage; extraction JSON in Postgres. No base64 in the database.
- Real per-document cost, model used and confidence logged from day one.
- Codification (ICD-10/SNOMED) out of MVP; revisit only if it feeds trend detection or a B2B product.

### 4.4 Projected unit economics (verify in pilot)

| Item | Paid family / month | Free family / month |
|--------------------------------------|-------------------------------------------------|---------------------------|
| Documents                            | ~4 × \$0.01–0.15 blended = \$0.04–0.15          | ≤3 × cheap model ≈ \$0.02 |
| Nudges (WhatsApp business-initiated) | ~6 × \$0.01–0.02 (verify current Meta BD rates) | None                      |
| Bangla TTS                           | ≈ \$0.01                                        | None                      |
| Infra share                          | ≈ \$0.03 at scale                               | ≈ \$0.01                  |
| Total                                | ≈ \$0.15–0.30 (under target)                    | ≈ \$0.03–0.05             |

Roughly 10 free families can be carried per paid family.

### 4.5 Revenue realism

Small cheque, high volume. 10,000 paying families ≈ BDT 2.5–3 Cr/year. A real Elio business, not a Celloscope-scale one. The strategic upside is the data asset: patient-owned longitudinal records.

### 4.6 Questions the pilot must answer

- Documents per family per month; nudges per family per month.
- Free → paid conversion after the first proactive nudge.
- Churn after a parent's acute episode ends.

## 5. Journey map and MVP cut

### 5.1 Onboarding (WhatsApp, zero forms)

Family owner messages “Hi” → Baymax asks their name → “Whose health will I keep track of?” → creates the first patient profile from a name and age → “Send me any report or prescription, even an old one.” First value in under two minutes.

### 5.2 Core loop

Photo → ack → extract + verify-crop → Bangla voice + text explanation → urgency → saved to timeline → (later) proactive nudge → optional chat.

### 5.3 Build order

Web first (extend existing MedScribe: family accounts, patient profiles, timeline, review gate, cost logging), then the WhatsApp channel. The pilot does not start until WhatsApp is live — a web-only pilot tests the wrong thing.

### 5.4 MVP — in

1. WhatsApp inbound: photo, PDF, multi-page.
2. Family account, multi-patient profiles, “which patient?” disambiguation.
3. LLM extraction with confidence gate and retake prompt.
4. Bangla explanation, text + voice note.
5. Three-level urgency with doctor-first framing.
6. Timeline per patient as a web link (OTP, no password).
7. Proactive nudges: follow-up due, medicine changed, silence check-in; trend detection for 5–6 chronic markers.
8. Sibling sharing (one number per profile).
9. Auto pre-visit one-page summary.
10. Paywall with free caps; mock payment.
11. Configurable review gate, default off.
12. Per-document cost and error logging.

### 5.5 MVP — out, explicitly

Native app · diaspora / USD pricing · diagnostic-centre integration · imaging interpretation · medicine ordering · doctor booking · codification · bKash/Nagad (post-pilot) · any English-first UX.

## 6. Compliance, data and trust

### 6.1 Legal posture

Baymax is an information and record-keeping service, not medical practice. No BMDC exposure as long as it never prescribes. Terms state explanations are educational; every urgency message carries “confirm with a doctor.” Operated by Tanvir as an individual through the pilot; entity formation deferred until traction.

### 6.2 Consent

- Family owner accepts terms at onboarding — one WhatsApp reply, logged with timestamp.
- Proxy consent per patient profile: “I am authorised to manage Ma's health records.” One tap, logged.
- Added sibling receives a notice and can leave with one message.

### 6.3 Data rights

- Export: full timeline as PDF on request.
- Delete: any document, profile or the whole account — one message, executed within 24 hours, confirmed back.
- Retention: keep forever by default (that is the promise); delete on request.

### 6.4 Data handling

- Images encrypted at rest in object storage; extraction JSON in Postgres.
- LLM calls only under zero-retention / no-training terms. Verify the Groq data policy before pilot — free tiers often lack this.
- Phone number is the identity; never sold, never used beyond product messages.
- Hosting is currently in Mumbai. Not disclosed proactively; the product never claims Bangladesh residency; answered honestly if a family asks. Move to a BD provider if it becomes a trust or regulatory issue.

### 6.5 Regulatory watch (verify, don't assume)

- Status of Bangladesh's personal data protection law.
- Meta's WhatsApp Business healthcare policy — message types and template categories allowed. Both can change template design.

### 6.6 Trust surface

- Extracted values shown beside the crop of the original.
- Confidence honesty: “I read this clearly” vs “I'm not sure about line 3 — please check.”
- Baymax never presents itself as a doctor.
- A “wrong” reply option on every message; every correction logged and reviewed.

## 7. Pilot design, metrics and kill criteria

### 7.1 Cohort and duration

Weeks 0–2: the 2–3 known families, Tanvir as reviewer, gate on for urgent messages. Weeks 2–4: expand to 15–20 families via those families' referrals only. No public launch. Total 8 weeks — long enough for at least one follow-up date to come due per chronic patient, so the proactive loop is judged on a real “it came back to me” moment.

### 7.2 Success metrics

| \# | What it proves | Target |
|--------|---------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------|
| 1      | Activation — family sends ≥1 document within 48h of onboarding                              | 80%                                                                                |
| 2      | Understanding — family can say what the document means and what to do (asked one day later) | 70%                                                                                |
| 3      | Accuracy — extracted key values match the original                                          | ≥95% printed; ≥85% handwritten; zero wrong values in any “this week / now” message |
| 4      | Urgency precision — reviewed by Tanvir in weeks 0–2                                         | False urgents \<10%; missed urgents 0                                              |
| 5      | Initiative — nudges that get a reply or an action                                           | ≥50%                                                                               |
| 6      | Retention — families sending a document in weeks 5–8 unprompted                             | ≥60%                                                                               |
| 7      | Willingness to pay — BDT 249 plan presented at week 6 (mock payment)                        | ≥30% say yes                                                                       |
| 8      | Cost — real per-document and per-family-month                                               | ≤\$0.15 / document; ≤\$0.30 / family-month                                         |

### 7.3 Kill / pivot criteria

| Signal | Meaning | Action |
|---------------------------------------------------|-------------------------------------------------|-------------------------------------------------------|
| Activation \<50%                                  | Channel or onboarding is wrong, not the product | Fix intake before anything else                       |
| Accuracy missed after two model/prompt iterations | Extraction not trustworthy                      | Do not scale; narrow document types or fix extraction |
| Nudge response \<25%                              | Initiative not valued; it's a filing cabinet    | Reprice as storage; rethink proactive engine          |
| WTP \<15%                                         | Consumer paid model fails                       | Revisit B2B2C before more consumer build              |

### 7.4 Instrumentation from day one

- Per document: cost, model used, confidence, review outcome, corrections.
- Per family: documents, nudges sent/answered, days since last document.
- Weekly one-page pilot log.

### 7.5 Post-pilot decision

Green on metrics 1–6 → build bKash/Nagad, drop the gate to config-only, open referral growth. Green on 7 → price holds. Any red → the specific pivot above.

## 8. Open items before development

| Item | Owner | When |
|-------------------------------------------------------------------------------------|-----------|------------------------------------|
| Public brand name (Baymax is codename only)                                         | Tanvir    | Before public launch               |
| Verify Groq zero-retention / no-training terms; pick fallback strong model          | Tanvir    | Before pilot                       |
| Verify current Meta WhatsApp BD conversation pricing and healthcare template policy | Tanvir    | Before WhatsApp build              |
| Confirm the 5–6 chronic markers for v1 trend detection                              | Tanvir    | Before extraction schema is frozen |
| Bangla TTS provider and voice selection                                             | Tanvir    | Before WhatsApp build              |
| Replace hardcoded \$0.072 cost with real logging                                    | Tanvir    | First engineering task             |
| Bangladesh data protection law status check                                         | Tanvir    | Before public launch               |
