package com.elioo.baymax.storage.application.service;

import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;

import java.util.ArrayDeque;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * At most {@code limit} pieces of work running at once, across every caller that shares this gate; the rest wait in
 * arrival order. Non-blocking: a waiting caller holds no thread. Work cancelled while it waits never takes a permit,
 * and work cancelled while it runs gives its permit back at once (its bytes, if a blocking call is still returning
 * them, are dropped when that call returns).
 *
 * <p>The read-path backfill (D4b) holds one image per permit, so one gate per process bounds how many images every
 * paper read in the process holds at once, not just one read.</p>
 */
final class FetchGate {

    private final int limit;
    private final Object lock = new Object();
    private final ArrayDeque<Ticket<?>> waiting = new ArrayDeque<>();
    private int running;

    FetchGate(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("a gate lets at least one through, got " + limit);
        }
        this.limit = limit;
    }

    /** The work's result, once a permit is free; the work is subscribed only then. */
    <T> Mono<T> run(Supplier<Mono<T>> work) {
        return Mono.create(sink -> {
            Ticket<T> ticket = new Ticket<>(work, sink);
            sink.onCancel(ticket::cancel);
            boolean now;
            synchronized (lock) {
                now = running < limit;
                if (now) {
                    running++;
                } else {
                    waiting.add(ticket);
                }
            }
            if (now && !ticket.start()) {
                release();
            }
        });
    }

    /** A permit comes back: it passes straight to the next waiting ticket still wanted, or is returned to the pool. */
    private void release() {
        while (true) {
            Ticket<?> next;
            synchronized (lock) {
                next = waiting.poll();
                if (next == null) {
                    running--;
                    return;
                }
            }
            if (next.start()) {
                return;
            }
        }
    }

    private final class Ticket<T> {
        private final Supplier<Mono<T>> work;
        private final MonoSink<T> sink;
        private boolean cancelled;      // guarded by this
        private Disposable subscription; // guarded by this

        Ticket(Supplier<Mono<T>> work, MonoSink<T> sink) {
            this.work = work;
            this.sink = sink;
        }

        /** Subscribes the work, holding a permit until it ends; false (and no permit held) when the caller is gone. */
        boolean start() {
            synchronized (this) {
                if (cancelled) {
                    return false;
                }
            }
            Disposable d = Mono.defer(work)
                    .map(Optional::of)
                    .defaultIfEmpty(Optional.empty())
                    .doFinally(signal -> release())
                    .subscribe(v -> sink.success(v.orElse(null)), sink::error);
            boolean dispose;
            synchronized (this) {
                subscription = d;
                dispose = cancelled;
            }
            if (dispose) {
                d.dispose();
            }
            return true;
        }

        void cancel() {
            Disposable d;
            synchronized (this) {
                cancelled = true;
                d = subscription;
            }
            if (d != null) {
                d.dispose();
            } else {
                synchronized (lock) {
                    waiting.remove(this);
                }
            }
        }
    }
}
