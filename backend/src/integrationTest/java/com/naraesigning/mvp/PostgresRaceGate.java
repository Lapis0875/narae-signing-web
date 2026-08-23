package com.naraesigning.mvp;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;
import org.springframework.jdbc.core.JdbcTemplate;

final class PostgresRaceGate implements AutoCloseable {
    private static final long LOCK_KEY = 30_030L;
    private final JdbcTemplate observer;
    private final Connection owner;

    private PostgresRaceGate(JdbcTemplate observer, Connection owner) {
        this.observer = observer;
        this.owner = owner;
    }

    static PostgresRaceGate install(JdbcTemplate jdbc) throws SQLException {
        var dataSource = jdbc.getDataSource();
        if (dataSource == null) throw new IllegalStateException("integration datasource is missing");
        var owner = dataSource.getConnection();
        try {
            owner.createStatement().execute("select pg_advisory_lock(" + LOCK_KEY + ")");
            jdbc.execute("""
                    create or replace function task30_hold_slot_update() returns trigger language plpgsql as $$
                    begin
                        perform pg_advisory_lock(30030);
                        perform pg_advisory_unlock(30030);
                        return new;
                    end $$
                    """);
            jdbc.execute("""
                    create trigger task30_hold_slot_update before update on signature_slot
                    for each row execute function task30_hold_slot_update()
                    """);
            return new PostgresRaceGate(jdbc, owner);
        } catch (RuntimeException | SQLException exception) {
            owner.close();
            throw exception;
        }
    }

    void awaitTwoDatabaseWaiters() {
        var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            Map<String, Integer> waiters = observer.query("""
                    select wait_event, count(*)::integer
                    from pg_stat_activity
                    where datname = current_database()
                      and wait_event_type = 'Lock'
                      and wait_event in ('advisory', 'transactionid', 'tuple')
                    group by wait_event
                    """, result -> {
                        var counts = new java.util.HashMap<String, Integer>();
                        while (result.next()) counts.put(result.getString(1), result.getInt(2));
                        return counts;
                    });
            if (waiters.getOrDefault("advisory", 0) >= 1
                    && (waiters.getOrDefault("transactionid", 0) >= 1 || waiters.getOrDefault("tuple", 0) >= 1)) return;
            LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
        }
        throw new AssertionError("two requests did not reach PostgreSQL lock contention");
    }

    void release() throws SQLException {
        owner.createStatement().execute("select pg_advisory_unlock(" + LOCK_KEY + ")");
    }

    @Override
    public void close() throws Exception {
        try {
            owner.createStatement().execute("select pg_advisory_unlock_all()");
            observer.execute("drop trigger if exists task30_hold_slot_update on signature_slot");
            observer.execute("drop function if exists task30_hold_slot_update()");
        } finally {
            owner.close();
        }
    }
}
