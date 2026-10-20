# Medioo — approved design, 2026-10-21

What the PO approved before the Next.js rebuild. The rebuild implements this; when a screen and this folder disagree
on look and layout, this folder wins until the PO approves a change (record it as an amendment in `direction-contract.md`).
It never wins on copy or safety: copy comes from the `ui-*`/`messages_*` bundles, and urgency, wording, `tel:999`
gating and every other safety rule from Java (DR-41 lists the places where the prototypes differ).

| File | What it is |
|---|---|
| `phone.html` | Phone prototype (iPhone frame, four tabs). Open in a browser; the bar above the phone switches scenes. |
| `desktop.html` | Desktop web prototype ("paper and talk": sidebar · paper · conversation). |
| `direction-contract.md` | The impeccable direction contract, with the 2026-10-21 amendment (Messages grammar, Mio, legibility floor). |
| `mio.webp`, `mio-head.webp` | **Mio**, the mascot. Made by the PO (Tanvir Hossain) for Medioo; used exactly as drawn, stethoscope included (PO, 2026-10-20). |
| `medioo-light.svg`, `medioo-dark.svg` | The medioo wordmark, vector master traced from the PO's `medioo.webp` (letters and colours unchanged). Light ground and dark ground. |
| `medioo-mono-ink.svg`, `medioo-mono-white.svg` | Single-colour wordmark for one-colour uses (WhatsApp profile picture, stamps, print). |

Everything inside the prototypes is fictional: patients, papers, values, numbers, the any-six-digits code. The scene bar,
the canned chat answers and the timed "পড়া হয়েছে" receipts are prototype devices and must not ship
(see the rebuild plan's Global Constraints).
