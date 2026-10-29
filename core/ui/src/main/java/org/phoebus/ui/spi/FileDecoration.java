package org.phoebus.ui.spi;

import javafx.scene.image.Image;

/** Decoration of a file in a file browser, provided by a {@link FileDecorator}
 *
 *  @param overlay Small image shown on top of the file icon, bottom right, or <code>null</code>
 *  @param prefix Text shown before the file name, or empty
 *  @param suffix Text shown after the file name, or empty
 *  @param style JavaFX CSS style for the file name, or <code>null</code>
 */
public record FileDecoration(Image overlay, String prefix, String suffix, String style)
{
}
