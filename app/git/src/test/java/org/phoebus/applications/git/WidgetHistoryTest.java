package org.phoebus.applications.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.csstudio.display.builder.model.DisplayModel;
import org.csstudio.display.builder.model.Widget;
import org.csstudio.display.builder.model.persist.ModelReader;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.StoredConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.phoebus.applications.git.BobDiff.Change;
import org.phoebus.applications.git.BobDiff.Kind;
import org.phoebus.applications.git.WidgetHistory.Entry;

/** Tests of widget keys and widget history */
@SuppressWarnings("nls")
public class WidgetHistoryTest
{
    @TempDir
    Path tmp;

    static String display(final String temp_pv, final String label_text, final boolean with_temp)
    {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <display version="2.0.0">
              <name>Test</name>
              <widget type="group" version="2.0.0">
                <name>Box</name>
            """ + (with_temp ? """
                <widget type="textupdate" version="2.0.0">
                  <name>temp</name>
                  <pv_name>%s</pv_name>
                </widget>
            """.formatted(temp_pv) : "") + """
                <widget type="label" version="2.0.0">
                  <name>L</name>
                  <text>%s</text>
                </widget>
                <widget type="label" version="2.0.0">
                  <name>L</name>
                  <text>second</text>
                </widget>
              </widget>
              <widget type="tabs" version="2.0.0">
                <name>Tabs</name>
                <tabs>
                  <tab>
                    <name>A</name>
                    <children>
                      <widget type="led" version="2.0.0">
                        <name>led</name>
                      </widget>
                    </children>
                  </tab>
                  <tab>
                    <name>B</name>
                    <children>
                      <widget type="led" version="2.0.0">
                        <name>led</name>
                      </widget>
                    </children>
                  </tab>
                </tabs>
              </widget>
            </display>
            """.formatted(label_text);
    }

    private static DisplayModel read(final String xml) throws Exception
    {
        return new ModelReader(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))).readModel();
    }

    @Test
    public void testKeysMatchBobDiff() throws Exception
    {
        final String xml = display("pv1", "first", true);
        final DisplayModel model = read(xml);
        final Map<String, Widget> widgets = WidgetKeys.of(model);
        final Set<String> diff_keys = new LinkedHashSet<>();
        for (Change change : BobDiff.compare(new byte[0], xml.getBytes(StandardCharsets.UTF_8)))
            if (! change.key().isEmpty())
                diff_keys.add(change.key());
        assertEquals(List.copyOf(diff_keys), List.copyOf(widgets.keySet()));
        assertEquals(List.of("/group:Box", "/group:Box/textupdate:temp", "/group:Box/label:L", "/group:Box/label:L#2",
                             "/tabs:Tabs", "/tabs:Tabs/led:led", "/tabs:Tabs/led:led#2"),
                     List.copyOf(widgets.keySet()));
        final Widget second = widgets.get("/group:Box/label:L#2");
        assertEquals("/group:Box/label:L#2", WidgetKeys.keyOf(second));
        assertEquals("", WidgetKeys.keyOf(model));

        // Written model compares equal to normalized file
        final byte[] written = WidgetKeys.write(model);
        assertTrue(BobDiff.compare(WidgetKeys.normalize(xml.getBytes(StandardCharsets.UTF_8)), written).isEmpty());
    }

    @Test
    public void testHistory() throws Exception
    {
        final File dir = tmp.resolve("repo").toFile();
        try (Git git = Git.init().setDirectory(dir).setInitialBranch("master").call())
        {
            final StoredConfig config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Tester");
            config.setString("user", null, "email", "tester@example.com");
            config.save();
            final Path file = dir.toPath().resolve("ui/test.bob");
            Files.createDirectories(file.getParent());
            final String[][] versions =
            {
                { display("pv1", "first", true), "add" },
                { display("pv2", "first", true), "change pv" },
                { display("pv2", "other", true), "change label" },
                { display("pv2", "other", false), "remove temp" },
            };
            for (String[] version : versions)
            {
                Files.writeString(file, version[0]);
                git.add().addFilepattern(".").call();
                git.commit().setMessage(version[1]).call();
            }

            try (GitRepository repo = new GitRepository(dir))
            {
                List<Entry> history = WidgetHistory.compute(repo, "ui/test.bob", "/group:Box/textupdate:temp", null, 100);
                assertEquals(List.of("remove temp", "change pv", "add"), history.stream().map(e -> e.commit().message()).toList());
                assertEquals(Kind.REMOVED, history.get(0).changes().get(0).kind());
                final Change pv = history.get(1).changes().get(0);
                assertEquals("pv_name", pv.property());
                assertEquals("pv1", pv.old_value());
                assertEquals("pv2", pv.new_value());
                assertEquals(Kind.ADDED, history.get(2).changes().get(0).kind());

                history = WidgetHistory.compute(repo, "ui/test.bob", "/group:Box/label:L", null, 100);
                assertEquals(List.of("change label", "add"), history.stream().map(e -> e.commit().message()).toList());
                // Second label with same name is told apart
                history = WidgetHistory.compute(repo, "ui/test.bob", "/group:Box/label:L#2", null, 100);
                assertEquals(List.of("add"), history.stream().map(e -> e.commit().message()).toList());

                // Unsaved change in an editor: Model written by the editor
                final DisplayModel model = read(display("pv2", "unsaved", false));
                history = WidgetHistory.compute(repo, "ui/test.bob", "/group:Box/label:L", WidgetKeys.write(model), 100);
                assertNull(history.get(0).commit());
                assertEquals("unsaved", history.get(0).changes().get(0).new_value());
                assertEquals(3, history.size());
            }
        }
    }
}
