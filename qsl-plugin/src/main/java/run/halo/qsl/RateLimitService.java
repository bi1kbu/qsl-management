package run.halo.qsl;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Service;

@Service
public class RateLimitService {

    private static final class WindowCounter {
        long minuteKey;
        final AtomicInteger count = new AtomicInteger(0);
    }

    private final Map<String, WindowCounter> counters = new ConcurrentHashMap<>();

    public boolean allow(String key, int limitPerMinute) {
        var nowMinute = Instant.now().getEpochSecond() / 60;
        var counter = counters.computeIfAbsent(key, k -> {
            var c = new WindowCounter();
            c.minuteKey = nowMinute;
            return c;
        });
        synchronized (counter) {
            if (counter.minuteKey != nowMinute) {
                counter.minuteKey = nowMinute;
                counter.count.set(0);
            }
            return counter.count.incrementAndGet() <= limitPerMinute;
        }
    }
}
