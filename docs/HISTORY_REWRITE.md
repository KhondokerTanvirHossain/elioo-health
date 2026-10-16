# History rewrite — removing two real patient documents and a real phone number

**Status: prepared, NOT run.** Every command below is for Tanvir to review and execute. Nothing in this file
has been applied to the repository.

## What is being removed, and why

Two **real patient medical records** were committed in `5529cb6` ("initial public release", 2026-09-11) and
have been in a **public** repository since:

| file | what it exposes |
|---|---|
| `medscribe-ai/src/test/resources/test-images/blood-test-report.png` | Named patient, UHID, age/sex, diagnostic centre, lab number, referring consultant, reporting pathologist + signature, full CBC/ESR results |
| `medscribe-ai/src/test/resources/test-images/biochemistry-test-report.png` | Named patient, age/sex, hospital, invoice/lab/admission numbers, two named staff + signatures, full electrolyte and LFT results |

Identity, identifiers and clinical results together, for two identifiable people.

**Provenance, confirmed 2026-10-06:** both images were downloaded from a Google image search, so this data
was already public before it reached this repository. That lowers the severity considerably — there is no
disclosure we caused, no one to notify, and no cache-purge request to file. **It does not change the
removal.** A public medical record is still a real person's medical record, it has no place in a test
fixture, and a repository that ships one teaches every contributor that it is acceptable.

`README.md` in that same directory says, in bold: *"**Never use real patient data** in test images… Remove
all PHI."* **The rule was written and broken in the same commit** — which is why step 6 replaces it with a
check (see `DR-36`).

Also removed: a **real personal phone number** (`01793399171`) in four test files, in Latin and Bangla digits.
Lower severity — it belongs to the maintainer, not a patient — but it is PII in a public repo and the same
rewrite clears it.

## Before you start

**The repository has been made private** while this is carried out. It reopens once step 7 verifies clean.

### What this invalidates

A root-commit rewrite changes **every** commit SHA. These are affected:

| PR | branch | state | action |
|---|---|---|---|
| **#54** | `feat/dr-31-locator-failure-is-not-a-retake` | **cannot go green** — still failing on the MinIO/quay pull, needs the GHCR mirror | **rebase after the rewrite** |
| #53 | `docs/batch2-findings` | open | rebase after |
| #51 | `feat/batch2-draft-labels` | open | rebase after |

#54 was checked at 2026-09-25 14:05 and still fails with `unauthorized: access to the requested resource is
not authorized` → `ContainerFetchException`. **It cannot be merged first.** Rebase all three after the
force-push.

Anyone else holding a clone must re-clone. There are currently **0 forks**.

## Step 1 — install the tool

```bash
brew install git-filter-repo      # or: pip3 install git-filter-repo
git filter-repo --version
```

`git-filter-repo` is the tool GitHub's own "removing sensitive data" documentation recommends. It is not
installed on this machine.

## Step 2 — work on a fresh mirror, never the working repo

```bash
cd ~/Documents/000_office                      # anywhere outside the working copy
git clone --mirror git@github.com:KhondokerTanvirHossain/elioo-health.git elioo-rewrite.git
cd elioo-rewrite.git
```

A mirror clone carries every ref. `filter-repo` refuses to run on a non-fresh clone by design, so that a
half-rewritten working repo cannot be pushed by accident.

**Keep a backup before rewriting:**

```bash
cp -R ~/Documents/000_office/elioo-rewrite.git ~/Documents/000_office/elioo-BACKUP-$(date +%Y%m%d).git
```

## Step 3 — the replacement rules

```bash
cat > /tmp/replacements.txt <<'EOF'
01793399171==>01700000000
8801793399171==>8801700000000
+8801793399171==>+8801700000000
০১৭৯৩৩৯৯১৭১==>০১৭০০০০০০০০
EOF
```

The Bangla-digit form is listed separately on purpose: a replacement written only in Latin digits leaves
`FamilyAccountServiceTest`'s Bangla case untouched, and the number survives the rewrite in a form nobody
thought to grep for.

`01700000000` is not a valid Bangladeshi mobile number (no operator uses the `017 0000` prefix), so it cannot
reach a real person.

## Step 4 — rewrite

```bash
git filter-repo \
  --invert-paths \
  --path medscribe-ai/src/test/resources/test-images/blood-test-report.png \
  --path medscribe-ai/src/test/resources/test-images/biochemistry-test-report.png \
  --replace-text /tmp/replacements.txt
```

Removes both blobs from every commit on every ref, and rewrites the number in every blob that contains it.
The `test-images/README.md` is **kept** — it documents the rule, and the rule is now enforced by CI.

Expected blobs removed (verify these hashes appear in the output):

```
af894ee051103c6a4e017e7a49c9959f158a45aa   blood-test-report.png
15497fbd7ff818b21197203f663b1613a91fead4   biochemistry-test-report.png
```

## Step 5 — check BEFORE pushing

```bash
# both paths must return nothing
git log --all --oneline -- medscribe-ai/src/test/resources/test-images/blood-test-report.png
git log --all --oneline -- medscribe-ai/src/test/resources/test-images/biochemistry-test-report.png

# the blobs must be gone entirely
git cat-file -e af894ee051103c6a4e017e7a49c9959f158a45aa 2>&1   # expect: Not a valid object name
git cat-file -e 15497fbd7ff818b21197203f663b1613a91fead4 2>&1   # expect: Not a valid object name

# every form of the number, across every revision — expect no output from all four
for p in 01793399171 8801793399171 +8801793399171 ০১৭৯৩৩৯৯১৭১; do
  echo "== $p"; git grep -I "$p" $(git rev-list --all) -- 2>/dev/null | head -3
done
```

**If any of these produces output, stop and do not push.**

## Step 6 — push

```bash
git remote add origin git@github.com:KhondokerTanvirHossain/elioo-health.git 2>/dev/null || true
git push --force --all
git push --force --tags
```

## Step 7 — verify from a fresh clone

The checks in step 5 ran against the local rewrite. This proves what the **server** now holds:

```bash
cd /tmp && rm -rf verify-clone
git clone git@github.com:KhondokerTanvirHossain/elioo-health.git verify-clone
cd verify-clone

git log --all --oneline -- medscribe-ai/src/test/resources/test-images/blood-test-report.png
git log --all --oneline -- medscribe-ai/src/test/resources/test-images/biochemistry-test-report.png
for p in 01793399171 8801793399171 ০১৭৯৩৩৯৯১৭১; do
  echo "== $p"; git grep -I "$p" $(git rev-list --all) -- 2>/dev/null | head -3
done
```

All five must return nothing.

## Step 8 — delete every clone holding the old history

```bash
rm -rf ~/Documents/000_office/elioo-rewrite.git
# the working copy must be re-cloned, not pulled — its history no longer matches
```

Keep `elioo-BACKUP-*.git` until step 7 verifies the push landed correctly, then delete it. It still contains
the images, so it stays off any shared drive in the meantime.

## Step 9 — rebase the three open PRs

Each branch must be rebased onto the rewritten `main`. Their old commits reference SHAs that no longer exist.

## What this does NOT do

- **It does not undo exposure**, but given the images came from a public Google image search there is no
  exposure we created. No GitHub Support cache-purge request is needed, and no one needs notifying — both
  confirmed by Tanvir on 2026-10-06.
- **It does not stop the next one.** A README told contributors never to commit real patient data, and the
  same commit broke the rule. That is what step 6 of the parent task fixes, and what DR-36 records: the rule
  moves from prose into a check.
