package org.phoebus.ui.spi;

import java.io.File;
import java.util.List;
import java.util.ServiceLoader;

/** SPI for decorating files in a file browser
 *
 *  <p>For example, a version control system can mark modified files.
 *  Implementations are registered via
 *  <code>META-INF/services/org.phoebus.ui.spi.FileDecorator</code>.
 */
public interface FileDecorator
{
    /** Called on the UI thread for every shown file, so it must be fast,
     *  for example by returning cached information and updating it in the background.
     *
     *  @param file File or directory
     *  @return Decoration for the file, or <code>null</code>
     */
    FileDecoration getDecoration(File file);

    /** @param on_change Called, on any thread, when decorations changed and should be fetched again */
    void addListener(Runnable on_change);

    /** @param on_change Listener to remove */
    void removeListener(Runnable on_change);

    /** @return All registered decorators */
    static List<FileDecorator> getDecorators()
    {
        return Decorators.DECORATORS;
    }

    /** @param file File or directory
     *  @return Decoration of the first decorator that has one, or <code>null</code>
     */
    static FileDecoration decorate(final File file)
    {
        for (FileDecorator decorator : Decorators.DECORATORS)
        {
            final FileDecoration decoration = decorator.getDecoration(file);
            if (decoration != null)
                return decoration;
        }
        return null;
    }
}

/** Lazily loaded list of decorators */
class Decorators
{
    static final List<FileDecorator> DECORATORS = ServiceLoader.load(FileDecorator.class)
                                                               .stream()
                                                               .map(ServiceLoader.Provider::get)
                                                               .toList();
}
