package eu.wohlben.qits.deployments.pacts;

import au.com.dius.pact.consumer.dsl.DslPart;
import au.com.dius.pact.consumer.dsl.Matchers;
import au.com.dius.pact.consumer.dsl.PactBuilder;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.V4Pact;
import au.com.dius.pact.core.support.Json;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * <b>What qits-deployments-service asks one provider</b> (ticket qits-1149): one row per (trigger,
 * call), each naming the provider state it needs, the request this repository's own client sends,
 * and the body paths that client reads.
 *
 * <p><b>A row is READY when the provider's golden masters record its (state, operation)</b>. Only
 * ready rows go into the pact file, because a response must come from the provider's recording and
 * never be invented here. A row that is not ready is still written down — the state it needs is the
 * provider's to record — and {@code ConsumerPactTest} reports it as skipped, naming that state.
 *
 * @param provider the provider as the pact names it: its repository name
 * @param indexProvider the provider as its golden-master index names it: its application name
 */
public record Contract(String provider, String indexProvider, List<Row> rows) {

  /** The consumer, as every pact names it: the repository name. */
  public static final String CONSUMER = "qits-deployments-service";

  /** The pact file, under {@code pacts/} at the repository root. */
  public String file() {
    return CONSUMER + "_" + provider + ".json";
  }

  /** The provider's golden masters, when a jar on the test classpath carries them. */
  public Optional<GoldenMasters> masters() {
    return GoldenMasters.of(indexProvider);
  }

  /** Whether the provider records this row's (state, operation). */
  public boolean ready(Row row) {
    return masters().map(m -> m.records(row.state(), row.operationId())).orElse(false);
  }

  /** Why a row that is not ready waits. */
  public String waitingReason(Row row) {
    return "needs provider state '" + row.state() + "' for " + row.operationId() + " in " + provider;
  }

  /** The rows the pact file holds. */
  public List<Row> readyRows() {
    return rows.stream().filter(this::ready).toList();
  }

  /** The pact of the ready rows, in table order (the file test sorts). */
  public V4Pact pact() {
    return pact(readyRows());
  }

  /** A pact holding only {@code subset}, every row of it ready. */
  public V4Pact pact(List<Row> subset) {
    PactBuilder builder = new PactBuilder(CONSUMER, provider, PactSpecVersion.V4);
    GoldenMasters masters = masters().orElse(null);
    for (Row row : subset) {
      interaction(builder, masters, row);
    }
    return builder.toPact();
  }

  private void interaction(PactBuilder builder, GoldenMasters masters, Row row) {
    if (masters == null || !masters.records(row.state(), row.operationId())) {
      throw new IllegalStateException(waitingReason(row));
    }
    GoldenMasters.Operation op = masters.operation(row.state(), row.operationId());
    Request request = row.request();
    if (!op.method().equalsIgnoreCase(request.method())) {
      throw new IllegalStateException(
          row.description() + ": the client sends " + request.method() + ", the recording is "
              + op.method());
    }
    DslPart body = masters.responseBody(row.state(), row.operationId(), row.consumes());
    Map<String, Object> references = new LinkedHashMap<>();
    Map<String, String> call = new LinkedHashMap<>();
    call.put("app", provider);
    call.put("operationId", row.operationId());
    references.put("qits-call", call);
    references.put("qits-trigger", row.trigger().reference());
    builder.expectsToReceiveHttpInteraction(
        row.description(),
        http -> {
          http.state(row.state(), new LinkedHashMap<String, Object>(op.params()));
          http.withRequest(
              r -> {
                r.method(request.method());
                if (op.hasPathParams()) {
                  r.path(Matchers.fromProviderState(op.expressionPath(), op.examplePath()));
                } else {
                  // A constant path takes no generator: pact-jvm would read it as a context key.
                  r.path(request.path());
                }
                request.query().forEach(r::queryParameter);
                if (request.body() != null) {
                  r.body(request.body(), request.contentType());
                }
                return r;
              });
          http.willRespondWith(
              r -> {
                r.status(op.status());
                if (body != null) {
                  r.header("Content-Type", Matchers.regexp("application/json.*", "application/json"));
                  r.body(body);
                }
                return r;
              });
          // pact-jvm 4.6 has no DSL for an arbitrary comment group; the V4 model's map is written
          // verbatim.
          http.getInteraction().getComments().put("references", Json.toJson(references));
          return http;
        });
  }

  /**
   * What made this service make the call — the {@code qits-trigger} reference: {@code operation}
   * (this service's own operationId), {@code event} (the bus event that enters the path) or {@code
   * schedule} (the scheduled job).
   */
  public record Trigger(String kind, String key, String value) {

    public Trigger {
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(value, "value");
    }

    public static Trigger operation(String operationId) {
      return new Trigger("operation", "operationId", operationId);
    }

    public static Trigger event(String eventType) {
      return new Trigger("event", "event", eventType);
    }

    public static Trigger schedule(String schedule) {
      return new Trigger("schedule", "schedule", schedule);
    }

    /** The {@code qits-trigger} group, every value a string, in a fixed key order. */
    public Map<String, String> reference() {
      Map<String, String> ref = new LinkedHashMap<>();
      ref.put("kind", kind);
      ref.put("app", CONSUMER);
      ref.put(key, value);
      return ref;
    }
  }

  /**
   * The request this repository's client sends: its own expectation, never the provider's recorded
   * request. {@code path} is the literal path the row's call sends; a recorded path template with
   * params replaces it in the pact.
   */
  public record Request(
      String method, String path, Map<String, String> query, String body, String contentType) {

    public static Request get(String path, Map<String, String> query) {
      return new Request("GET", path, query, null, null);
    }

    public static Request send(String method, String path, Map<String, String> query, String body, String contentType) {
      return new Request(method, path, query, body, contentType);
    }
  }

  /** What one row's call does against a base url, asserting what the client made of the answer. */
  @FunctionalInterface
  public interface Call {
    void run(String baseUrl, Map<String, String> params) throws Exception;
  }

  /**
   * One (trigger, call).
   *
   * @param consumes the body paths the client reads ({@code events[].id}); empty for status only
   */
  public record Row(
      Trigger trigger,
      String state,
      String operationId,
      Request request,
      List<String> consumes,
      Call call) {

    /** The trigger first, so (description, state) stays unique. */
    public String description() {
      return trigger.value() + ": " + operationId;
    }
  }
}
