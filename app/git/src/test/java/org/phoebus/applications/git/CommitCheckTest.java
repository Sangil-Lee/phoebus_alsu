package org.phoebus.applications.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.phoebus.applications.git.CommitCheck.Problem;
import org.phoebus.applications.git.CommitCheck.Severity;

/** Tests of {@link CommitCheck} */
@SuppressWarnings("nls")
public class CommitCheckTest
{
    @TempDir
    Path tmp;

    private File dir;
    private Git git;
    private GitRepository repo;

    @BeforeEach
    public void setup() throws Exception
    {
        dir = tmp.toFile();
        git = Git.init().setDirectory(dir).setInitialBranch("master").call();
        repo = new GitRepository(dir);
        GitApp.pv_name_pattern = "";
    }

    @AfterEach
    public void cleanup()
    {
        repo.close();
        git.close();
    }

    private void write(final String path, final String text) throws Exception
    {
        final Path file = dir.toPath().resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private static String display(final String widgets)
    {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<display version=\"2.0.0\">\n  <name>Test</name>\n" + widgets + "</display>\n";
    }

    private static String button(final String target)
    {
        return "  <widget type=\"action_button\" version=\"3.0.0\">\n    <name>open</name>\n    <actions>\n" +
               "      <action type=\"open_display\">\n        <file>" + target + "</file>\n      </action>\n    </actions>\n  </widget>\n";
    }

    private List<String> check() throws Exception
    {
        return CommitCheck.check(repo).stream().map(p -> p.severity() + "|" + p.path() + "|" + p.message()).toList();
    }

    @Test
    public void testValidDisplay() throws Exception
    {
        write("main.bob", display(button("sub/detail.bob") +
              "  <widget type=\"textupdate\" version=\"2.0.0\">\n    <name>t</name>\n    <pv_name>SR:T1</pv_name>\n" +
              "    <scripts>\n      <script file=\"scripts/calc.py\"/>\n      <script file=\"EmbeddedPy\"/>\n    </scripts>\n  </widget>\n"));
        write("sub/detail.bob", display(button("../main.bob") + button("$(DIR)/other.bob")));
        write("scripts/calc.py", "x = 1\nprint(x)\n");
        repo.stage(List.of("main.bob", "sub/detail.bob", "scripts/calc.py"));
        assertEquals(List.of(), check());
    }

    @Test
    public void testInvalidDisplay() throws Exception
    {
        write("broken.bob", "<display><name>Broken</display>");
        write("unknown.bob", display("  <widget type=\"no_such_widget\" version=\"2.0.0\">\n    <name>x</name>\n  </widget>\n"));
        repo.stage(List.of("broken.bob", "unknown.bob"));
        final List<String> problems = check();
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("ERROR|broken.bob|" + Messages.CheckInvalidXml)), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.equals("WARNING|unknown.bob|" + Messages.CheckUnknownWidget + ": no_such_widget")),
                   problems.toString());
    }

    @Test
    public void testReferences() throws Exception
    {
        write("main.bob", display(button("missing.bob") + button("untracked.bob") + button("/no/such/abs.bob")));
        write("untracked.bob", display(""));
        repo.stage(List.of("main.bob"));
        final List<String> problems = check();
        assertTrue(problems.contains("ERROR|main.bob|" + Messages.CheckMissing + ": missing.bob"), problems.toString());
        assertTrue(problems.contains("WARNING|main.bob|" + Messages.CheckNotCommitted + ": untracked.bob"), problems.toString());
        assertTrue(problems.contains("WARNING|main.bob|" + Messages.CheckMissingOutside + ": /no/such/abs.bob"), problems.toString());
        // Staging the referenced file resolves the warning
        repo.stage(List.of("untracked.bob"));
        assertTrue(check().stream().noneMatch(p -> p.contains("untracked.bob")));
    }

    @Test
    public void testPVNames() throws Exception
    {
        write("pvs.bob", display(
            "  <widget type=\"textupdate\" version=\"2.0.0\">\n    <name>a</name>\n    <pv_name>SR:BPM1:X</pv_name>\n  </widget>\n" +
            "  <widget type=\"textupdate\" version=\"2.0.0\">\n    <name>b</name>\n    <pv_name>ca://bad name</pv_name>\n  </widget>\n" +
            "  <widget type=\"textupdate\" version=\"2.0.0\">\n    <name>c</name>\n    <pv_name>loc://x(1)</pv_name>\n  </widget>\n" +
            "  <widget type=\"textupdate\" version=\"2.0.0\">\n    <name>d</name>\n    <pv_name>$(P):T</pv_name>\n  </widget>\n"));
        repo.stage(List.of("pvs.bob"));
        // No pattern: No PV check
        assertEquals(List.of(), check());
        GitApp.pv_name_pattern = "[A-Z0-9]+(:[A-Za-z0-9_]+)+";
        assertEquals(List.of("WARNING|pvs.bob|" + Messages.CheckPVName + ": bad name"), check());
    }

    @Test
    public void testPython() throws Exception
    {
        write("ok.py", "from org.csstudio.display.builder.runtime.script import PVUtil\nv = 1\n");
        write("bad.py", "def f(:\n    pass\n");
        repo.stage(List.of("ok.py", "bad.py"));
        final List<Problem> problems = CommitCheck.check(repo);
        assertEquals(1, problems.size(), problems.toString());
        assertEquals("bad.py", problems.get(0).path());
        assertEquals(Severity.WARNING, problems.get(0).severity());
        assertTrue(problems.get(0).message().startsWith(Messages.CheckPythonSyntax));
    }
}
