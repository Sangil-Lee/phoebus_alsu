package org.phoebus.applications.git;

import java.util.function.IntSupplier;
import java.util.function.Supplier;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revplot.AbstractPlotRenderer;
import org.eclipse.jgit.revplot.PlotCommit;
import org.eclipse.jgit.revplot.PlotLane;
import org.phoebus.applications.git.GitRepository.CommitInfo;

import javafx.geometry.Insets;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.TableCell;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;

/** Table cell that shows the commit graph, branch and tag labels, and the commit message
 *
 *  <p>Graph and labels are drawn into a canvas by the JGit plot renderer,
 *  the message is the cell text so that it follows the row selection colors.
 */
@SuppressWarnings("nls")
class CommitGraphCell extends TableCell<CommitInfo, CommitInfo>
{
    /** Lane colors, by lane position */
    private static final Color[] LANE_COLORS =
    {
        Color.web("#1f77b4"), Color.web("#2ca02c"), Color.web("#d62728"), Color.web("#9467bd"),
        Color.web("#ff7f0e"), Color.web("#17becf"), Color.web("#8c564b"), Color.web("#e377c2"),
    };

    private static final Color LOCAL_BRANCH = Color.web("#d4f5d4"),
                               REMOTE_BRANCH = Color.web("#dde7f7"),
                               TAG = Color.web("#fff3c4"),
                               LABEL_BORDER = Color.web("#8a8a8a");

    private static final Font LABEL_FONT = Font.font(Font.getDefault().getFamily(), Font.getDefault().getSize() - 1),
                              CURRENT_FONT = Font.font(Font.getDefault().getFamily(), FontWeight.BOLD, Font.getDefault().getSize() - 1);

    private final Canvas canvas = new Canvas();
    private final Renderer renderer = new Renderer();

    /** Full name of the checked out branch, e.g. "refs/heads/master", or <code>null</code> */
    private final Supplier<String> current_branch;

    /** Width of the graph area, labels and message start after it */
    private final IntSupplier graph_width;

    /** @param current_branch Provides the full name of the checked out branch
     *  @param graph_width Provides the width of the graph area, so that messages line up
     */
    CommitGraphCell(final Supplier<String> current_branch, final IntSupplier graph_width)
    {
        this.current_branch = current_branch;
        this.graph_width = graph_width;
        setPadding(new Insets(0, 2, 0, 0));
        setContentDisplay(ContentDisplay.LEFT);
        setGraphicTextGap(0);
    }

    @Override
    protected void updateItem(final CommitInfo commit, final boolean empty)
    {
        super.updateItem(commit, empty);
        setStyle(null);
        if (empty  ||  commit == null)
        {
            setText(null);
            setGraphic(null);
            return;
        }
        if (commit.plot() == null)
        {
            // 'Uncommitted changes' row: No graph, aligned with the messages
            setGraphic(null);
            setText(commit.message());
            setStyle("-fx-font-style: italic; -fx-padding: 0 0 0 " + graph_width.getAsInt() + ";");
            return;
        }
        final double height = getTableView().getFixedCellSize() > 0 ? getTableView().getFixedCellSize() : 24;
        final int h = (int) height;

        // First pass determines where labels start and the width, second pass draws
        renderer.gc = null;
        renderer.text_x = 0;
        renderer.first_x = -1;
        renderer.shift = 0;
        renderer.paint(commit.plot(), h);
        // Move labels and message right to the end of the graph area
        renderer.shift = Math.max(0, graph_width.getAsInt() - renderer.first_x);
        canvas.setWidth(renderer.text_x + renderer.shift);
        canvas.setHeight(height);

        renderer.gc = canvas.getGraphicsContext2D();
        renderer.gc.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
        renderer.paint(commit.plot(), h);

        setGraphic(canvas);
        setText(commit.message());
    }

    /** JGit plot renderer for a JavaFX canvas
     *
     *  <p>With <code>gc == null</code>, only determines the width
     */
    private class Renderer extends AbstractPlotRenderer<PlotLane, Color>
    {
        GraphicsContext gc;

        /** Color of the commit's lane, and is it a merge? Used for the commit dot */
        private Color dot_color;
        private boolean merge;

        /** X position where the message text starts */
        double text_x;

        /** X position of the first label or the message, determined in first pass */
        int first_x;

        /** Offset added to labels and message */
        int shift;

        void paint(final PlotCommit<PlotLane> commit, final int h)
        {
            dot_color = laneColor(commit.getLane());
            merge = commit.getParentCount() > 1;
            paintCommit(commit, h);
        }

        @Override
        protected Color laneColor(final PlotLane lane)
        {
            if (lane == null)
                return LANE_COLORS[0];
            return LANE_COLORS[lane.getPosition() % LANE_COLORS.length];
        }

        @Override
        protected void drawLine(final Color color, final int x1, final int y1, final int x2, final int y2, final int width)
        {
            if (gc == null)
                return;
            gc.setStroke(color);
            gc.setLineWidth(width);
            gc.strokeLine(x1, y1, x2, y2);
        }

        @Override
        protected void drawCommitDot(final int x, final int y, final int w, final int h)
        {
            if (gc == null)
                return;
            // Filled with lane color, merge commits as hollow circle
            gc.setFill(merge ? Color.WHITE : dot_color);
            gc.fillOval(x, y, w, h);
            gc.setStroke(merge ? dot_color : dot_color.darker());
            gc.setLineWidth(merge ? 2 : 1);
            gc.strokeOval(x, y, w, h);
        }

        @Override
        protected void drawBoundaryDot(final int x, final int y, final int w, final int h)
        {
            if (gc == null)
                return;
            gc.setStroke(Color.GRAY);
            gc.setLineWidth(1);
            gc.strokeOval(x, y, w, h);
        }

        @Override
        protected int drawLabel(final int label_x, final int y, final Ref ref)
        {
            if (first_x < 0)
                first_x = label_x;
            final int x = label_x + shift;
            final String name = ref.getName();
            final Color background;
            if (name.startsWith(Constants.R_HEADS))
                background = LOCAL_BRANCH;
            else if (name.startsWith(Constants.R_REMOTES))
                background = REMOTE_BRANCH;
            else if (name.startsWith(Constants.R_TAGS))
                background = TAG;
            else // HEAD, stash, ...
                return 0;
            if (name.endsWith("/" + Constants.HEAD))
                return 0;

            final String label = Repository.shortenRefName(name);
            final Font font = name.equals(current_branch.get()) ? CURRENT_FONT : LABEL_FONT;
            final Text text = new Text(label);
            text.setFont(font);
            final double width = Math.ceil(text.getLayoutBounds().getWidth()) + 8;
            final double height = Math.ceil(text.getLayoutBounds().getHeight()) + 2;
            if (gc != null)
            {
                final double top = y - height / 2;
                gc.setFill(background);
                gc.fillRoundRect(x, top, width, height, 6, 6);
                gc.setStroke(LABEL_BORDER);
                gc.setLineWidth(1);
                gc.strokeRoundRect(x + 0.5, top + 0.5, width - 1, height - 1, 6, 6);
                gc.setFill(Color.BLACK);
                gc.setFont(font);
                gc.setTextAlign(TextAlignment.LEFT);
                gc.setTextBaseline(VPos.CENTER);
                gc.fillText(label, x + 4, y);
            }
            return (int) width + 4;
        }

        @Override
        protected void drawText(final String message, final int x, final int y)
        {
            // Message is shown as cell text, only remember where it starts
            if (first_x < 0)
                first_x = x;
            text_x = x;
        }
    }
}
