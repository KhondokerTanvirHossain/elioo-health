package com.elioo.healthcare.core;

/**
 * DR-11 (BMX-6b): the whole MedScribe PoC — UI, API and static assets — lives under this prefix; Baymax serves
 * {@code /}. Every MedScribe route is built from it, and the static UI's fetch calls carry it verbatim.
 * {@code /actuator/**} stays at the root: deploy.sh, the Dockerfile and CI all probe it there.
 */
public final class MedScribePaths {

    public static final String PREFIX = "/medscribeai";

    private MedScribePaths() {
    }
}
