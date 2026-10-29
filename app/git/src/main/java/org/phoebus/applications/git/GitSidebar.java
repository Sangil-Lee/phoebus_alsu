package org.phoebus.applications.git;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.phoebus.applications.git.GitRepository.BranchInfo;
import org.phoebus.applications.git.GitRepository.RemoteBranch;
import org.phoebus.applications.git.GitRepository.State;
import org.phoebus.applications.git.GitRepository.StashInfo;
import org.phoebus.applications.git.GitRepository.SubmoduleInfo;
import org.phoebus.applications.git.GitRepository.SubmoduleState;
import org.phoebus.applications.git.GitRepository.TagInfo;

import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.MouseButton;

/** Side bar: Workspace views, branches, tags, remotes, stashes */
@SuppressWarnings("nls")
class GitSidebar extends TreeView<Object>
{
    /** Actions triggered from the side bar */
    interface Actions
    {
        void showFileStatus();
        /** @param commit Commit to select, <code>null</code> to keep the selection */
        void showHistory(String commit);
        void showStash(StashInfo stash);
        void checkout(BranchInfo branch);
        void checkoutRemote(RemoteBranch branch);
        /** @param start_point Commit id, branch or tag, <code>null</code> for HEAD */
        void createBranch(String start_point);
        void deleteBranch(BranchInfo branch);
        /** @param drop Drop after applying ('pop')? */
        void applyStash(StashInfo stash, boolean drop);
        void dropStash(StashInfo stash);
        /** @param branch Local or remote branch to merge into the current branch */
        void merge(String branch);
        /** @param branch Local or remote branch onto which the current branch is rebased, after asking user */
        void rebaseOnto(String branch);
        /** Prompt for new name of local branch */
        void renameBranch(BranchInfo branch);
        void pushTag(TagInfo tag);
        void deleteTag(TagInfo tag);
        /** Delete tag on the remote, after asking user */
        void deleteRemoteTag(TagInfo tag);
        /** Prompt for name and URL, add remote */
        void addRemote();
        /** Prompt for new URL of a remote */
        void editRemote(String name, String url);
        /** Remove a remote, after asking user */
        void removeRemote(String name);
        /** Show the reflog */
        void showReflog();
        /** @param ref Branch or tag to compare with the current branch */
        void compareWithCurrent(String ref);
        /** @param paths Submodules to initialize and update, empty for all */
        void updateSubmodules(List<String> paths);
        /** Open submodule in a new Git view */
        void openSubmodule(SubmoduleInfo submodule);
        /** Edit fetch/push URLs, ref specs, ... of a remote */
        void remoteSettings(String name);
    }

    /** Section header */
    private record Header(String label) {}

    /** View of the workspace section */
    private enum View { FILE_STATUS, HISTORY, REFLOG }

    /** Entry in the workspace section */
    private record Workspace(String label, View view) {}

    /** Remote, parent of its branches */
    private record Remote(String name, String url) {}

    private final Actions actions;

    private final TreeItem<Object> file_status = new TreeItem<>(new Workspace(Messages.FileStatus, View.FILE_STATUS));
    private final TreeItem<Object> history = new TreeItem<>(new Workspace(Messages.History, View.HISTORY));
    private final TreeItem<Object> reflog = new TreeItem<>(new Workspace(Messages.Reflog, View.REFLOG));
    private final TreeItem<Object> branches = new TreeItem<>(new Header(Messages.HeaderBranches));
    private final TreeItem<Object> tags = new TreeItem<>(new Header(Messages.HeaderTags));
    private final TreeItem<Object> remotes = new TreeItem<>(new Header(Messages.HeaderRemotes));
    private final TreeItem<Object> stashes = new TreeItem<>(new Header(Messages.HeaderStashes));
    private final TreeItem<Object> submodules = new TreeItem<>(new Header(Messages.HeaderSubmodules));

    /** Ignore selection changes while updating the tree */
    private boolean updating = false;

    /** Names of remotes that are expanded, kept across updates */
    private final Set<String> expanded_remotes = new HashSet<>();

    GitSidebar(final Actions actions)
    {
        this.actions = actions;

        final TreeItem<Object> workspace = new TreeItem<>(new Header(Messages.HeaderWorkspace));
        workspace.getChildren().setAll(List.of(file_status, history, reflog));
        workspace.setExpanded(true);
        branches.setExpanded(true);

        final TreeItem<Object> root = new TreeItem<>();
        root.getChildren().setAll(List.of(workspace, branches, tags, remotes, stashes));
        setRoot(root);
        setShowRoot(false);
        setCellFactory(view -> new SidebarCell());
        setPrefWidth(220);

        getSelectionModel().selectedItemProperty().addListener((prop, old, item) ->
        {
            if (item != null  &&  ! updating)
                select(item.getValue());
        });
        getSelectionModel().select(file_status);
    }

    private void select(final Object value)
    {
        if (value instanceof Workspace ws)
        {
            switch (ws.view())
            {
            case HISTORY -> actions.showHistory(null);
            case REFLOG  -> actions.showReflog();
            default      -> actions.showFileStatus();
            }
        }
        else if (value instanceof BranchInfo branch)
            actions.showHistory(branch.commit());
        else if (value instanceof RemoteBranch branch)
            actions.showHistory(branch.commit());
        else if (value instanceof TagInfo tag)
            actions.showHistory(tag.commit());
        else if (value instanceof StashInfo stash)
            actions.showStash(stash);
    }

    /** @param state Repository state to show */
    void update(final State state)
    {
        final TreeItem<Object> old_selection = getSelectionModel().getSelectedItem();
        final Object selected = old_selection == null ? null : old_selection.getValue();
        updating = true;
        try
        {
            updateItems(state);
        }
        finally
        {
            updating = false;
        }

        // Restore selection of the same branch, tag, ...
        // If it's gone, show the history, or file status instead of a removed stash
        if (selected == null  ||  selected instanceof Workspace)
            return;
        final TreeItem<Object> same = find(getRoot(), selected);
        if (same != null)
        {
            updating = true;
            getSelectionModel().select(same);
            updating = false;
        }
        else
            getSelectionModel().select(selected instanceof StashInfo ? file_status : history);
    }

    /** @return Item that represents the same branch, tag, ... */
    private static TreeItem<Object> find(final TreeItem<Object> parent, final Object value)
    {
        for (TreeItem<Object> item : parent.getChildren())
        {
            if (getKey(item.getValue()).equals(getKey(value)))
                return item;
            final TreeItem<Object> found = find(item, value);
            if (found != null)
                return found;
        }
        return null;
    }

    /** @return Identity of a side bar entry, ignoring changes like a new commit id */
    private static String getKey(final Object value)
    {
        if (value instanceof BranchInfo branch)
            return "branch:" + branch.name();
        if (value instanceof RemoteBranch branch)
            return "remote_branch:" + branch.fullName();
        if (value instanceof TagInfo tag)
            return "tag:" + tag.name();
        if (value instanceof StashInfo stash)
            return "stash:" + stash.commit();
        if (value instanceof SubmoduleInfo submodule)
            return "submodule:" + submodule.path();
        return String.valueOf(value);
    }

    private void updateItems(final State state)
    {
        for (TreeItem<Object> remote : remotes.getChildren())
            if (remote.isExpanded())
                expanded_remotes.add(((Remote) remote.getValue()).name());
            else
                expanded_remotes.remove(((Remote) remote.getValue()).name());

        branches.getChildren().setAll(state.branches().stream().map(b -> new TreeItem<Object>(b)).toList());
        tags.getChildren().setAll(state.tags().stream().map(t -> new TreeItem<Object>(t)).toList());
        stashes.getChildren().setAll(state.stashes().stream().map(s -> new TreeItem<Object>(s)).toList());
        submodules.getChildren().setAll(state.submodules().stream().map(s -> new TreeItem<Object>(s)).toList());
        submodules.setExpanded(true);
        // Section only shown when there are submodules
        final List<TreeItem<Object>> sections = getRoot().getChildren();
        if (state.submodules().isEmpty())
            sections.remove(submodules);
        else if (! sections.contains(submodules))
            sections.add(submodules);

        remotes.getChildren().clear();
        for (Map.Entry<String, List<RemoteBranch>> entry : state.remotes().entrySet())
        {
            final TreeItem<Object> remote = new TreeItem<>(new Remote(entry.getKey(), state.remote_urls().get(entry.getKey())));
            remote.getChildren().setAll(entry.getValue().stream().map(b -> new TreeItem<Object>(b)).toList());
            remote.setExpanded(expanded_remotes.contains(entry.getKey()));
            remotes.getChildren().add(remote);
        }
    }

    /** Show the 'File Status' view */
    void selectFileStatus()
    {
        getSelectionModel().select(file_status);
    }

    private class SidebarCell extends TreeCell<Object>
    {
        SidebarCell()
        {
            // Double-click on local branch checks it out, on submodule opens it
            setOnMouseClicked(event ->
            {
                if (event.getButton() == MouseButton.PRIMARY  &&  event.getClickCount() == 2)
                {
                    if (getItem() instanceof BranchInfo branch  &&  ! branch.current())
                        actions.checkout(branch);
                    else if (getItem() instanceof SubmoduleInfo submodule  &&  submodule.state() != SubmoduleState.UNINITIALIZED)
                        actions.openSubmodule(submodule);
                }
            });
        }

        @Override
        protected void updateItem(final Object value, final boolean empty)
        {
            super.updateItem(value, empty);
            setStyle(null);
            setContextMenu(null);
            setTooltip(null);
            if (empty  ||  value == null)
            {
                setText(null);
                return;
            }
            if (value instanceof Header header)
            {
                setText(header.label());
                setStyle("-fx-font-weight: bold; -fx-text-fill: gray;");
                if (getTreeItem() == remotes)
                {
                    final MenuItem add = new MenuItem(Messages.AddRemote);
                    add.setOnAction(event -> actions.addRemote());
                    setContextMenu(new ContextMenu(add));
                }
                else if (getTreeItem() == submodules)
                {
                    final MenuItem update = new MenuItem(Messages.UpdateAllSubmodules);
                    update.setOnAction(event -> actions.updateSubmodules(List.of()));
                    setContextMenu(new ContextMenu(update));
                }
            }
            else if (value instanceof Workspace ws)
                setText(ws.label());
            else if (value instanceof BranchInfo branch)
            {
                final StringBuilder text = new StringBuilder(branch.name());
                if (branch.ahead() > 0)
                    text.append("  ↑").append(branch.ahead());
                if (branch.behind() > 0)
                    text.append("  ↓").append(branch.behind());
                setText(text.toString());
                if (branch.current())
                    setStyle("-fx-font-weight: bold;");
                setContextMenu(createMenu(branch));
            }
            else if (value instanceof Remote remote)
            {
                setText(remote.name());
                if (remote.url() != null)
                    setTooltip(new Tooltip(remote.url()));
                final MenuItem edit = new MenuItem(Messages.EditRemote);
                edit.setOnAction(event -> actions.editRemote(remote.name(), remote.url()));
                final MenuItem settings = new MenuItem(Messages.RemoteSettings);
                settings.setOnAction(event -> actions.remoteSettings(remote.name()));
                final MenuItem remove = new MenuItem(Messages.RemoveRemote);
                remove.setOnAction(event -> actions.removeRemote(remote.name()));
                setContextMenu(new ContextMenu(edit, settings, remove));
            }
            else if (value instanceof RemoteBranch branch)
            {
                setText(branch.name());
                setContextMenu(createMenu(branch));
            }
            else if (value instanceof TagInfo tag)
            {
                setText(tag.name());
                setContextMenu(createMenu(tag));
            }
            else if (value instanceof StashInfo stash)
            {
                setText(stash.message());
                setContextMenu(createMenu(stash));
            }
            else if (value instanceof SubmoduleInfo submodule)
            {
                setText(switch (submodule.state())
                {
                    case UNINITIALIZED -> submodule.path() + "  (" + Messages.SubmoduleUninitialized + ")";
                    case DIFFERENT     -> submodule.path() + "  (" + Messages.SubmoduleDifferent + ")";
                    case MISSING       -> submodule.path() + "  (" + Messages.SubmoduleMissing + ")";
                    default            -> submodule.path();
                });
                setTooltip(new Tooltip(submodule.url() + "\n" + Messages.SubmoduleRecorded + ": " +
                                       (submodule.recorded() == null ? "-" : submodule.recorded().substring(0, 7)) +
                                       "\n" + Messages.SubmoduleActual + ": " +
                                       (submodule.actual() == null ? "-" : submodule.actual().substring(0, 7))));
                final MenuItem update = new MenuItem(Messages.UpdateSubmodule);
                update.setOnAction(event -> actions.updateSubmodules(List.of(submodule.path())));
                final MenuItem open = new MenuItem(Messages.OpenSubmodule);
                open.setDisable(submodule.state() == SubmoduleState.UNINITIALIZED);
                open.setOnAction(event -> actions.openSubmodule(submodule));
                setContextMenu(new ContextMenu(update, open));
            }
        }
    }

    private ContextMenu createMenu(final TagInfo tag)
    {
        final MenuItem create = new MenuItem(Messages.CreateBranchHere);
        create.setOnAction(event -> actions.createBranch(tag.commit()));
        final MenuItem push = new MenuItem(Messages.PushTag);
        push.setOnAction(event -> actions.pushTag(tag));
        final MenuItem delete = new MenuItem(Messages.DeleteTag);
        delete.setOnAction(event -> actions.deleteTag(tag));
        final MenuItem delete_remote = new MenuItem(Messages.DeleteRemoteTag);
        delete_remote.setOnAction(event -> actions.deleteRemoteTag(tag));
        final MenuItem compare = new MenuItem(Messages.CompareWithCurrent);
        compare.setOnAction(event -> actions.compareWithCurrent(tag.name()));
        return new ContextMenu(create, compare, push, delete, delete_remote);
    }

    private ContextMenu createMenu(final BranchInfo branch)
    {
        final MenuItem checkout = new MenuItem(Messages.Checkout);
        checkout.setDisable(branch.current());
        checkout.setOnAction(event -> actions.checkout(branch));
        final MenuItem create = new MenuItem(Messages.CreateBranchHere);
        create.setOnAction(event -> actions.createBranch(branch.name()));
        final MenuItem merge = new MenuItem(Messages.MergeIntoCurrent);
        merge.setDisable(branch.current());
        merge.setOnAction(event -> actions.merge(branch.name()));
        final MenuItem rebase = new MenuItem(Messages.RebaseOnto);
        rebase.setDisable(branch.current());
        rebase.setOnAction(event -> actions.rebaseOnto(branch.name()));
        final MenuItem rename = new MenuItem(Messages.RenameBranch);
        rename.setOnAction(event -> actions.renameBranch(branch));
        final MenuItem delete = new MenuItem(Messages.DeleteBranch);
        delete.setDisable(branch.current());
        delete.setOnAction(event -> actions.deleteBranch(branch));
        final MenuItem compare = new MenuItem(Messages.CompareWithCurrent);
        compare.setDisable(branch.current());
        compare.setOnAction(event -> actions.compareWithCurrent(branch.name()));
        return new ContextMenu(checkout, create, merge, rebase, compare, rename, delete);
    }

    private ContextMenu createMenu(final RemoteBranch branch)
    {
        final MenuItem checkout = new MenuItem(Messages.CheckoutRemote);
        checkout.setOnAction(event -> actions.checkoutRemote(branch));
        final MenuItem create = new MenuItem(Messages.CreateBranchHere);
        create.setOnAction(event -> actions.createBranch(branch.fullName()));
        final MenuItem merge = new MenuItem(Messages.MergeIntoCurrent);
        merge.setOnAction(event -> actions.merge(branch.fullName()));
        final MenuItem rebase = new MenuItem(Messages.RebaseOnto);
        rebase.setOnAction(event -> actions.rebaseOnto(branch.fullName()));
        final MenuItem compare = new MenuItem(Messages.CompareWithCurrent);
        compare.setOnAction(event -> actions.compareWithCurrent(branch.fullName()));
        return new ContextMenu(checkout, create, merge, rebase, compare);
    }

    private ContextMenu createMenu(final StashInfo stash)
    {
        final MenuItem apply = new MenuItem(Messages.ApplyStash);
        apply.setOnAction(event -> actions.applyStash(stash, false));
        final MenuItem pop = new MenuItem(Messages.PopStash);
        pop.setOnAction(event -> actions.applyStash(stash, true));
        final MenuItem drop = new MenuItem(Messages.DropStash);
        drop.setOnAction(event -> actions.dropStash(stash));
        return new ContextMenu(apply, pop, drop);
    }
}
