package com.naraesigning.realtime;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
// ponytail: single-JVM lease registry; use a shared atomic store before adding backend replicas.
public final class PublicDisplayLeaseRegistry {
    static final Duration EXPIRY = Duration.ofSeconds(30);
    private final Clock clock;
    private final Map<UUID, Lease> leases = new HashMap<>();

    public PublicDisplayLeaseRegistry() { this(Clock.systemUTC()); }

    PublicDisplayLeaseRegistry(Clock clock) { this.clock = clock; }

    synchronized Claim claim(UUID boardId, String presentedOwner) {
        var now = clock.instant();
        var current = leases.get(boardId);
        var owner = parse(presentedOwner);
        if (current != null && current.activeAt(now) && !current.owner().equals(owner)) return Claim.denied();
        if (owner == null) owner = UUID.randomUUID();
        leases.put(boardId, new Lease(owner, now));
        return Claim.acquired(owner.toString());
    }

    synchronized boolean heartbeat(UUID boardId, String presentedOwner) {
        var owner = parse(presentedOwner);
        var current = leases.get(boardId);
        if (current == null) return false;
        if (!current.activeAt(clock.instant())) {
            leases.remove(boardId);
            return false;
        }
        if (owner == null || !current.owner().equals(owner)) return false;
        leases.put(boardId, new Lease(owner, clock.instant()));
        return true;
    }

    synchronized boolean owns(UUID boardId, String presentedOwner) {
        var owner = parse(presentedOwner);
        var current = leases.get(boardId);
        if (current == null || !current.activeAt(clock.instant())) {
            leases.remove(boardId, current);
            return false;
        }
        return current.owner().equals(owner);
    }

    synchronized boolean registerIfOwned(UUID boardId, String presentedOwner, Runnable registration) {
        if (!owns(boardId, presentedOwner)) return false;
        registration.run();
        return true;
    }

    synchronized void release(UUID boardId, String presentedOwner) {
        var owner = parse(presentedOwner);
        var current = leases.get(boardId);
        if (current != null && current.owner().equals(owner)) leases.remove(boardId);
    }

    public synchronized void forceReplace(UUID boardId, Runnable notifyOwner) {
        notifyOwner.run();
        leases.remove(boardId);
    }

    private static UUID parse(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    record Claim(boolean acquired, String owner) {
        static Claim acquired(String owner) { return new Claim(true, owner); }
        static Claim denied() { return new Claim(false, null); }
    }

    private record Lease(UUID owner, Instant heartbeatAt) {
        boolean activeAt(Instant now) { return now.isBefore(heartbeatAt.plus(EXPIRY)); }
    }
}
