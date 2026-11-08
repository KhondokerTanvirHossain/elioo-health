"use client";

import dynamic from "next/dynamic";

/*
 * The desktop medicines' loader (owner decision 2026-11-07, option a; DR-42): its stylesheet comes through next/dynamic
 * from DeskMedicines.desk, so Next links it only on a page that renders the desktop medicines, never on a live page.
 * desktop-chunks.built.test.ts holds that.
 */
export const DeskMedicinesStyles = dynamic(() => import("./DeskMedicines.desk"));
