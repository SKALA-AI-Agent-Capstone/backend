package org.skala.capstone.review.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Relays review progress to browsers over SSE (FR-8, NFR-1). Subscribers and the short replay
 * buffer live in this process, which fits the single API instance. Running several API instances
 * needs a shared channel such as Redis pub/sub. The stored review stays the source of truth: every
 * subscription starts with a snapshot of it.
 */
@Component
public class ReviewEvents {
    public static final Set<String> AGENT_TYPES =
            Set.of("step_done", "tool_started", "tool_finished");
    static final Set<String> TERMINAL = Set.of("completed", "failed");
    static final long TIMEOUT_MS = 10 * 60 * 1000L;
    static final int REPLAY = 50;

    public record Event(long id, String type, ObjectNode data) {}

    private final ObjectMapper json;
    private final AtomicLong sequence = new AtomicLong();
    private final Map<String, List<SseEmitter>> subscribers = new ConcurrentHashMap<>();
    private final Map<String, Deque<Event>> recent =
            Collections.synchronizedMap(
                    new LinkedHashMap<>(64, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(
                                Map.Entry<String, Deque<Event>> eldest) {
                            return size() > 200;
                        }
                    });

    public ReviewEvents(ObjectMapper json) {
        this.json = json;
    }

    public void publishAfterCommit(String reviewId, int versionNo, String type, ObjectNode extra) {
        ObjectNode data = extra == null ? null : extra.deepCopy();
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            publish(reviewId, versionNo, type, data);
                        }
                    });
        } else publish(reviewId, versionNo, type, data);
    }

    public void publishAfterCommit(String reviewId, int versionNo, String type) {
        publishAfterCommit(reviewId, versionNo, type, null);
    }

    /**
     * Opens a stream. The caller has already checked that the review belongs to the user's tenant.
     */
    public SseEmitter subscribe(
            String reviewId, ObjectNode snapshot, boolean running, String lastEventId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        try {
            emitter.send(SseEmitter.event().name("snapshot").data(snapshot).reconnectTime(3000));
            if (!running) {
                emitter.complete();
                return emitter;
            }
            long after = parse(lastEventId);
            Deque<Event> buffer = recent.computeIfAbsent(reviewId, k -> new ArrayDeque<>());
            synchronized (buffer) {
                // 재생과 구독 등록 사이에 완료 이벤트를 놓치지 않도록 같은 잠금을 쓴다.
                for (Event event : buffer) {
                    if (event.id() > after) emitter.send(frame(event));
                    if (TERMINAL.contains(event.type())) {
                        emitter.complete();
                        return emitter;
                    }
                }
                List<SseEmitter> list =
                        subscribers.compute(
                                reviewId,
                                (id, current) -> {
                                    if (current == null) current = new CopyOnWriteArrayList<>();
                                    current.add(emitter);
                                    return current;
                                });
                Runnable remove = () -> list.remove(emitter);
                emitter.onCompletion(remove);
                emitter.onTimeout(remove);
                emitter.onError(e -> remove.run());
            }
        } catch (IOException closed) {
            emitter.completeWithError(closed);
        }
        return emitter;
    }

    /** Sends one event to every open stream of the review and keeps it for reconnecting clients. */
    public void publish(String reviewId, int versionNo, String type, ObjectNode extra) {
        ObjectNode data =
                json.createObjectNode()
                        .put("type", type)
                        .put("reviewId", reviewId)
                        .put("versionNo", versionNo)
                        .put("at", Instant.now().toString());
        if (extra != null)
            extra.fieldNames()
                    .forEachRemaining(
                            k -> {
                                if (!data.has(k)) data.set(k, extra.get(k));
                            });
        Event event;
        List<SseEmitter> targets;
        Deque<Event> buffer = recent.computeIfAbsent(reviewId, k -> new ArrayDeque<>());
        synchronized (buffer) {
            event = new Event(sequence.incrementAndGet(), type, data);
            if (type.equals("started")) buffer.clear();
            buffer.addLast(event);
            while (buffer.size() > REPLAY) buffer.removeFirst();
            targets = List.copyOf(subscribers.getOrDefault(reviewId, List.of()));
            if (TERMINAL.contains(type)) subscribers.remove(reviewId);
        }
        for (SseEmitter emitter : targets) {
            try {
                emitter.send(frame(event));
                if (TERMINAL.contains(type)) emitter.complete();
            } catch (Exception closed) {
                emitter.completeWithError(closed);
            }
        }
    }

    public void publish(String reviewId, int versionNo, String type) {
        publish(reviewId, versionNo, type, null);
    }

    /**
     * Proxies close idle connections. A comment every 15 seconds keeps the stream open and finds
     * dead clients.
     */
    @Scheduled(fixedDelay = 15000, initialDelay = 15000)
    public void heartbeat() {
        subscribers.forEach(
                (id, list) -> {
                    for (SseEmitter emitter : list) {
                        try {
                            emitter.send(SseEmitter.event().comment("ping"));
                        } catch (Exception closed) {
                            list.remove(emitter);
                            emitter.completeWithError(closed);
                        }
                    }
                });
        subscribers.forEach(
                (id, list) ->
                        subscribers.computeIfPresent(
                                id, (key, current) -> current.isEmpty() ? null : current));
    }

    public int subscriberCount(String reviewId) {
        return subscribers.getOrDefault(reviewId, List.of()).size();
    }

    public List<Event> replay(String reviewId) {
        Deque<Event> buffer = recent.get(reviewId);
        if (buffer == null) return List.of();
        synchronized (buffer) {
            return new ArrayList<>(buffer);
        }
    }

    private SseEmitter.SseEventBuilder frame(Event event) {
        return SseEmitter.event()
                .id(Long.toString(event.id()))
                .name(event.type())
                .data(event.data());
    }

    private static long parse(String id) {
        try {
            return id == null ? 0 : Long.parseLong(id.trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
