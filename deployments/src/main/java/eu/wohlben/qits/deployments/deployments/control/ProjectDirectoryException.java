package eu.wohlben.qits.deployments.deployments.control;

/** qits-projects could not be reached, or answered what its door does not promise (qits-2659). */
public class ProjectDirectoryException extends RuntimeException {

  public ProjectDirectoryException(String message) {
    super(message);
  }

  public ProjectDirectoryException(String message, Throwable cause) {
    super(message, cause);
  }
}
