#!/usr/bin/env python3
"""
The scorer must pair a label row with the RIGHT extracted row, not merely a same-named one.

    python3 scripts/test_score_pairing.py

lab1's differential prints each cell type twice — an absolute count and a percentage:

    Neutrophil  2.25  10^3/uL        Neutrophil  41.9  %
    Lymphocyte  2.18  10^3/uL        Lymphocyte  40.7  %

The label carries all ten rows. So did the extraction. The scorer still reported eight of them wrong,
because `match_values` took the FIRST row whose name matched and removed it from the pool: the label's
absolute row paired with the extraction's percentage row, both readings were then "wrong", and a correct
extraction scored as a failure. lab2 has the same shape with two "Others" rows, one chemical and one
microscopic.

This is the alias matcher's defect one layer up — keying on a field that is not unique. There the fix was
whole-word matching plus specimen; here it is pairing on (name, unit) and then on section, and treating a
naming variant like "Neutrophil %" as the same row as "Neutrophil" + unit "%".

Both directions in every test: the two same-named rows must pair CORRECTLY, and two genuinely different
rows that happen to share a name and a unit must NOT be merged into one.
"""
import sys

sys.path.insert(0, "scripts")
from score_extraction import match_values  # noqa: E402

FAILURES = []


def check(name, condition, detail=""):
    if condition:
        print(f"  PASS  {name}")
    else:
        print(f"  FAIL  {name}{(' — ' + detail) if detail else ''}")
        FAILURES.append(name)


def value(name, val, unit=None, **extra):
    row = {"name": name, "value": val, "unit": unit}
    row.update(extra)
    return row


def test_the_lab1_differential_pairs_by_unit():
    """The case that produced eight false misses."""
    label = [value("Neutrophil", "2.25", "10^3/uL"), value("Neutrophil", "41.9", "%"),
             value("Lymphocyte", "2.18", "10^3/uL"), value("Lymphocyte", "40.7", "%")]
    extracted = [value("Neutrophil", "41.9", "%"), value("Neutrophil", "2.25", "10^3/uL"),
                 value("Lymphocyte", "40.7", "%"), value("Lymphocyte", "2.18", "10^3/uL")]

    hits, misses, extra = match_values(label, extracted)

    check("all four differential rows pair correctly", hits == 4, f"hits={hits}, misses={misses}")
    check("nothing is reported as invented", extra == 0, f"extra={extra}")


def test_the_naming_variant_is_the_same_row():
    """Extraction may name the percentage row "Neutrophil %"; the label calls it "Neutrophil" + unit %."""
    label = [value("Neutrophil", "2.25", "10^3/uL"), value("Neutrophil", "41.9", "%")]
    extracted = [value("Neutrophil", "2.25", "10^3/uL"), value("Neutrophil %", "41.9", "%")]

    hits, misses, extra = match_values(label, extracted)

    check("'Neutrophil %' matches 'Neutrophil' + unit %", hits == 2, f"hits={hits}, misses={misses}")
    check("and is not counted as an extra row", extra == 0, f"extra={extra}")


def test_lab2_others_rows_pair_by_section():
    """Two rows, same name, same (absent) unit — told apart only by the section they sit in."""
    label = [value("Others", "Nil", None, specimen="urine", section="chemical"),
             value("Others", "Nil", None, specimen="urine", section="microscopic")]
    extracted = [value("Others (Microscopic)", "Nil", None),
                 value("Others (Chemical)", "Nil", None)]

    hits, misses, extra = match_values(label, extracted)

    check("both Others rows pair", hits == 2, f"hits={hits}, misses={misses}")
    check("neither is reported as invented", extra == 0, f"extra={extra}")


def test_two_different_rows_sharing_name_and_unit_are_not_merged():
    """
    THE DANGEROUS DIRECTION. If pairing gets loose enough to fix the above, it can also collapse two rows
    that really are different — and a missing row would then score as present.
    """
    label = [value("Glucose", "5.2", "mmol/L"), value("Glucose", "8.9", "mmol/L")]
    extracted = [value("Glucose", "5.2", "mmol/L")]

    hits, misses, extra = match_values(label, extracted)

    check("only one of the two pairs", hits == 1, f"hits={hits}")
    check("the second is reported missing, not silently matched", len(misses) == 1, f"misses={misses}")


def test_a_wrong_reading_is_still_wrong():
    """Pairing correctly must not make a wrong value look right."""
    label = [value("Neutrophil", "2.25", "10^3/uL")]
    extracted = [value("Neutrophil", "9.99", "10^3/uL")]

    hits, misses, _ = match_values(label, extracted)

    check("a mis-read value still counts as a miss", hits == 0 and len(misses) == 1,
          f"hits={hits}, misses={misses}")


def test_the_ordinary_single_row_case_is_unchanged():
    """The overwhelmingly common shape must behave exactly as before."""
    label = [value("Haemoglobin", "12.9", "g/dL"), value("ESR", "29", "mm/hr")]
    extracted = [value("ESR", "29", "mm/hr"), value("Haemoglobin", "12.9", "g/dL")]

    hits, misses, extra = match_values(label, extracted)

    check("distinct names still pair regardless of order", hits == 2 and extra == 0,
          f"hits={hits}, misses={misses}, extra={extra}")


def test_a_genuinely_invented_row_is_still_counted():
    label = [value("Haemoglobin", "12.9", "g/dL")]
    extracted = [value("Haemoglobin", "12.9", "g/dL"), value("Creatinine", "1.1", "mg/dL")]

    hits, _, extra = match_values(label, extracted)

    check("an extracted row with no label counts as invented", hits == 1 and extra == 1, f"extra={extra}")


def test_micro_sign_and_greek_mu_are_the_same_unit():
    """
    The label writes MICRO SIGN (U+00B5); extraction writes GREEK SMALL LETTER MU (U+03BC). Identical on
    the page, different code points. This pair already manufactured eight false unit misses in v1 and did
    it again here — pairing on unit makes it worse, because now the rows do not even meet.
    """
    label = [value("WBC total count", "5.36", "10^3/\u00b5L")]
    extracted = [value("WBC total count", "5.36", "10^3/\u03bcL")]

    hits, misses, _ = match_values(label, extracted)

    check("10^3/µL and 10^3/μL are one unit", hits == 1, f"hits={hits}, misses={misses}")


def test_a_specimen_on_one_side_only_does_not_block_pairing():
    """
    v2 records `specimen` and the labels predate the field. If a section present on one side and absent on
    the other blocked pairing, adding a field to extraction would silently destroy the score — which is
    exactly what happened.
    """
    label = [value("Neutrophil", "2.25", "10^3/uL"), value("Neutrophil", "41.9", "%")]
    extracted = [value("Neutrophil", "2.25", "10^3/uL", specimen="blood"),
                 value("Neutrophil", "41.9", "%", specimen="blood")]

    hits, misses, extra = match_values(label, extracted)

    check("an unlabelled specimen still pairs", hits == 2, f"hits={hits}, misses={misses}")
    check("and nothing is counted as invented", extra == 0, f"extra={extra}")


def main():
    tests = [test_the_lab1_differential_pairs_by_unit,
             test_micro_sign_and_greek_mu_are_the_same_unit,
             test_a_specimen_on_one_side_only_does_not_block_pairing,
             test_the_naming_variant_is_the_same_row,
             test_lab2_others_rows_pair_by_section,
             test_two_different_rows_sharing_name_and_unit_are_not_merged,
             test_a_wrong_reading_is_still_wrong,
             test_the_ordinary_single_row_case_is_unchanged,
             test_a_genuinely_invented_row_is_still_counted]
    for test in tests:
        print(f"\n{test.__name__}")
        test()
    print()
    if FAILURES:
        print(f"{len(FAILURES)} FAILED: {', '.join(FAILURES)}")
        sys.exit(1)
    print("all green")


if __name__ == "__main__":
    main()
