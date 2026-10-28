package org.phoebus.applications.git;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.jgit.api.AddCommand;
import org.eclipse.jgit.api.CherryPickResult;
import org.eclipse.jgit.api.CreateBranchCommand.SetupUpstreamMode;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ListBranchCommand.ListMode;
import org.eclipse.jgit.api.MergeCommand.FastForwardMode;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.api.RevertCommand;
import org.eclipse.jgit.api.RmCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.TransportCommand;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.dircache.DirCacheIterator;
import org.eclipse.jgit.lib.BranchTrackingStatus;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryState;
import org.eclipse.jgit.revplot.PlotCommit;
import org.eclipse.jgit.revplot.PlotCommitList;
import org.eclipse.jgit.revplot.PlotLane;
import org.eclipse.jgit.revplot.PlotWalk;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.SshTransport;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.treewalk.filter.PathFilter;

/** Thin wrapper around JGit for the operations used by the UI
 *
 *  <p>All calls block, so invoke them from a background job.
 */
public class GitRepository implements AutoCloseable
{
    /** Kind of change to a file */
    public enum ChangeType
    {
        ADDED("Added"),
        MODIFIED("Modified"),
        DELETED("Deleted"),
        UNTRACKED("Untracked"),
        CONFLICT("Conflict");

        private final String label;

        private ChangeType(final String label)
        {
            this.label = label;
        }

        @Override
        public String toString()
        {
            return label;
        }
    }

    /** One changed file
     *  @param type Kind of change
     *  @param path Path relative to the work tree
     */
    public record StatusEntry(ChangeType type, String path) {}

    /** Changed files
     *  @param staged Changes in the index, compared to HEAD
     *  @param unstaged Changes in the work tree, compared to the index, including conflicts and untracked files
     *  @param merging Is a merge, cherry-pick or revert in progress, so that a commit is possible without staged changes?
     *  @param merge_message Prepared commit message of the merge, cherry-pick or revert, or <code>null</code>
     */
    public record FileStatus(List<StatusEntry> staged, List<StatusEntry> unstaged,
                             boolean merging, String merge_message) {}

    /** One commit of the history
     *  @param id Full commit id
     *  @param plot Lane information and refs for drawing the commit graph
     */
    public record CommitInfo(String id, String message, String author, Instant date, PlotCommit<PlotLane> plot) {}

    /** Local branch
     *  @param name Short name, e.g. "master"
     *  @param commit Commit id
     *  @param current Is this the checked out branch?
     *  @param upstream Remote tracking branch, e.g. "origin/master", or <code>null</code>
     *  @param ahead Commits not yet pushed to upstream
     *  @param behind Commits not yet pulled from upstream
     */
    public record BranchInfo(String name, String commit, boolean current, String upstream, int ahead, int behind) {}

    /** Branch of a remote
     *  @param remote Remote name, e.g. "origin"
     *  @param name Branch name on the remote, e.g. "master"
     *  @param commit Commit id
     */
    public record RemoteBranch(String remote, String name, String commit)
    {
        /** @return Name of the remote tracking branch, e.g. "origin/master" */
        public String fullName()
        {
            return remote + "/" + name;
        }
    }

    /** Tag
     *  @param name Tag name
     *  @param commit Id of the tagged commit
     */
    public record TagInfo(String name, String commit) {}

    /** Stash entry
     *  @param index 0 for the most recent stash
     *  @param commit Id of the stash commit
     */
    public record StashInfo(int index, String commit, String message) {}

    /** Snapshot of everything shown in the UI
     *  @param branch Current branch, or commit id for detached HEAD
     *  @param remotes Branches of each remote, by remote name
     */
    public record State(String branch, FileStatus status, List<CommitInfo> log,
                        List<BranchInfo> branches, Map<String, List<RemoteBranch>> remotes,
                        List<TagInfo> tags, List<StashInfo> stashes) {}

    private final Git git;

    /** @param directory Directory inside a work tree; parent directories are searched for '.git'
     *  @throws Exception if no repository is found
     */
    public GitRepository(final File directory) throws Exception
    {
        final FileRepositoryBuilder builder = new FileRepositoryBuilder().findGitDir(directory);
        if (builder.getGitDir() == null)
            throw new Exception("No git repository found for " + directory);
        git = new Git(builder.build());
    }

    /** @return Top directory of the work tree */
    public File getWorkTree()
    {
        return git.getRepository().getWorkTree();
    }

    /** @return Current branch name */
    public String getBranch() throws Exception
    {
        return git.getRepository().getBranch();
    }

    /** @param log_count Maximum number of commits to read
     *  @param all_branches Show history of all branches, or only the current one?
     *  @return Current state of the repository
     */
    public State getState(final int log_count, final boolean all_branches) throws Exception
    {
        return new State(getBranch(), getStatus(), getLog(log_count, all_branches),
                         getBranches(), getRemoteBranches(), getTags(), getStashes());
    }

    /** @return Local branches */
    public List<BranchInfo> getBranches() throws Exception
    {
        final Repository repository = git.getRepository();
        final String current = repository.getFullBranch();
        final List<BranchInfo> result = new ArrayList<>();
        for (Ref ref : git.branchList().call())
        {
            final String name = Repository.shortenRefName(ref.getName());
            final BranchTrackingStatus tracking = BranchTrackingStatus.of(repository, name);
            result.add(new BranchInfo(name, ref.getObjectId().name(), ref.getName().equals(current),
                                      tracking == null ? null : Repository.shortenRefName(tracking.getRemoteTrackingBranch()),
                                      tracking == null ? 0 : tracking.getAheadCount(),
                                      tracking == null ? 0 : tracking.getBehindCount()));
        }
        return result;
    }

    /** @return Branches of each remote, including remotes without branches */
    public Map<String, List<RemoteBranch>> getRemoteBranches() throws Exception
    {
        final Map<String, List<RemoteBranch>> result = new LinkedHashMap<>();
        for (String remote : git.getRepository().getRemoteNames().stream().sorted().toList())
            result.put(remote, new ArrayList<>());
        for (Ref ref : git.branchList().setListMode(ListMode.REMOTE).call())
        {
            // Skip "origin/HEAD"
            if (ref.isSymbolic())
                continue;
            final String name = ref.getName().substring(Constants.R_REMOTES.length());
            for (String remote : result.keySet())
                if (name.startsWith(remote + "/"))
                {
                    result.get(remote).add(new RemoteBranch(remote, name.substring(remote.length() + 1), ref.getObjectId().name()));
                    break;
                }
        }
        return result;
    }

    /** @return Tags */
    public List<TagInfo> getTags() throws Exception
    {
        final Repository repository = git.getRepository();
        final List<TagInfo> result = new ArrayList<>();
        for (Ref ref : git.tagList().call())
        {
            // Annotated tag: Use the tagged commit, not the tag object
            final Ref peeled = repository.getRefDatabase().peel(ref);
            final ObjectId commit = peeled.getPeeledObjectId() != null ? peeled.getPeeledObjectId() : ref.getObjectId();
            result.add(new TagInfo(Repository.shortenRefName(ref.getName()), commit.name()));
        }
        return result;
    }

    /** @return Stash entries, most recent first */
    public List<StashInfo> getStashes() throws Exception
    {
        final List<StashInfo> result = new ArrayList<>();
        int index = 0;
        for (RevCommit commit : git.stashList().call())
            result.add(new StashInfo(index++, commit.name(), commit.getShortMessage()));
        return result;
    }

    /** @return Staged and unstaged changes */
    public FileStatus getStatus() throws Exception
    {
        final Status status = git.status().call();
        // Conflicts are only listed as such, as unstaged
        final Set<String> conflicts = status.getConflicting();
        final List<StatusEntry> staged = new ArrayList<>();
        add(staged, ChangeType.ADDED, status.getAdded(), conflicts);
        add(staged, ChangeType.MODIFIED, status.getChanged(), conflicts);
        add(staged, ChangeType.DELETED, status.getRemoved(), conflicts);
        final List<StatusEntry> unstaged = new ArrayList<>();
        add(unstaged, ChangeType.CONFLICT, conflicts, Set.of());
        add(unstaged, ChangeType.MODIFIED, status.getModified(), conflicts);
        add(unstaged, ChangeType.DELETED, status.getMissing(), conflicts);
        add(unstaged, ChangeType.UNTRACKED, status.getUntracked(), conflicts);
        staged.sort(Comparator.comparing(StatusEntry::path));
        unstaged.sort(Comparator.comparing(StatusEntry::path));
        final Repository repository = git.getRepository();
        final boolean merging = isMerging(repository.getRepositoryState());
        return new FileStatus(staged, unstaged, merging, merging ? getMergeMessage(repository) : null);
    }

    /** @return Prepared merge message without '# Conflicts' comments, or <code>null</code> */
    private static String getMergeMessage(final Repository repository) throws Exception
    {
        final String message = repository.readMergeCommitMsg();
        if (message == null)
            return null;
        return message.lines()
                      .filter(line -> ! line.startsWith("#"))
                      .collect(Collectors.joining("\n"))
                      .trim();
    }

    /** @return Is a merge, cherry-pick or revert in progress? */
    private static boolean isMerging(final RepositoryState state)
    {
        return switch (state)
        {
            case MERGING, MERGING_RESOLVED,
                 CHERRY_PICKING, CHERRY_PICKING_RESOLVED,
                 REVERTING, REVERTING_RESOLVED -> true;
            default -> false;
        };
    }

    private static void add(final List<StatusEntry> result, final ChangeType type,
                            final Set<String> paths, final Set<String> skip)
    {
        for (String path : paths)
            if (! skip.contains(path))
                result.add(new StatusEntry(type, path));
    }

    /** @param count Maximum number of commits
     *  @param all_branches Include all local and remote branches and tags, or only HEAD?
     *  @return Most recent commits with commit graph information, newest first
     */
    public List<CommitInfo> getLog(final int count, final boolean all_branches) throws Exception
    {
        final Repository repository = git.getRepository();
        final List<CommitInfo> result = new ArrayList<>();
        try (PlotWalk walk = new PlotWalk(repository))
        {
            // Children before parents, otherwise newest first
            walk.sort(RevSort.TOPO, true);
            walk.sort(RevSort.COMMIT_TIME_DESC, true);

            boolean have_start = markStart(walk, repository.resolve(Constants.HEAD));
            if (all_branches)
                for (String prefix : List.of(Constants.R_HEADS, Constants.R_REMOTES, Constants.R_TAGS))
                    for (Ref ref : repository.getRefDatabase().getRefsByPrefix(prefix))
                        have_start |= markStart(walk, ref.getObjectId());
            // Empty repository has nothing to walk
            if (! have_start)
                return result;

            final PlotCommitList<PlotLane> list = new PlotCommitList<>();
            list.source(walk);
            list.fillTo(count);
            for (PlotCommit<PlotLane> commit : list)
                result.add(new CommitInfo(commit.name(),
                                          commit.getShortMessage(),
                                          commit.getAuthorIdent().getName(),
                                          Instant.ofEpochSecond(commit.getCommitTime()),
                                          commit));
        }
        return result;
    }

    /** @return <code>true</code> if id was a commit, or a tag of a commit, and added as start point */
    private static boolean markStart(final PlotWalk walk, final ObjectId id) throws Exception
    {
        if (id == null)
            return false;
        final RevObject object = walk.peel(walk.parseAny(id));
        if (! (object instanceof RevCommit commit))
            return false;
        walk.markStart(commit);
        return true;
    }

    /** @param path File path relative to the work tree
     *  @return Unified diff of the staged change, from HEAD to the index
     */
    public String getStagedDiff(final String path) throws Exception
    {
        final Repository repository = git.getRepository();
        try (ObjectReader reader = repository.newObjectReader())
        {
            return formatDiff(path, getHeadTree(reader), new DirCacheIterator(repository.readDirCache()));
        }
    }

    /** @param path File path relative to the work tree
     *  @return Unified diff of the unstaged change, from the index to the work tree.
     *          For a conflict, from HEAD to the work tree file with conflict markers
     */
    public String getUnstagedDiff(final String path) throws Exception
    {
        final Repository repository = git.getRepository();
        final DirCache index = repository.readDirCache();
        final int i = index.findEntry(path);
        if (i >= 0  &&  index.getEntry(i).getStage() != DirCacheEntry.STAGE_0)
        {
            // Index has 'base', 'ours', 'theirs' entries, compare with HEAD instead
            try (ObjectReader reader = repository.newObjectReader())
            {
                return formatDiff(path, getHeadTree(reader), new FileTreeIterator(repository));
            }
        }
        return formatDiff(path, new DirCacheIterator(index), new FileTreeIterator(repository));
    }

    /** @return Tree iterator for HEAD, empty if there is no commit, yet */
    private AbstractTreeIterator getHeadTree(final ObjectReader reader) throws Exception
    {
        final ObjectId head = git.getRepository().resolve("HEAD^{tree}");
        if (head == null)
            return new EmptyTreeIterator();
        final CanonicalTreeParser parser = new CanonicalTreeParser();
        parser.reset(reader, head);
        return parser;
    }

    private String formatDiff(final String path, final AbstractTreeIterator old_tree, final AbstractTreeIterator new_tree) throws Exception
    {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DiffFormatter formatter = new DiffFormatter(out))
        {
            formatter.setRepository(git.getRepository());
            formatter.setPathFilter(PathFilter.create(path));
            formatter.format(old_tree, new_tree);
            formatter.flush();
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    /** @param id Full commit id
     *  @return Commit header and unified diff against its first parent
     */
    public String getCommitDiff(final String id) throws Exception
    {
        final Repository repository = git.getRepository();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (RevWalk walk = new RevWalk(repository);
             DiffFormatter formatter = new DiffFormatter(out))
        {
            final RevCommit commit = walk.parseCommit(ObjectId.fromString(id));
            final PersonIdent author = commit.getAuthorIdent();
            out.write(("commit " + commit.name() + "\n" +
                       "Author: " + author.getName() + " <" + author.getEmailAddress() + ">\n" +
                       "Date:   " + Instant.ofEpochSecond(commit.getCommitTime()) + "\n\n" +
                       commit.getFullMessage() + "\n").getBytes(StandardCharsets.UTF_8));
            // First commit has no parent: Compare with empty tree
            final RevTree parent = commit.getParentCount() > 0
                                 ? walk.parseCommit(commit.getParent(0)).getTree()
                                 : null;
            formatter.setRepository(repository);
            formatter.setDetectRenames(true);
            formatter.format(parent, commit.getTree());
            formatter.flush();
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    /** Stage files, like 'git add' or 'git rm --cached' for deleted files
     *  @param paths File paths relative to the work tree
     */
    public void stage(final Collection<String> paths) throws Exception
    {
        final File work_tree = getWorkTree();
        AddCommand add = null;
        RmCommand rm = null;
        for (String path : paths)
            if (new File(work_tree, path).exists())
                add = (add == null ? git.add() : add).addFilepattern(path);
            else
                rm = (rm == null ? git.rm().setCached(true) : rm).addFilepattern(path);
        if (add != null)
            add.call();
        if (rm != null)
            rm.call();
    }

    /** Unstage files, like 'git reset -- paths'
     *  @param paths File paths relative to the work tree
     */
    public void unstage(final Collection<String> paths) throws Exception
    {
        if (paths.isEmpty())
            return;
        if (git.getRepository().resolve(Constants.HEAD) == null)
        {
            // No commit yet, nothing to reset to: Remove from index
            final RmCommand rm = git.rm().setCached(true);
            for (String path : paths)
                rm.addFilepattern(path);
            rm.call();
        }
        else
        {
            final ResetCommand reset = git.reset();
            for (String path : paths)
                reset.addPath(path);
            reset.call();
        }
    }

    /** Discard unstaged changes: Restore tracked files from the index, delete untracked files
     *  @param paths File paths relative to the work tree
     */
    public void discard(final Collection<String> paths) throws Exception
    {
        final DirCache index = git.getRepository().readDirCache();
        final List<String> tracked = new ArrayList<>();
        for (String path : paths)
            if (index.findEntry(path) >= 0)
                tracked.add(path);
            else
                Files.deleteIfExists(new File(getWorkTree(), path).toPath());
        if (! tracked.isEmpty())
            git.checkout().addPaths(tracked).call();
    }

    /** @return Full message of the HEAD commit, <code>null</code> if there is none */
    public String getHeadMessage() throws Exception
    {
        final ObjectId head = git.getRepository().resolve(Constants.HEAD);
        if (head == null)
            return null;
        try (RevWalk walk = new RevWalk(git.getRepository()))
        {
            return walk.parseCommit(head).getFullMessage();
        }
    }

    /** Commit the staged changes
     *  @param message Commit message
     *  @param amend Replace the last commit?
     */
    public void commit(final String message, final boolean amend) throws Exception
    {
        final FileStatus status = getStatus();
        if (! amend  &&  status.staged().isEmpty()  &&  ! status.merging())
            throw new Exception("Nothing staged for commit");
        git.commit().setMessage(message).setAmend(amend).call();
    }

    /** Merge a branch into the current branch
     *  @param name Branch to merge, e.g. "feature" or "origin/master"
     *  @param no_ff Always create a merge commit, even if fast-forward is possible?
     *  @return <code>true</code> if the merge stopped with conflicts that need to be resolved
     *  @throws Exception if the merge failed, for example because local changes would be overwritten
     */
    public boolean merge(final String name, final boolean no_ff) throws Exception
    {
        final Ref ref = git.getRepository().findRef(name);
        if (ref == null)
            throw new Exception("Unknown branch " + name);
        final MergeResult result = git.merge()
                                      .include(ref)
                                      .setFastForward(no_ff ? FastForwardMode.NO_FF : FastForwardMode.FF)
                                      .call();
        if (result.getMergeStatus() == MergeResult.MergeStatus.CONFLICTING)
            return true;
        if (! result.getMergeStatus().isSuccessful())
            throw new Exception("Merge " + result.getMergeStatus() +
                                (result.getFailingPaths() != null ? "\n" + result.getFailingPaths() : ""));
        return false;
    }

    /** Abort a merge, cherry-pick or revert: Reset index and work tree to HEAD
     *
     *  <p>Like 'git merge --abort', discards all uncommitted changes.
     */
    public void abortMerge() throws Exception
    {
        git.reset().setMode(ResetType.HARD).call();
    }

    /** Apply the changes of a commit to the current branch
     *  @param id Commit id
     *  @return <code>true</code> if it stopped with conflicts that need to be resolved
     */
    public boolean cherryPick(final String id) throws Exception
    {
        final CherryPickResult result = git.cherryPick().include(ObjectId.fromString(id)).call();
        if (result.getStatus() == CherryPickResult.CherryPickStatus.CONFLICTING)
            return true;
        if (result.getStatus() != CherryPickResult.CherryPickStatus.OK)
            throw new Exception("Cherry-pick " + result.getStatus() +
                                (result.getFailingPaths() != null ? "\n" + result.getFailingPaths() : ""));
        return false;
    }

    /** Create a commit that reverts the changes of a commit
     *  @param id Commit id
     *  @return <code>true</code> if it stopped with conflicts that need to be resolved
     */
    public boolean revert(final String id) throws Exception
    {
        final RevertCommand command = git.revert().include(ObjectId.fromString(id));
        if (command.call() != null)
            return false;
        if (command.getUnmergedPaths() != null  &&  ! command.getUnmergedPaths().isEmpty())
            return true;
        throw new Exception("Revert failed" +
                            (command.getFailingResult() != null ? "\n" + command.getFailingResult().getFailingPaths() : ""));
    }

    /** @param name Tag name
     *  @param message Message for an annotated tag, empty for a lightweight tag
     *  @param commit Commit to tag, <code>null</code> for HEAD
     */
    public void createTag(final String name, final String message, final String commit) throws Exception
    {
        try (RevWalk walk = new RevWalk(git.getRepository()))
        {
            final ObjectId id = commit == null ? git.getRepository().resolve(Constants.HEAD) : ObjectId.fromString(commit);
            if (id == null)
                throw new Exception("Nothing to tag, no commit");
            final boolean annotated = message != null  &&  ! message.isBlank();
            final var command = git.tag()
                                   .setName(name)
                                   .setObjectId(walk.parseCommit(id))
                                   .setAnnotated(annotated);
            if (annotated)
                command.setMessage(message);
            command.call();
        }
    }

    /** @param name Local tag to delete */
    public void deleteTag(final String name) throws Exception
    {
        git.tagDelete().setTags(name).call();
    }

    /** @param branch Local branch to check out */
    public void checkout(final String branch) throws Exception
    {
        git.checkout().setName(branch).call();
    }

    /** Create local branch that tracks a remote branch, and check it out
     *  @param remote_branch Remote branch
     *  @param name Name of the new local branch
     */
    public void checkoutRemote(final RemoteBranch remote_branch, final String name) throws Exception
    {
        git.checkout()
           .setCreateBranch(true)
           .setName(name)
           .setStartPoint(remote_branch.fullName())
           .setUpstreamMode(SetupUpstreamMode.TRACK)
           .call();
    }

    /** @param name Name of new branch
     *  @param start_point Commit id, branch or tag where the branch starts, <code>null</code> for HEAD
     *  @param checkout Check out the new branch?
     */
    public void createBranch(final String name, final String start_point, final boolean checkout) throws Exception
    {
        if (checkout)
        {
            final var command = git.checkout().setCreateBranch(true).setName(name);
            if (start_point != null)
                command.setStartPoint(start_point);
            command.call();
        }
        else
        {
            final var command = git.branchCreate().setName(name);
            if (start_point != null)
                command.setStartPoint(start_point);
            command.call();
        }
    }

    /** @param name Local branch to delete
     *  @param force Delete even if not merged?
     *  @throws org.eclipse.jgit.api.errors.NotMergedException if not merged and not forced
     */
    public void deleteBranch(final String name, final boolean force) throws Exception
    {
        git.branchDelete().setBranchNames(Constants.R_HEADS + name).setForce(force).call();
    }

    /** @param id Stash commit id
     *  @return Diff of the stashed changes, including untracked files
     */
    public String getStashDiff(final String id) throws Exception
    {
        final StringBuilder result = new StringBuilder(getCommitDiff(id));
        // Untracked files are stored in a third parent commit
        final Repository repository = git.getRepository();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (RevWalk walk = new RevWalk(repository);
             DiffFormatter formatter = new DiffFormatter(out))
        {
            final RevCommit stash = walk.parseCommit(ObjectId.fromString(id));
            if (stash.getParentCount() > 2)
            {
                formatter.setRepository(repository);
                formatter.format(null, walk.parseCommit(stash.getParent(2)).getTree());
                formatter.flush();
            }
        }
        return result.append(out.toString(StandardCharsets.UTF_8)).toString();
    }

    /** Stash local changes
     *  @param message Stash message, <code>null</code> for default
     *  @param include_untracked Also stash untracked files?
     */
    public void stash(final String message, final boolean include_untracked) throws Exception
    {
        final var command = git.stashCreate().setIncludeUntracked(include_untracked);
        if (message != null  &&  ! message.isBlank())
            command.setWorkingDirectoryMessage(message);
        if (command.call() == null)
            throw new Exception("No local changes to stash");
    }

    /** @param index Stash index
     *  @param drop Drop the stash after applying it ('pop')?
     */
    public void applyStash(final int index, final boolean drop) throws Exception
    {
        git.stashApply().setStashRef(Constants.STASH + "@{" + index + "}").setRestoreUntracked(true).call();
        if (drop)
            dropStash(index);
    }

    /** @param index Stash index to delete */
    public void dropStash(final int index) throws Exception
    {
        git.stashDrop().setStashRef(index).call();
    }

    /** Configure authentication and timeout of a remote operation
     *  @param command Pull, push, ...
     *  @param credentials HTTPS user/password, SSH passphrase and host key prompts
     */
    private static void configure(final TransportCommand<?, ?> command, final CredentialsProvider credentials)
    {
        command.setCredentialsProvider(credentials);
        command.setTimeout(GitApp.remote_timeout);
        // Use Apache MINA sshd, not whatever SSH implementation is found first on the class path
        command.setTransportConfigCallback(transport ->
        {
            if (transport instanceof SshTransport ssh)
                ssh.setSshSessionFactory(GitSsh.getFactory());
        });
    }

    /** Fetch from all remotes, removing branches deleted on the remote
     *  @param credentials Credentials provider
     */
    public void fetch(final CredentialsProvider credentials) throws Exception
    {
        for (String remote : git.getRepository().getRemoteNames())
        {
            final var command = git.fetch().setRemote(remote).setRemoveDeletedRefs(true);
            configure(command, credentials);
            command.call();
        }
    }

    /** Pull from the configured upstream
     *  @param credentials Credentials provider
     */
    public void pull(final CredentialsProvider credentials) throws Exception
    {
        final var command = git.pull();
        configure(command, credentials);
        final PullResult result = command.call();
        if (! result.isSuccessful())
            throw new Exception("Pull did not complete, check for merge conflicts\n" + result);
    }

    /** Push the current branch to the configured remote
     *  @param credentials Credentials provider
     */
    public void push(final CredentialsProvider credentials) throws Exception
    {
        final var command = git.push();
        configure(command, credentials);
        checkPushResults(command.call());
    }

    /** Push a tag
     *  @param name Tag name
     *  @param credentials Credentials provider
     */
    public void pushTag(final String name, final CredentialsProvider credentials) throws Exception
    {
        final Set<String> remotes = git.getRepository().getRemoteNames();
        if (remotes.isEmpty())
            throw new Exception("No remote configured");
        final String remote = remotes.contains(Constants.DEFAULT_REMOTE_NAME)
                            ? Constants.DEFAULT_REMOTE_NAME
                            : remotes.iterator().next();
        final String ref = Constants.R_TAGS + name;
        final var command = git.push().setRemote(remote).setRefSpecs(new RefSpec(ref + ":" + ref));
        configure(command, credentials);
        checkPushResults(command.call());
    }

    /** Push does not throw when the remote rejects an update, check each one */
    private static void checkPushResults(final Iterable<PushResult> results) throws Exception
    {
        final StringBuilder errors = new StringBuilder();
        for (PushResult result : results)
            for (RemoteRefUpdate update : result.getRemoteUpdates())
                if (update.getStatus() != RemoteRefUpdate.Status.OK  &&
                    update.getStatus() != RemoteRefUpdate.Status.UP_TO_DATE)
                {
                    errors.append(update.getRemoteName()).append(": ").append(update.getStatus());
                    if (update.getMessage() != null)
                        errors.append(" (").append(update.getMessage()).append(")");
                    errors.append("\n");
                }
        if (errors.length() > 0)
            throw new Exception("Push rejected\n" + errors);
    }

    @Override
    public void close()
    {
        git.close();
    }
}
