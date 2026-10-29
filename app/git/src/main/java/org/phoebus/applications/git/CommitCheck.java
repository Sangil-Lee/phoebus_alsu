package org.phoebus.applications.git;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.csstudio.display.builder.model.WidgetFactory;
import org.csstudio.display.builder.model.persist.ModelReader;
import org.phoebus.applications.git.GitRepository.ChangeType;
import org.phoebus.applications.git.GitRepository.StatusEntry;
import org.python.util.PythonInterpreter;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Checks of staged files before a commit
 *
 *  <p>Display files: XML, can the display builder read them, known widget types,
 *  do referenced displays, scripts, images exist and are they committed,
 *  optionally PV names.
 *  Python scripts: Syntax, as used by the display runtime (Jython).
 */
@SuppressWarnings("nls")
class CommitCheck
{
    /** Severity of a problem */
    enum Severity { ERROR, WARNING }

    /** Problem found in a file
     *  @param severity Severity
     *  @param path File
     *  @param message Description
     */
    record Problem(Severity severity, String path, String message) {}

    /** Python interpreter for syntax checks, created on first use */
    private static PythonInterpreter python = null;

    /** @param git Repository
     *  @return Problems in the staged files, empty if none
     */
    static List<Problem> check(final GitRepository git) throws Exception
    {
        final Set<Problem> problems = new LinkedHashSet<>();
        for (StatusEntry entry : git.getStatus().staged())
        {
            if (entry.type() == ChangeType.DELETED)
                continue;
            final String path = entry.path();
            if (BobDiff.supports(path))
                checkDisplay(git, path, git.getStagedVersions(path).new_content(), problems);
            else if (path.endsWith(".py"))
                checkPython(path, git.getStagedVersions(path).new_content(), problems);
        }
        return new ArrayList<>(problems);
    }

    /** Check a display file
     *  @param git Repository
     *  @param path Path of display file relative to work tree
     *  @param content Content that will be committed
     *  @param problems Problems found
     */
    static void checkDisplay(final GitRepository git, final String path, final byte[] content,
                             final Set<Problem> problems) throws Exception
    {
        final Element root;
        try
        {
            final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            root = factory.newDocumentBuilder().parse(new ByteArrayInputStream(content)).getDocumentElement();
        }
        catch (Exception ex)
        {
            problems.add(new Problem(Severity.ERROR, path, Messages.CheckInvalidXml + ": " + ex.getMessage()));
            return;
        }

        // Can the display builder read it?
        try
        {
            new ModelReader(new ByteArrayInputStream(content), path).readModel();
        }
        catch (Exception ex)
        {
            problems.add(new Problem(Severity.ERROR, path, Messages.CheckCannotRead + ": " + ex.getMessage()));
        }

        // Widget types known to this phoebus?
        final NodeList widgets = root.getElementsByTagName("widget");
        for (int i=0; i<widgets.getLength(); ++i)
        {
            final String type = ((Element) widgets.item(i)).getAttribute("type");
            try
            {
                WidgetFactory.getInstance().getAllWidgetDescriptors(type);
            }
            catch (Exception ex)
            {
                problems.add(new Problem(Severity.WARNING, path, Messages.CheckUnknownWidget + ": " + type));
            }
        }

        checkReferences(git, path, root, problems);
        checkPVNames(path, root, problems);
    }

    /** Check files referenced by a display: Open display actions, embedded displays, pictures, scripts, ... */
    private static void checkReferences(final GitRepository git, final String path, final Element root,
                                        final Set<Problem> problems) throws Exception
    {
        final List<String> references = new ArrayList<>();
        final NodeList files = root.getElementsByTagName("file");
        for (int i=0; i<files.getLength(); ++i)
            references.add(files.item(i).getTextContent().trim());
        final NodeList scripts = root.getElementsByTagName("script");
        for (int i=0; i<scripts.getLength(); ++i)
            references.add(((Element) scripts.item(i)).getAttribute("file").trim());
        final NodeList symbols = root.getElementsByTagName("symbol");
        for (int i=0; i<symbols.getLength(); ++i)
            references.add(symbols.item(i).getTextContent().trim());

        final File work_tree = git.getWorkTree().getAbsoluteFile();
        final Path display_dir = Paths.get(path).getParent();
        for (String reference : references)
        {
            if (! isCheckable(reference))
                continue;
            final Path referenced = Paths.get(reference);
            if (referenced.isAbsolute())
            {
                // Outside of the repository, may be installed elsewhere
                if (! referenced.toFile().exists())
                    problems.add(new Problem(Severity.WARNING, path, Messages.CheckMissingOutside + ": " + reference));
                continue;
            }
            final Path relative = (display_dir == null ? referenced : display_dir.resolve(referenced)).normalize();
            if (relative.startsWith(".."))
            {
                if (! work_tree.toPath().resolve(relative).normalize().toFile().exists())
                    problems.add(new Problem(Severity.WARNING, path, Messages.CheckMissingOutside + ": " + reference));
                continue;
            }
            final String repo_path = relative.toString().replace(File.separatorChar, '/');
            if (git.isInIndex(repo_path))
                continue;
            if (new File(work_tree, repo_path).exists())
                problems.add(new Problem(Severity.WARNING, path, Messages.CheckNotCommitted + ": " + reference));
            else
                problems.add(new Problem(Severity.ERROR, path, Messages.CheckMissing + ": " + reference));
        }
    }

    /** @param reference File reference from a display
     *  @return Is it a file that can be checked, not a URL, embedded script, path with macros?
     */
    private static boolean isCheckable(final String reference)
    {
        if (reference.isEmpty()  ||  reference.contains("$(")  ||  reference.contains("${"))
            return false;
        final String lower = reference.toLowerCase();
        return ! (lower.startsWith("http:")  ||  lower.startsWith("https:")  ||  lower.startsWith("ftp:")  ||
                  lower.startsWith("pv:")  ||  lower.startsWith("examples:")  ||  lower.startsWith("embedded"));
    }

    /** Check PV names against the configured pattern */
    private static void checkPVNames(final String path, final Element root, final Set<Problem> problems)
    {
        if (GitApp.pv_name_pattern == null  ||  GitApp.pv_name_pattern.isBlank())
            return;
        final Pattern pattern = Pattern.compile(GitApp.pv_name_pattern);
        final NodeList names = root.getElementsByTagName("pv_name");
        for (int i=0; i<names.getLength(); ++i)
        {
            String name = names.item(i).getTextContent().trim();
            // Local and simulated PVs, formulas, names with macros are not checked
            if (name.isEmpty()  ||  name.contains("$(")  ||  name.contains("${")  ||  name.startsWith("=")  ||
                name.startsWith("loc://")  ||  name.startsWith("sim://"))
                continue;
            // Protocol prefix is not part of the name
            final int protocol = name.indexOf("://");
            if (protocol > 0)
                name = name.substring(protocol + 3);
            if (! pattern.matcher(name).matches())
                problems.add(new Problem(Severity.WARNING, path, Messages.CheckPVName + ": " + name));
        }
    }

    /** Check Python syntax as the display runtime would use it (Jython) */
    static void checkPython(final String path, final byte[] content, final Set<Problem> problems)
    {
        try
        {
            getPython().compile(new StringReader(new String(content, StandardCharsets.UTF_8)), path);
        }
        catch (Throwable ex)
        {
            problems.add(new Problem(Severity.WARNING, path,
                                     Messages.CheckPythonSyntax + ": " + ex.toString().replace('\n', ' ').trim()));
        }
    }

    private static synchronized PythonInterpreter getPython()
    {
        if (python == null)
        {
            // Jython is initialized once per JVM.
            // Let the display runtime do that with its settings (python_path, ...),
            // so that checking a script does not change how displays run scripts
            try
            {
                Class.forName("org.csstudio.display.builder.runtime.script.internal.JythonScriptSupport",
                              true, CommitCheck.class.getClassLoader());
            }
            catch (Throwable ex)
            {
                // No display runtime: Quiet, light interpreter without 'site' import
                final Properties props = new Properties();
                props.setProperty("python.import.site", "false");
                props.setProperty("python.console.encoding", "UTF-8");
                PythonInterpreter.initialize(System.getProperties(), props, new String[0]);
            }
            python = new PythonInterpreter();
        }
        return python;
    }
}
