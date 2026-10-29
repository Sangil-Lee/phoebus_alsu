package org.phoebus.applications.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.StoredConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.phoebus.ui.spi.FileDecoration;
import org.phoebus.ui.spi.FileDecorator;

/** Tests of the File Browser decorations */
@SuppressWarnings("nls")
public class GitFileDecoratorTest
{
    @TempDir
    Path tmp;

    private File dir;
    private Git git;
    private GitFileDecorator decorator;

    @BeforeEach
    public void setup() throws Exception
    {
        dir = tmp.resolve("repo").toFile();
        git = Git.init().setDirectory(dir).setInitialBranch("master").call();
        final StoredConfig config = git.getRepository().getConfig();
        config.setString("user", null, "name", "Tester");
        config.setString("user", null, "email", "tester@example.com");
        config.save();
        write("a.txt", "a\n");
        write("ui/b.bob", "<display/>\n");
        write("ui/sub/c.bob", "<display/>\n");
        write("doc/d.txt", "d\n");
        write(".gitignore", "*.log\nbuild/\n");
        git.add().addFilepattern(".").call();
        git.commit().setMessage("first").call();
        decorator = new GitFileDecorator();
    }

    @AfterEach
    public void cleanup()
    {
        decorator.close();
        git.close();
    }

    private void write(final String path, final String text) throws Exception
    {
        final Path file = dir.toPath().resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    /** @return Decoration, after waiting for the background read of the repository */
    private FileDecoration decorate(final String path) throws Exception
    {
        // Top directory is decorated once the state has been read
        for (int i=0; i<50  &&  decorator.getDecoration(dir) == null; ++i)
            Thread.sleep(100);
        return decorator.getDecoration(path.isEmpty() ? dir : new File(dir, path));
    }

    @Test
    public void testServiceRegistered()
    {
        assertTrue(FileDecorator.getDecorators().stream().anyMatch(d -> d instanceof GitFileDecorator));
    }

    @Test
    public void testDecorations() throws Exception
    {
        write("ui/sub/c.bob", "<display version='2'/>\n");
        write("new.txt", "new\n");
        write("staged.txt", "staged\n");
        git.add().addFilepattern("staged.txt").call();
        write("x.log", "log\n");
        write("build/out.txt", "out\n");

        // Top directory shows branch
        assertEquals("  [master *]", decorate("").suffix());

        assertEquals("> ", decorate("ui/sub/c.bob").prefix());
        assertEquals("> ", decorate("ui").prefix());
        assertEquals("> ", decorate("ui/sub").prefix());
        assertEquals("? ", decorate("new.txt").prefix());
        assertEquals("+ ", decorate("staged.txt").prefix());
        assertNull(decorate("a.txt"));
        assertNull(decorate("doc"));
        assertNull(decorate("ui/b.bob"));
        assertNull(decorate(".git"));
        assertTrue(decorate("x.log").style().contains("#a0a0a0"));
        assertTrue(decorate("build").style().contains("#a0a0a0"));
        assertTrue(decorate("build/out.txt").style().contains("#a0a0a0"));

        // Outside of a repository
        final File plain = tmp.resolve("plain").toFile();
        plain.mkdirs();
        assertNull(decorator.getDecoration(plain));
        assertEquals(1, decorator.getRepositoryCount());
    }

    @Test
    public void testUpdate() throws Exception
    {
        assertTrue(decorate("") != null);
        assertNull(decorate("a.txt"));
        final AtomicInteger changes = new AtomicInteger();
        decorator.addListener(changes::incrementAndGet);
        // Wait for 'own changes' period to pass
        Thread.sleep(1500);
        write("a.txt", "changed\n");
        for (int i=0; i<50  &&  decorate("a.txt") == null; ++i)
            Thread.sleep(100);
        assertEquals("> ", decorate("a.txt").prefix());
        assertTrue(changes.get() > 0);
        assertEquals("  [master *]", decorate("").suffix());
    }
}
