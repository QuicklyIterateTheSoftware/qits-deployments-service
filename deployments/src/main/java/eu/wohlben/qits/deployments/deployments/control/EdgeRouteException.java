package eu.wohlben.qits.deployments.deployments.control;

/** An edge's route-table door could not be reached, or answered what it does not promise. */
public class EdgeRouteException extends RuntimeException {

  public EdgeRouteException(String message) {
    super(message);
  }

  public EdgeRouteException(String message, Throwable cause) {
    super(message, cause);
  }
}
