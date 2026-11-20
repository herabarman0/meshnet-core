package com.herabarman.meshnet.sim;

import com.herabarman.meshnet.core.Clock;
import java.util.PriorityQueue;

/** Virtual clock: time only moves when events run. */
public final class SimClock implements Clock {
    private static final class Event implements Comparable<Event> {
        final long time;
        final long seq;
        final Runnable task;

        Event(long time, long seq, Runnable task) {
            this.time = time;
            this.seq = seq;
            this.task = task;
        }

        @Override
        public int compareTo(Event o) {
            int c = Long.compare(time, o.time);
            return c != 0 ? c : Long.compare(seq, o.seq);
        }
    }

    private final PriorityQueue<Event> queue = new PriorityQueue<>();
    private long now;
    private long seq;

    @Override
    public long nowMs() {
        return now;
    }

    @Override
    public void schedule(long delayMs, Runnable task) {
        queue.add(new Event(now + Math.max(0, delayMs), seq++, task));
    }

    /** Run every event up to and including time t, then set the clock to t. */
    public void runUntil(long t) {
        while (!queue.isEmpty() && queue.peek().time <= t) {
            Event e = queue.poll();
            now = e.time;
            e.task.run();
        }
        if (now < t) now = t;
    }

    public void runFor(long ms) {
        runUntil(now + ms);
    }

    public int pending() {
        return queue.size();
    }
}
