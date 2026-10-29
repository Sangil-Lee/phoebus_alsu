package org.phoebus.applications.git;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.phoebus.applications.git.GitRepository.CommitDetails;
import org.phoebus.applications.git.GitRepository.CommitFile;
import org.phoebus.logbook.AttachmentImpl;
import org.phoebus.logbook.LogEntry;
import org.phoebus.logbook.LogEntryImpl.LogEntryBuilder;
import org.phoebus.logbook.LogbookImpl;
import org.phoebus.logbook.TagImpl;

/** Logbook entry that describes commits
 *
 *  <p>Title, Markdown description with the commits,
 *  changes of display files by widget, other changed files,
 *  and the complete diff as attachment.
 */
@SuppressWarnings("nls")
class GitLogbook
{
    private static final DateTimeFormatter DATE_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    /** Maximum number of widget changes listed per display file */
    private static final int MAX_WIDGET_LINES = 20;

    /** @param git Repository
     *  @param commits Commit ids, newest first
     *  @return Title of the entry
     */
    static String createTitle(final GitRepository git, final List<String> commits) throws Exception
    {
        final String name = git.getWorkTree().getName();
        if (commits.size() == 1)
        {
            final CommitDetails commit = git.getCommitDetails(commits.get(0));
            return "Git: " + name + " " + commit.id().substring(0, 7) + " " + commit.message().lines().findFirst().orElse("");
        }
        return "Git: " + name + " " + git.getBranch() + " " + String.format(Messages.Commits, commits.size());
    }

    /** @param git Repository
     *  @param commits Commit ids, newest first
     *  @param remote Remote that the commits were pushed to, or <code>null</code>
     *  @return Markdown description
     */
    static String createDescription(final GitRepository git, final List<String> commits, final String remote) throws Exception
    {
        final StringBuilder text = new StringBuilder();
        text.append("**").append(Messages.Repository).append("** `").append(git.getWorkTree().getName()).append("`");
        if (remote != null)
        {
            final String url = git.getRemoteUrls().get(remote);
            text.append(", ").append(Messages.LogPushedTo).append(" `").append(remote).append("`");
            if (url != null)
                text.append(" (").append(url).append(")");
        }
        text.append(", ").append(Messages.Branch).append(" `").append(git.getBranch()).append("`\n");

        for (String id : commits)
        {
            final CommitDetails commit = git.getCommitDetails(id);
            final List<String> message = commit.message().lines().toList();
            text.append("\n### ").append(commit.id(), 0, 7).append(" ").append(message.isEmpty() ? "" : message.get(0)).append("\n\n");
            text.append(commit.author()).append(", ").append(DATE_FORMAT.format(commit.date())).append("\n");
            if (message.size() > 1)
                text.append("\n").append(String.join("\n", message.subList(1, message.size())).trim()).append("\n");

            final List<CommitFile> files = git.getCommitFiles(id);
            boolean other_header = false;
            for (CommitFile file : files)
                if (BobDiff.supports(file.path()))
                {
                    text.append("\n**").append(file.path()).append("** (").append(file.type()).append(")\n\n");
                    final GitRepository.Versions versions = git.getCommitFileVersions(id, file.path());
                    final List<String> lines = BobDiff.format(versions.old_content(), versions.new_content());
                    for (String line : lines.subList(0, Math.min(lines.size(), MAX_WIDGET_LINES)))
                        text.append("- ").append(escape(line)).append("\n");
                    if (lines.size() > MAX_WIDGET_LINES)
                        text.append("- … ").append(lines.size() - MAX_WIDGET_LINES).append(" ").append(Messages.LogMoreChanges).append("\n");
                }
            for (CommitFile file : files)
                if (! BobDiff.supports(file.path()))
                {
                    if (! other_header)
                    {
                        text.append("\n").append(Messages.LogOtherFiles).append(":\n\n");
                        other_header = true;
                    }
                    text.append("- ").append(file.type()).append(" ")
                        .append(file.old_path() == null ? "" : file.old_path() + " → ")
                        .append(file.path()).append("\n");
                }
        }
        return text.toString();
    }

    /** @param text Text for a Markdown list item
     *  @return Text with characters that Markdown would interpret escaped
     */
    private static String escape(final String text)
    {
        return text.replace("\\", "\\\\").replace("*", "\\*").replace("_", "\\_").replace("`", "\\`")
                   .replace("<", "&lt;").replace(">", "&gt;");
    }

    /** @param git Repository
     *  @param commits Commit ids, newest first
     *  @return Temporary file with the diff of all commits, oldest first
     */
    static File createDiffFile(final GitRepository git, final List<String> commits) throws Exception
    {
        final StringBuilder diff = new StringBuilder();
        for (int i=commits.size()-1; i>=0; --i)
            diff.append(git.getCommitDiff(commits.get(i))).append("\n");
        final File file = File.createTempFile("git-" + commits.get(0).substring(0, 7) + "-", ".diff");
        // Logbook editor deletes attachments after submitting, not when the entry is cancelled
        file.deleteOnExit();
        Files.writeString(file.toPath(), diff.toString(), StandardCharsets.UTF_8);
        return file;
    }

    /** @param git Repository
     *  @param commits Commit ids, newest first
     *  @param remote Remote that the commits were pushed to, or <code>null</code>
     *  @return Logbook entry for the logbook editor
     */
    static LogEntry createEntry(final GitRepository git, final List<String> commits, final String remote) throws Exception
    {
        final LogEntryBuilder builder = LogEntryBuilder.log()
            .title(createTitle(git, commits))
            .description(createDescription(git, commits, remote))
            .attach(AttachmentImpl.of(createDiffFile(git, commits), "text/plain", false));
        for (String logbook : GitApp.logbooks.split(","))
            if (! logbook.isBlank())
                builder.appendToLogbook(LogbookImpl.of(logbook.trim()));
        if (! GitApp.log_level.isBlank())
            builder.level(GitApp.log_level.trim());
        for (String tag : GitApp.log_tags.split(","))
            if (! tag.isBlank())
                builder.appendTag(TagImpl.of(tag.trim()));
        return builder.build();
    }
}
