package eu.wohlben.qits.deployments.deployments.control;

/**
 * The image-reference convention — the one place it is spelled: {@code
 * <registry-host>/<repository>/<application>:<version>}, e.g. {@code
 * qits-artifacts:8080/qits/qits-ci:2026.903.113443}.
 *
 * <p><b>The tag is the RELEASED VERSION, and that is the change releases brought.</b> It used to be
 * the commit sha, because a green build was the trigger and a build has no version. A release has
 * one, the pipeline pushes {@code qits/<application>:<version>}, and this is what pulls it.
 *
 * <p>Nothing in the release notification names a full reference — a {@code SoftwareRelease} carries
 * the unqualified package ({@code qits/qits-ci}) precisely because no host-qualified reference is
 * portable between a step container and this process. So the reference is <em>derived</em> from the
 * application's name and the version, which makes the tag convention a contract the publisher has
 * to meet rather than a value someone forgot to send. The registry host here is the one the
 * <b>docker daemon</b> resolves (it does the pulling), not one this process dials.
 */
public final class ImageRefs {

  private ImageRefs() {}

  public static String imageRef(
      String registryHost, String imageRepository, String applicationName, String version) {
    return registryHost + "/" + imageRepository + "/" + applicationName + ":" + version;
  }

  /**
   * Whether a reference the runtime reported back carries this tag — the convention read the other
   * way round, which is how the startup sweep settles a row it did not finish itself.
   *
   * <p><b>Ask it with {@code PdDeployment.imageTag()}</b>, never with the version or the commit sha
   * alone: a row written before releases were the trigger really is tagged with a sha, and a sweep
   * that compared the version there would find null and record every adopted row {@code
   * SUPERSEDED} by its own successor.
   *
   * <p>The tag is compared whole: a reference tagged with another version is another deployment,
   * never a near miss. The registry host may carry a port, so the last colon is a tag separator
   * only when it comes after the last slash, and a {@code @sha256:…} digest an orchestrator
   * resolved is dropped before the tag is read.
   */
  public static boolean carries(String imageRef, String tag) {
    if (tag == null || tag.isBlank()) {
      return false;
    }
    String carried = tagOf(imageRef);
    return carried != null && carried.equalsIgnoreCase(tag.strip());
  }

  /**
   * The tag a reference carries, or null when it carries none — {@link #carries}'s parse, exposed
   * for the one caller that has to ORDER the tag rather than match it: the startup sweep, which
   * may call a row superseded only by a version newer than its own (qits-2306).
   *
   * <p>The same rules: stripped, a {@code @sha256:…} digest dropped first, and the last colon a tag
   * separator only when it comes after the last slash, so a registry port is never read as a tag.
   */
  public static String tagOf(String imageRef) {
    if (imageRef == null) {
      return null;
    }
    String reference = imageRef.strip();
    int digest = reference.indexOf('@');
    if (digest >= 0) {
      reference = reference.substring(0, digest);
    }
    int colon = reference.lastIndexOf(':');
    if (colon < 0 || colon < reference.lastIndexOf('/')) {
      return null; // no tag at all: `latest`, and this component never deploys one
    }
    String tag = reference.substring(colon + 1);
    return tag.isEmpty() ? null : tag;
  }
}
