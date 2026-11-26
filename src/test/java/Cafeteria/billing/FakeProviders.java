package Cafeteria.billing;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

public final class FakeProviders {

    public static final FakeProviders INSTANCE = new FakeProviders();

    public final List<String> paypalCreateBodies = new CopyOnWriteArrayList<>();
    public final List<String> preferenceBodies = new CopyOnWriteArrayList<>();
    public final Map<String, String> paypalCaptureByOrder = new ConcurrentHashMap<>();
    public final Map<String, String> mpPayments = new ConcurrentHashMap<>();
    public final Map<String, List<String>> mpSearch = new ConcurrentHashMap<>();
    public final AtomicInteger paypalOrders = new AtomicInteger();
    public final AtomicInteger preferences = new AtomicInteger();

    private final HttpServer server;

    private FakeProviders() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/v1/oauth2/token", ex -> reply(ex, 200, "{\"access_token\":\"fake-token\",\"expires_in\":3600}"));
        server.createContext("/v2/checkout/orders", this::paypal);
        server.createContext("/checkout/preferences", this::preference);
        server.createContext("/v1/payments", this::payment);
        server.createContext("/v1/dolares", ex -> reply(ex, 200, "[{\"casa\":\"oficial\",\"compra\":1580,\"venta\":1630},"
                + "{\"casa\":\"blue\",\"compra\":1690,\"venta\":1710},{\"casa\":\"bolsa\",\"compra\":1664,\"venta\":1688.7},"
                + "{\"casa\":\"tarjeta\",\"compra\":2054,\"venta\":2119}]"));
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void reset() {
        paypalCreateBodies.clear();
        preferenceBodies.clear();
        paypalCaptureByOrder.clear();
        mpPayments.clear();
        mpSearch.clear();
    }

    public static String capture(String orderId, String reference, String value, String payerEmail) {
        return "{\"id\":\"" + orderId + "\",\"status\":\"COMPLETED\",\"payer\":{\"email_address\":\"" + payerEmail + "\"},"
                + "\"purchase_units\":[{\"custom_id\":\"" + reference + "\",\"payments\":{\"captures\":[{\"id\":\"CAP-" + orderId
                + "\",\"status\":\"COMPLETED\",\"custom_id\":\"" + reference + "\",\"amount\":{\"currency_code\":\"USD\",\"value\":\"" + value + "\"}}]}}]}";
    }

    public static String mpPayment(String id, String status, String reference, String amount, String currency, String email) {
        return "{\"id\":" + id + ",\"status\":\"" + status + "\",\"status_detail\":\"" + ("approved".equals(status) ? "accredited" : "cc_rejected_other_reason")
                + "\",\"transaction_amount\":" + amount + ",\"currency_id\":\"" + currency + "\",\"external_reference\":\"" + reference
                + "\",\"payer\":{\"email\":\"" + email + "\"}}";
    }

    private void paypal(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if ("POST".equals(ex.getRequestMethod()) && path.equals("/v2/checkout/orders")) {
            paypalCreateBodies.add(body);
            reply(ex, 201, "{\"id\":\"PP-" + paypalOrders.incrementAndGet() + "\",\"status\":\"CREATED\"}");
        } else if (path.endsWith("/capture")) {
            String orderId = path.substring("/v2/checkout/orders/".length(), path.length() - "/capture".length());
            String capture = paypalCaptureByOrder.get(orderId);
            if (capture == null) {
                reply(ex, 422, "{\"name\":\"UNPROCESSABLE_ENTITY\",\"details\":[{\"issue\":\"ORDER_NOT_APPROVED\"}]}");
            } else {
                reply(ex, 201, capture);
            }
        } else {
            String orderId = path.substring("/v2/checkout/orders/".length());
            reply(ex, 200, paypalCaptureByOrder.getOrDefault(orderId, "{\"id\":\"" + orderId + "\",\"status\":\"CREATED\"}"));
        }
    }

    private void preference(HttpExchange ex) throws IOException {
        preferenceBodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        int n = preferences.incrementAndGet();
        reply(ex, 201, "{\"id\":\"PREF-" + n + "\",\"init_point\":\"https://mp.test/checkout?pref=PREF-" + n + "\"}");
    }

    private void payment(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/v1/payments/search")) {
            String query = ex.getRequestURI().getRawQuery();
            String reference = query.substring(query.indexOf("external_reference=") + "external_reference=".length());
            List<String> results = mpSearch.getOrDefault(reference, new ArrayList<>());
            reply(ex, 200, "{\"results\":[" + String.join(",", results) + "]}");
            return;
        }
        String id = path.substring("/v1/payments/".length());
        String payment = mpPayments.get(id);
        reply(ex, payment == null ? 404 : 200, payment == null ? "{\"message\":\"not found\"}" : payment);
    }

    private static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }
}
