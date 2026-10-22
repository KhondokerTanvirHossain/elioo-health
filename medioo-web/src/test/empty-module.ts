// Stands in for `server-only` under Vitest (vitest.config.mts): the real package throws when it is imported outside a
// React Server Components build, which is exactly what a unit test of a server module is. Next's build keeps the real one.
export {};
