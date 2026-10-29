package org.phoebus.applications.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.phoebus.logbook.Attachment;
import org.phoebus.logbook.LogEntry;

/** Tests of {@link GitLogbook} */
@SuppressWarnings("nls")
public class GitLogbookTest
{
    @TempDir
    Path tmp;

    private File dir;
    private Git git;
    private GitRepository repo;

    @BeforeEach
    public void setup() throws Exception
    {
        dir = tmp.resolve("displays").toFile();
        git = Git.init().setDirectory(dir).setInitialBranch("master").call();
        git.getRepository().getConfig().setString("user", null, "name", "Operator Kim");
        git.getRepository().getConfig().setString("user", null, "email", "kim@example.com");
        repo = new GitRepository(dir);
    }

    @AfterEach
    public void cleanup()
    {
        repo.close();
        git.close();
    }

    private RevCommit commit(final String file, final String text, final String message) throws Exception
    {
        Files.writeString(dir.toPath().resolve(file), text);
        git.add().addFilepattern(".").call();
        return git.commit().setMessage(message).call();
    }

    private static String display(final String pv)
    {
        return "<display version=\"2.0.0\">\n  <name>Main</name>\n  <widget type=\"textupdate\" version=\"2.0.0\">\n" +
               "    <name>temp_1</name>\n    <pv_name>" + pv + "</pv_name>\n  </widget>\n</display>\n";
    }

    @Test
    public void testEntry() throws Exception
    {
        commit("main.bob", display("SR:T1"), "Add main display");
        commit("readme.txt", "x\n", "Add readme");
        final RevCommit change = commit("main.bob", display("SR:T2"), "Use second sensor\n\nRequested by operations.");
        Files.writeString(dir.toPath().resolve("readme.txt"), "y\n");
        git.add().addFilepattern(".").call();
        final RevCommit second = git.commit().setMessage("Update readme").call();

        final List<String> commits = List.of(second.name(), change.name());
        assertEquals("Git: displays master " + String.format(Messages.Commits, 2), GitLogbook.createTitle(repo, commits));
        assertEquals("Git: displays " + change.name().substring(0, 7) + " Use second sensor",
                     GitLogbook.createTitle(repo, List.of(change.name())));

        final String description = GitLogbook.createDescription(repo, commits, null);
        assertTrue(description.contains("### " + change.name().substring(0, 7) + " Use second sensor"), description);
        assertTrue(description.contains("Requested by operations."), description);
        assertTrue(description.contains("Operator Kim"), description);
        assertTrue(description.contains("**main.bob**"), description);
        // Markdown special characters in widget name are escaped
        assertTrue(description.contains("textupdate 'temp\\_1'   pv\\_name: SR:T1"), description);
        assertTrue(description.contains("- Modified readme.txt"), description);
        // Newest commit first
        assertTrue(description.indexOf("Update readme") < description.indexOf("Use second sensor"));

        GitApp.logbooks = "Operations, Controls";
        GitApp.log_tags = "Display";
        GitApp.log_level = "Info";
        final LogEntry entry = GitLogbook.createEntry(repo, commits, null);
        assertEquals("Info", entry.getLevel());
        assertEquals(GitLogbook.createTitle(repo, commits), entry.getTitle());
        assertEquals(2, entry.getLogbooks().size());
        assertEquals("Display", entry.getTags().iterator().next().getName());
        final Attachment attachment = entry.getAttachments().iterator().next();
        final String diff = Files.readString(attachment.getFile().toPath());
        assertTrue(diff.contains("-    <pv_name>SR:T1</pv_name>"), diff);
        assertTrue(diff.contains("+y"), diff);
        attachment.getFile().delete();
    }
}
