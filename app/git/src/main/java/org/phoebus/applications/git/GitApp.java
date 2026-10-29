package org.phoebus.applications.git;

import java.io.File;
import java.net.URI;
import java.util.logging.Logger;

import org.phoebus.framework.preferences.AnnotatedPreferences;
import org.phoebus.framework.preferences.Preference;
import org.phoebus.framework.spi.AppInstance;
import org.phoebus.framework.spi.AppResourceDescriptor;

/** Git application: Status, commit, history, pull and push of a local repository */
@SuppressWarnings("nls")
public class GitApp implements AppResourceDescriptor
{
    /** Logger for all git app code */
    public static final Logger logger = Logger.getLogger(GitApp.class.getPackageName());

    public static final String Name = "git";

    public static final String DisplayName = Messages.DisplayName;

    /** Repository directory opened by default, empty for none */
    @Preference public static String default_repository;

    /** Number of commits shown in the history */
    @Preference public static int log_count;

    /** Directory with SSH keys, known_hosts and config */
    @Preference public static File ssh_directory;

    /** SSH private key file, empty to use the default keys */
    @Preference public static String ssh_private_key;

    /** Timeout in seconds for pull and push */
    @Preference public static int remote_timeout;

    /** Refresh automatically when files or the repository change? */
    @Preference public static boolean auto_refresh;

    /** Check staged display and script files before a commit? */
    @Preference public static boolean check_before_commit;

    /** Regular expression for PV names in display files, empty to not check */
    @Preference public static String pv_name_pattern;

    /** Offer logbook entry after push? */
    @Preference public static boolean logbook_after_push;

    /** Logbooks and tags for logbook entries, comma-separated, empty for default */
    @Preference public static String logbooks;
    @Preference public static String log_tags;

    /** Level of logbook entries, empty for default */
    @Preference public static String log_level;

    static
    {
        AnnotatedPreferences.initialize(GitApp.class, "/git_preferences.properties");
    }

    @Override
    public String getName()
    {
        return Name;
    }

    @Override
    public String getDisplayName()
    {
        return DisplayName;
    }

    @Override
    public AppInstance create()
    {
        final File dir = default_repository.isBlank() ? null : new File(default_repository);
        return new GitView(this, dir);
    }

    @Override
    public AppInstance create(final URI resource)
    {
        return new GitView(this, new File(resource));
    }
}
