package com.bizbitenow.printservice.pairing.service;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class PairingRateLimiter {
    private static final int LIMIT = 5;
    private static final long WINDOW_SECONDS = 60;
    private final ConcurrentHashMap<String, ArrayDeque<Long>> attempts = new ConcurrentHashMap<>();

    public boolean allow(String key) {
        long now = Instant.now().getEpochSecond();
        if (attempts.size() > 10_000) {
            attempts.entrySet().removeIf(entry -> entry.getValue().isEmpty()
                    || entry.getValue().peekFirst() < now - WINDOW_SECONDS);
        }
        ArrayDeque<Long> times = attempts.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && times.peekFirst() <= now - WINDOW_SECONDS) times.removeFirst();
            if (times.size() >= LIMIT) return false;
            times.addLast(now);
            return true;
        }
    }
}
