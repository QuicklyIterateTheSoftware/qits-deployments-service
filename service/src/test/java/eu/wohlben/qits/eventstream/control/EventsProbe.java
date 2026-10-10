package eu.wohlben.qits.eventstream.control;

import java.util.Collection;
import java.util.List;

/**
 * <b>The bus library's two clients, reachable from this repository's consumer pacts</b> (ticket
 * qits-1149). {@link EventsQuery} and {@link EventsPublisher} keep their address and their paging
 * methods package-private, and this test class shares their package to call them as the library's
 * own sweepers do — so the pact proves the calls that ship in this service, at the library version
 * this repository pins.
 */
public final class EventsProbe {

  private EventsProbe() {}

  /** One page as the catch-up sweep sees it. */
  public record Page(List<EventFrame> events, String nextCursor) {}

  /** The page size {@link CatchupSweeper} asks for. */
  public static int pageSize() {
    return CatchupSweeper.PAGE_SIZE;
  }

  /** {@link EventsQuery#after}, against {@code eventsUrl}. */
  public static Page after(String eventsUrl, Collection<String> names, String cursor, int limit) {
    EventPage page = query(eventsUrl).after(names, cursor, limit);
    return new Page(page.events(), page.nextCursor());
  }

  /** {@link EventsQuery#newest}, against {@code eventsUrl}. */
  public static EventFrame newest(String eventsUrl, Collection<String> names) {
    return query(eventsUrl).newest(names);
  }

  /** {@link EventsPublisher#put}, against {@code eventsUrl}. */
  public static EventsPublisher.Delivery put(String eventsUrl, String eventId, EventEnvelope envelope) {
    EventsPublisher publisher = new EventsPublisher();
    publisher.eventsUrl = eventsUrl;
    publisher.publishTimeout = java.time.Duration.ofSeconds(5);
    return publisher.put(eventId, envelope);
  }

  /** The body {@link EventsPublisher#put} sends for {@code envelope}. */
  public static String body(EventEnvelope envelope) {
    return CanonicalJson.envelope(envelope);
  }

  private static EventsQuery query(String eventsUrl) {
    EventsQuery query = new EventsQuery();
    query.eventsUrl = eventsUrl;
    return query;
  }
}
