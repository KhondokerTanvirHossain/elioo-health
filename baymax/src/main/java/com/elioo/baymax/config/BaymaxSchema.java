package com.elioo.baymax.config;

/**
 * The Postgres schema Baymax owns. It is part of the code contract (entity mappings and SQL name it),
 * not configuration. The shared R2DBC connection keeps {@code medscribe} as its search path, so every
 * Baymax table is referenced schema-qualified.
 */
public final class BaymaxSchema {

    public static final String NAME = "baymax";

    private BaymaxSchema() {
    }
}
