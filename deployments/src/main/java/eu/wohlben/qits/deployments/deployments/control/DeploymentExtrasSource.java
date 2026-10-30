package eu.wohlben.qits.deployments.deployments.control;

import org.eclipse.microprofile.config.Config;

/**
 * Where one argv build reads {@code qits.deployments.extras.<application>.*} from.
 *
 * <p><b>The one implementation is {@link SettingsExtrasSource}</b>: the settings store's rows for
 * the place, rendered in the extras grammar over the boot config (qits-1393). It used to be the
 * config volume's properties file, then qits-configuration over HTTP; both are gone. It stays an
 * interface because the driver's suite states a snapshot in a lambda, which is the cheapest double
 * there is for a seam that returns a value rather than holding a conversation.
 *
 * <p><b>One call is one snapshot, and that is the invariant {@link ServiceExtras} rests on.</b> Its
 * javadoc says every reading agrees, which is only true of a fixed {@link Config}: a caller takes
 * one snapshot per argv build and hands it to every reading of that deployment. Calling this twice
 * for one argv is the bug it is shaped to prevent.
 *
 * <p><b>An implementation that cannot answer REFUSES the deployment</b> — {@link
 * ServiceExtras.Refused}, naming what it could not read. It never falls back to a value it read
 * earlier or to the boot config: a stale extras value is the exact failure class this whole line of
 * work exists to kill, and it ships invisibly, as a green deployment carrying last boot's config.
 */
@FunctionalInterface
public interface DeploymentExtrasSource {

  /**
   * The config this deployment's extras are read out of, as it reads now.
   *
   * <p><b>One call is one snapshot for one PLACE</b>: an application in a tier. A configuration
   * that cannot differ between {@code dev} and {@code prod} is not configuration, so the tier is
   * part of the address; the released version is not — the settings store holds one value per
   * (application, environment, key), and a rollback deploys an older image with today's settings.
   *
   * @param application the deployed application's name — the segment in the key family
   * @param environmentName the tier this deployment is going into
   * @throws ServiceExtras.Refused the extras could not be read
   */
  Config forDeployment(String application, String environmentName);
}
