package eu.wohlben.qits.deployments.deployments.control;

/** qits-egress's grant-table door could not be reached, or answered what it does not promise. */
public class EgressGrantException extends RuntimeException {

  public EgressGrantException(String message) {
    super(message);
  }

  public EgressGrantException(String message, Throwable cause) {
    super(message, cause);
  }
}
