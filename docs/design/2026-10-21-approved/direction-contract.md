---
version: 1
slug: "web"
primary_target: "web"
related_targets: []
---

# Medioo web app — surface brief

Mode: Operate (a family completes a task: understand a paper, act, find a past one). Users: the adult child AND the parent reading Bangla alone (PO 2026-10-20). Quality bar: Apple Health, WhatsApp.

## Direction contract

THESIS: Medioo is a conversation the family already knows how to have. Every paper arrives as a sent photo and Medioo answers with one verdict line read in two seconds; the wall calendar is where every paper and follow-up lives by date. It refuses the dashboard of identical cards.

OWN-WORLD: cool mist ground, white Medioo bubbles with tails, soft-green family bubbles, deep ink text, one Bangladesh-green brand accent. Red is reserved for NOW, amber for THIS WEEK. The calendar uses big Bangla numerals with green circles for follow-ups. Translucent header and composer sit over the thread.

STORY: the reader sees the newest verdict, taps it to see the paper and the values beside their snippets, sends the next photo from the composer, opens the calendar for history and what is due, and manages the family.

FIRST VIEWPORT: a header with the patient's avatar and name (which opens the switcher) and today's Bangla date. The thread ends on the newest exchange: the sent paper photo, then Medioo's verdict bubble led by its urgency band, two or three key values with snippets, and quick replies. A large camera button in the composer.

FORM: The Conversation (position 1 of 7, seed bb429a92), with The Wall Calendar (position 7, the roll) as the কাগজপত্র surface.

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance

AMENDMENT 2026-10-21 (PO): the conversation follows Apple Messages exactly — grey received bubbles, teal (#06788a) sent bubbles on a plain background — instead of the mist ground / white Medioo bubbles / soft-green family bubbles above. Mascot is the PO's Mio (unchanged), wordmark the PO's medioo (vector master in ~/medioo-design/logo/). Legibility floor for elderly users: every status = colour + icon/shape + word; no text under 12px; secondary text ≥ 4.5:1 in both themes (axe clean on 30 screens).

AMENDMENT 2026-10-21 (PO, recorded in the repo by DR-41): "দেখেছি" on the global NOW banner hides the banner only. Stored urgency, the paper's NOW block and WhatsApp delivery are unchanged; a new NOW paper shows the banner again; the patient keeps the red mark. The wordmark's vector master named above is committed in this folder as medioo-light.svg, medioo-dark.svg, medioo-mono-ink.svg and medioo-mono-white.svg; those files, not ~/medioo-design/logo/, are the source.

SCOPE (DR-41, engineering record, not a PO amendment): this contract and the prototypes govern look and layout only. Copy comes from the ui-*/messages_* bundles; urgency, display kind, wording, tel:999 gating and every other safety rule stay with Java and the PO.
