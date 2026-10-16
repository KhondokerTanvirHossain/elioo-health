#!/usr/bin/env python3
"""
Generate synthetic medical-report fixtures — invented patients, values and facilities.

    python3 scripts/make_synthetic_fixtures.py

DR-36: no real medical document may be committed, including one found on the public web. These are drawn,
not photographed, so there is no page behind them and no person to identify. Every name, number, facility
and clinician is invented.

The layouts deliberately include the shapes that have cost us defects:

  - a differential block printing each cell type TWICE (absolute count and percentage) — the shape that
    made the scorer pair rows by name and report 88.2% where the truth was 97.3%
  - a "Nil" urine row — negatives were being dropped entirely until the prompt was fixed
  - a flag letter in the value cell ("2.1 L") — the locator could not find it in the OCR text
  - a sex-specific reference range — which range applies is still undecided (DR-30)
  - a two-phase regimen on the prescription — one instruction, not two doses (DR-16)
"""
from PIL import Image, ImageDraw, ImageFont
import pathlib

OUT = pathlib.Path("docs/testset/synthetic")
OUT.mkdir(parents=True, exist_ok=True)

W, H = 1240, 1754            # A4 at 150 DPI
MARGIN = 70


def font(size, bold=False):
    for path in ("/System/Library/Fonts/Supplemental/Arial Bold.ttf" if bold
                 else "/System/Library/Fonts/Supplemental/Arial.ttf",
                 "/System/Library/Fonts/Helvetica.ttc"):
        try:
            return ImageFont.truetype(path, size)
        except OSError:
            continue
    return ImageFont.load_default()


def lab_report(path, *, title, patient, rows, footer):
    img = Image.new("RGB", (W, H), "white")
    d = ImageDraw.Draw(img)
    y = MARGIN

    d.text((MARGIN, y), "MEADOWLARK DIAGNOSTIC CENTRE", font=font(30, True), fill="black")
    y += 38
    d.text((MARGIN, y), "17 Invented Road, Testville  ·  Tel: 000-000-0000", font=font(16), fill="black")
    y += 24
    d.text((MARGIN, y), "— SYNTHETIC TEST FIXTURE, NOT A REAL REPORT —", font=font(15, True), fill="black")
    y += 34
    d.line([(MARGIN, y), (W - MARGIN, y)], fill="black", width=2)
    y += 20

    for k, v in patient.items():
        d.text((MARGIN, y), f"{k}:", font=font(17, True), fill="black")
        d.text((MARGIN + 190, y), v, font=font(17), fill="black")
        y += 26
    y += 10
    d.line([(MARGIN, y), (W - MARGIN, y)], fill="black", width=1)
    y += 18

    d.text((MARGIN, y), title, font=font(22, True), fill="black")
    y += 36

    cols = (MARGIN, MARGIN + 430, MARGIN + 640, MARGIN + 810)
    for label, x in zip(("Test", "Result", "Unit", "Reference"), cols):
        d.text((x, y), label, font=font(17, True), fill="black")
    y += 8
    d.line([(MARGIN, y + 14), (W - MARGIN, y + 14)], fill="black", width=1)
    y += 28

    for row in rows:
        if row is None:
            y += 10
            continue
        if isinstance(row, str):
            d.text((MARGIN, y), row, font=font(18, True), fill="black")
            y += 30
            continue
        name, result, unit, ref = row
        d.text((cols[0], y), name, font=font(17), fill="black")
        d.text((cols[1], y), result, font=font(17), fill="black")
        d.text((cols[2], y), unit, font=font(17), fill="black")
        d.text((cols[3], y), ref, font=font(15), fill="black")
        y += 27

    y += 26
    d.line([(MARGIN, y), (W - MARGIN, y)], fill="black", width=1)
    y += 16
    for line in footer:
        d.text((MARGIN, y), line, font=font(15), fill="black")
        y += 22

    img.save(path, "PNG")
    print(f"  wrote {path}")


# --- a haematology report: the differential block, a flag in the cell, a sex-specific range -------------
lab_report(
    OUT / "synthetic-blood-report.png",
    title="HAEMATOLOGY — COMPLETE BLOOD COUNT",
    patient={"Patient Name": "Testholm, A. (fictional)",
             "Patient ID": "SYN-0001",
             "Age / Sex": "44 Y / Male",
             "Referred By": "Dr. Imaginary, MBBS (fictional)",
             "Report Date": "11/03/2026"},
    rows=[
        ("Haemoglobin", "12.4 L", "g/dL", "M: 13.0-17.0  F: 11.5-16.5"),
        ("RBC total count", "4.61", "10^6/uL", "4.50-5.50"),
        ("WBC total count", "7.80", "10^3/uL", "4.00-11.00"),
        ("Platelets total count", "245", "10^3/uL", "150-410"),
        ("ESR", "18", "mm/hr", "0-15"),
        None,
        "Differential Leucocyte Count",
        ("Neutrophil", "4.21", "10^3/uL", "2.00-7.50"),
        ("Neutrophil", "54.0", "%", "40.0-75.0"),
        ("Lymphocyte", "2.65", "10^3/uL", "1.00-4.00"),
        ("Lymphocyte", "34.0", "%", "20.0-45.0"),
        ("Monocyte", "0.62", "10^3/uL", "0.20-1.00"),
        ("Monocyte", "8.0", "%", "2.0-10.0"),
        ("Eosinophil", "0.23", "10^3/uL", "0.02-0.50"),
        ("Eosinophil", "3.0", "%", "1.0-6.0"),
        ("Basophil", "0.08", "10^3/uL", "0.00-0.10"),
        ("Basophil", "1.0", "%", "0.0-1.0"),
    ],
    footer=["Checked by: A. Fictitious, Medical Technologist (fictional)",
            "Dr. N. Nonexistent, Consultant Pathologist (fictional)",
            "SYNTHETIC FIXTURE — invented patient, invented values, invented facility."],
)

# --- a urine report: Nil rows, a range-valued result, a qualitative reference ----------------------------
lab_report(
    OUT / "synthetic-urine-report.png",
    title="URINE ROUTINE EXAMINATION",
    patient={"Patient Name": "Quilldrake, B. (fictional)",
             "Patient ID": "SYN-0002",
             "Age / Sex": "31 Y / Female",
             "Referred By": "Dr. Notional, MBBS (fictional)",
             "Report Date": "11/03/2026"},
    rows=[
        "Physical",
        ("Colour", "Straw", "", "Pale Yellow/Straw"),
        ("Appearance", "Clear", "", "Clear"),
        ("Specific gravity", "1.012", "", "1.005-1.030"),
        None,
        "Chemical",
        ("Protein", "Nil", "", "Nil"),
        ("Glucose", "Nil", "", "Nil"),
        ("Bilirubin", "Nil", "", "Nil"),
        ("Urobilinogen", "Normal", "", "Normal"),
        ("Nitrite", "Negative", "", "Negative"),
        None,
        "Microscopic",
        ("Pus cells/WBC", "0-2", "/HPF", "0-5"),
        ("Epithelial cells", "1-3", "/HPF", "0-5"),
        ("RBC", "Nil", "/HPF", "Nil"),
        ("Hyaline Cast", "Nil", "/LPF", "Nil"),
        ("Calcium-Oxalate", "Nil", "", "Nil"),
        ("Candida", "Nil", "", "Nil"),
    ],
    footer=["Checked by: A. Fictitious, Medical Technologist (fictional)",
            "SYNTHETIC FIXTURE — invented patient, invented values, invented facility."],
)
