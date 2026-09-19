# Pilot log

Evidence from real runs on production, kept because the fixtures that produced it do not survive. Each entry is
what actually happened, with the row states and the family-facing text, not a summary of what should happen.

---

## 2026-09-19 — DR-20 and DR-22 proven end to end: a missed appointment, twice, then fixed

The safety rules say a family is never left un-told about an appointment. This is the run that showed the rules
failing, showed them failing again one layer down, and then showed them holding. All three stages are on
production, on the same fixture, minutes apart.

### The fixture

One patient, `Synthetic Contention Patient` (family `c0111111-0000-4000-8000-000000000001`, deleted after this
run), built so two rules fire for the **same** patient and therefore contend:

- an open `follow_up` due 2026-09-21 ("Follow up after 4 days"), from a prescription dated 2026-09-17
- three `S. Creatinine` readings — 1.1 → 1.3 → 1.5 mg/dL on 2026-06-01, 2026-07-15, 2026-09-01 (92 days, over the
  14-day minimum DR-17 requires)

The earlier synthetic families each had one patient with one rule, so nothing ever contended and none of this was
reachable with them. The contention case had to be built before any of it could be seen.

### Stage 1 — the harm DR-20 exists to prevent (code `1427307`, DR-19 only)

```
rule          | status  | reason              | has_msg
follow_up_due | dropped | superseded_by_trend | f
trend         | gated   | -                   | t
```

The trend outranked the follow-up on equal urgency and the follow-up was **dropped**. `follow_up_due` fires once
per follow-up, so nothing would fire again: the family is never told about the appointment. DR-19 had deferred a
date-bound nudge refused by a *cap*, but a nudge beaten in a *tie* was still discarded — the rule was split on
mechanism rather than on consequence.

### Stage 2 — DR-20 defers it, and it is lost anyway one day later (code `604edc1`)

Day one behaved as DR-20 ruled — `follow_up_due | deferred | superseded_by_trend`. The next day's evaluation
returned **HTTP 500**:

```
Caused by: io.r2dbc.postgresql.ExceptionFactory$PostgresqlDataIntegrityViolationException:
  duplicate key value violates unique constraint "nudge_patient_id_rule_trigger_key_key"
```

leaving:

```
rule          | status  | reason              | has_msg
follow_up_due | dropped | superseded_by_retry | f      <- deferral consumed, nothing composed
```

`consumeDeferred` had already retired the deferred row when the retry's INSERT collided with that same retired
row under V10's blanket `UNIQUE (patient_id, rule, trigger_key)`. The deferral was consumed and the replacement
never written. **Same harm as stage 1, one day later, by the mechanism meant to prevent it.**

Worse, the exception aborted the *entire* evaluation, so one patient's failure cost every other family their
nudges for that run — silently. Nobody would have noticed until a pilot family asked why they had heard nothing.

### Stage 3 — DR-22, the fix, on production (code `0ec76c1`, Flyway V12)

Verified before the run: old constraint `nudge_patient_id_rule_trigger_key_key` **absent** (0 rows in
`pg_constraint`), `nudge_live_trigger_idx` present with predicate
`WHERE status <> ALL (ARRAY['dropped','failed'])`.

**Day 1** — the tie:

```
rule          | status   | reason              | has_msg | created
follow_up_due | deferred | superseded_by_trend | f       | 08:42:51
trend         | gated    | -                   | t       | 08:42:51
```

**Day 2** — the retry, `{"composed":1}` where it previously returned 500:

```
rule          | status  | reason              | has_msg | created
follow_up_due | dropped | superseded_by_retry | f       | 08:42:51   <- retired, audit kept
follow_up_due | gated   | -                   | t       | 08:44:07   <- the retry, WITH a message
trend         | gated   | -                   | t       | 08:42:51 (day 1)
```

Live rows per trigger: `follow_up_due live=1`, `trend live=1`. Exactly one active nudge per trigger, history
preserved — which is what DR-22's partial index buys over deleting the retired row.

### The two messages the family would receive

Day 1, trend:

```
Synthetic Contention Patient-এর S. Creatinine গত ৩টি রিপোর্টে একটু একটু করে বাড়ছে — 1.1 mg/dL → 1.3 mg/dL → 1.5 mg/dL (১ জুন থেকে ১ সেপ্টেম্বর)।

এ সপ্তাহের মধ্যে একজন ডাক্তার দেখান। সাথে নিয়ে যাবেন এই রিপোর্টগুলো — আমি টাইমলাইনে একসাথে রেখেছি।
এই ধরনের বার্তা আর চান না? এখানে ক্লিক করুন: https://medioo.eliooo.org/app/nudges/opt-out?p=…&t=…
```

Day 2, the follow-up that used to be lost:

```
Synthetic Contention Patient-এর ফলো-আপের তারিখ কাছে এসে গেছে: ২১ সেপ্টেম্বর।

প্রেসক্রিপশনে লেখা আছে: "Follow up after 4 days"।

সম্ভব হলে সেই দিন ডাক্তার দেখিয়ে নেবেন। সাথে করে আগের রিপোর্ট ও প্রেসক্রিপশনগুলো নিয়ে যেতে ভুলবেন না।
এই ধরনের বার্তা আর চান না? এখানে ক্লিক করুন: https://medioo.eliooo.org/app/nudges/opt-out?p=…&t=…
```

Both name the patient as the family entered them, carry Bangla day-and-month dates, and state the trend direction
in words (the 2026-09-19 copy rulings). The follow-up arrives the day *after* the trend, which is the right
order: the trend is what the family should raise at that appointment.

### Export rows

```
family_id,rule,sent,gated,held,deferred,dropped,drop_reasons,failed,replied
c0111111-…-000000000001,follow_up_due,0,1,0,0,1,superseded_by_retry:1,0,n/a
c0111111-…-000000000001,trend,0,1,0,0,0,,0,n/a
```

`deferred` and `dropped` are separate columns, as DR-19 required.

**Known limitation, not yet addressed (2026-09-19).** The `deferred` column is a **point-in-time state**, not a
count of events: a deferral consumed within the same window leaves no trace in it — above, the follow-up reads
`gated=1, dropped=1` (the retirement plus the send) and `deferred=0`, even though it *was* deferred for a day. If
the pilot needs "how often are nudges postponed", that is a different query over the audit rows
(`drop_reason = 'superseded_by_retry'` marks each consumed deferral), not this column.

### What this cost, and why it is written down

Both bugs were found by running the thing on production with a fixture built for the purpose. Neither was caught
by the unit tests, and the second was *introduced by the guard for the first*: the mocked `NudgePort` uses a
`HashSet` ledger where a duplicate `add` is a silent no-op, while Postgres raises — so
defer → consume → re-insert was green in the unit test and 500'd in production. That is now a standing rule in
CLAUDE.md (any path with a database constraint runs against a real database), together with the sharper one it
taught: **a red replay must fail ON the defect, not merely fail** — the first attempt at the isolation guard died
on a `NullPointerException` in its own Mockito matcher, was red, and proved nothing.

Guards that now hold this ground: `PostgresNudgeAdapterTest` (real Postgres, replayed red with the production
error verbatim), `aDateBoundNudgeBeatenInATieIsDeferredAndSendsTheNextDay`, and
`onePatientsFailureDoesNotStopTheRestOfTheRun`.
