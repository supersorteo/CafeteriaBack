package Cafeteria.security;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import Cafeteria.shared.ProblemResponses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class RateLimitFilter extends OncePerRequestFilter {

    private static final int PRUNE_THRESHOLD = 10_000;

    private final String path;
    private final int maxAttempts;
    private final int globalMaxAttempts;
    private final long windowMillis;
    private final Function<HttpServletRequest, String> clientKey;
    private final ConcurrentHashMap<String, Deque<Long>> attempts = new ConcurrentHashMap<>();
    private final Deque<Long> globalAttempts = new ArrayDeque<>();

    public RateLimitFilter(String path, int maxAttempts, Duration window) {
        this(path, maxAttempts, window, HttpServletRequest::getRemoteAddr, Integer.MAX_VALUE);
    }

    public RateLimitFilter(String path, int maxAttempts, Duration window,
            Function<HttpServletRequest, String> clientKey, int globalMaxAttempts) {
        this.path = path;
        this.maxAttempts = maxAttempts;
        this.globalMaxAttempts = globalMaxAttempts;
        this.windowMillis = window.toMillis();
        this.clientKey = clientKey;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !path.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long now = System.currentTimeMillis();
        if (attempts.size() > PRUNE_THRESHOLD) {
            prune(now);
        }
        Deque<Long> window = attempts.computeIfAbsent(clientKey.apply(request), k -> new ArrayDeque<>());
        boolean blocked;
        synchronized (globalAttempts) {
            evict(globalAttempts, now);
            synchronized (window) {
                evict(window, now);
                blocked = window.size() >= maxAttempts || globalAttempts.size() >= globalMaxAttempts;
                if (!blocked) {
                    window.addLast(now);
                    globalAttempts.addLast(now);
                }
            }
        }
        if (blocked) {
            response.setHeader("Retry-After", String.valueOf(windowMillis / 1000));
            ProblemResponses.write(response, HttpStatus.TOO_MANY_REQUESTS, "too_many_attempts",
                    "Demasiados intentos, inténtalo más tarde");
            return;
        }
        chain.doFilter(request, response);
    }

    private void evict(Deque<Long> window, long now) {
        while (!window.isEmpty() && now - window.peekFirst() > windowMillis) {
            window.pollFirst();
        }
    }

    private void prune(long now) {
        attempts.entrySet().removeIf(entry -> {
            synchronized (entry.getValue()) {
                evict(entry.getValue(), now);
                return entry.getValue().isEmpty();
            }
        });
    }
}
