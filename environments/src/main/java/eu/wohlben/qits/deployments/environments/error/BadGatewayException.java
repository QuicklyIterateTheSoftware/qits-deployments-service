package eu.wohlben.qits.deployments.environments.error;

/** 502: a peer this component called on the caller's behalf failed. */
public class BadGatewayException extends PdException {

  public BadGatewayException(String message, Throwable cause) {
    super(502, message, cause);
  }
}
