package org.phoebus.applications.git;

import java.util.ArrayList;
import java.util.List;

import org.phoebus.applications.git.BobDiff.Change;
import org.phoebus.applications.git.GitRepository.CommitInfo;
import org.phoebus.applications.git.GitRepository.FileVersion;
import org.phoebus.applications.git.GitRepository.Versions;

/** History of one widget in a display file: Commits that changed it, and how */
class WidgetHistory
{
    /** Default number of file versions to check */
    static final int MAX_VERSIONS = 200;

    /** Commit that changed the widget
     *  @param commit Commit, <code>null</code> for uncommitted changes
     *  @param path Path of the display file in that commit
     *  @param changes Changes of the widget
     */
    record Entry(CommitInfo commit, String path, List<Change> changes) {}

    /** @param git Repository
     *  @param path Path of display file
     *  @param key Widget key, see {@link WidgetKeys}
     *  @param current Current content of the display, for example of an editor with unsaved changes,
     *                 compared to HEAD, or <code>null</code> to ignore uncommitted changes
     *  @param max Maximum number of file versions to check
     *  @return Changes of the widget, newest first
     */
    static List<Entry> compute(final GitRepository git, final String path, final String key,
                               final byte[] current, final int max) throws Exception
    {
        final List<Entry> result = new ArrayList<>();
        if (current != null)
        {
            final byte[] head = WidgetKeys.normalize(git.getContent(git.resolve("HEAD"), path));
            final List<Change> changes = filter(BobDiff.compare(head, current), key);
            if (! changes.isEmpty())
                result.add(new Entry(null, path, changes));
        }
        for (FileVersion version : git.getFileHistory(path, max))
        {
            final Versions versions = git.getCommitFileVersions(version.commit().id(), version.path());
            final List<Change> changes = filter(BobDiff.compare(versions.old_content(), versions.new_content()), key);
            if (! changes.isEmpty())
                result.add(new Entry(version.commit(), version.path(), changes));
        }
        return result;
    }

    private static List<Change> filter(final List<Change> changes, final String key)
    {
        return changes.stream().filter(change -> key.equals(change.key())).toList();
    }
}
