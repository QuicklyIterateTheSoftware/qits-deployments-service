package eu.wohlben.qits.deployments.deployments.control;

/**
 * A purge that did not happen: qits-artifacts unreachable, or answering anything but 2xx. The
 * message names the address and the status, because it is what the decommission door's 502 and the
 * {@code pd_decommission} row's {@code failure_detail} say.
 */
public class PurgeException extends RuntimeException {

  public PurgeException(String message) {
    super(message);
  }

  public PurgeException(String message, Throwable cause) {
    super(message, cause);
  }
}
