package org.phoebus.applications.git;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.eclipse.jgit.api.AddCommand;
import org.eclipse.jgit.api.CheckoutCommand.Stage;
import org.eclipse.jgit.api.ApplyResult;
import org.eclipse.jgit.api.CherryPickResult;
import org.eclipse.jgit.api.CreateBranchCommand.SetupUpstreamMode;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ListBranchCommand.ListMode;
import org.eclipse.jgit.api.MergeCommand.FastForwardMode;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.api.RebaseCommand;
import org.eclipse.jgit.api.RebaseResult;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.api.RevertCommand;
import org.eclipse.jgit.api.RmCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.submodule.SubmoduleStatus;
import org.eclipse.jgit.submodule.SubmoduleStatusType;
import org.eclipse.jgit.submodule.SubmoduleWalk;
import org.eclipse.jgit.api.TransportCommand;
import org.eclipse.jgit.blame.BlameResult;
import org.eclipse.jgit.diff.ContentSource;
import org.eclipse.jgit.diff.DiffConfig;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.diff.RenameDetector;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEditor;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.dircache.DirCacheIterator;
import org.eclipse.jgit.lib.AbbreviatedObjectId;
import org.eclipse.jgit.lib.BranchTrackingStatus;
import org.eclipse.jgit.lib.ConfigConstants;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.RebaseTodoLine;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.ReflogEntry;
import org.eclipse.jgit.lib.ReflogReader;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryState;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.patch.FileHeader;
import org.eclipse.jgit.patch.HunkHeader;
import org.eclipse.jgit.patch.Patch;
import org.eclipse.jgit.revplot.PlotCommit;
import org.eclipse.jgit.revplot.PlotCommitList;
import org.eclipse.jgit.revplot.PlotLane;
import org.eclipse.jgit.revplot.PlotWalk;
import org.eclipse.jgit.revwalk.FollowFilter;
import org.eclipse.jgit.revwalk.RenameCallback;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefLeaseSpec;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.TagOpt;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.SshTransport;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.WorkingTreeIterator;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;
import org.eclipse.jgit.util.io.DisabledOutputStream;

/** Thin wrapper around JGit for the operations used by the UI
 *
 *  <p>All calls block, so invoke them from a background job.
 *  Calls are serialized, so that for example a refresh that updates
 *  the index does not collide with a commit and fail on the index lock.
 */
public class GitRepository implements AutoCloseable
{
    /** Kind of change to a file */
    public enum ChangeType
    {
        ADDED("Added"),
        MODIFIED("Modified"),
        DELETED("Deleted"),
        RENAMED("Renamed"),
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

    /** File changed by a commit
     *  @param type Kind of change
     *  @param path Path after the change, or the old path for a deleted file
     *  @param old_path Path before a rename, otherwise <code>null</code>
     */
    public record CommitFile(ChangeType type, String path, String old_path) {}

    /** Commit in the history of a file
     *  @param commit Commit
     *  @param path Path of the file in this commit, may differ from the current path after a rename
     */
    public record FileVersion(CommitInfo commit, String path) {}

    /** One line of 'blame'
     *  @param number Line number, starting at 1
     *  @param commit Id of the commit that last changed the line, <code>null</code> if not committed
     *  @param author Author of that commit
     *  @param date Date of that commit
     *  @param text Line text
     */
    public record BlameLine(int number, String commit, String author, Instant date, String text) {}

    /** Changed files
     *  @param staged Changes in the index, compared to HEAD
     *  @param unstaged Changes in the work tree, compared to the index, including conflicts and untracked files
     *  @param merging Is a merge, cherry-pick or revert in progress, so that a commit is possible without staged changes?
     *  @param merge_message Prepared commit message of the merge, cherry-pick or revert, or <code>null</code>
     *  @param rebasing Is a rebase in progress, to be continued, skipped or aborted?
     */
    public record FileStatus(List<StatusEntry> staged, List<StatusEntry> unstaged,
                             boolean merging, String merge_message, boolean rebasing) {}

    /** Push options
     *  @param remote Remote, <code>null</code> for the branch's upstream remote or the default remote
     *  @param branch Local branch, <code>null</code> for the current branch
     *  @param tags Also push all tags?
     *  @param force Overwrite the remote branch, but only if it's still where we last fetched it
     */
    public record PushOptions(String remote, String branch, boolean tags, boolean force)
    {
        /** Push current branch to its upstream */
        public static final PushOptions DEFAULT = new PushOptions(null, null, false, false);
    }

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
     *  @param remote_urls URL of each remote, by remote name
     */
    public record State(String branch, FileStatus status, List<CommitInfo> log,
                        List<BranchInfo> branches, Map<String, List<RemoteBranch>> remotes,
                        Map<String, String> remote_urls,
                        List<TagInfo> tags, List<StashInfo> stashes, List<SubmoduleInfo> submodules,
                        List<ReflogInfo> reflog) {}

    private final Git git;

    /** Ignore white space changes when showing diffs? */
    private volatile boolean ignore_whitespace = false;

    /** Clone a remote repository
     *  @param url Remote URL
     *  @param directory New directory for the clone
     *  @param credentials Credentials provider
     *  @param monitor Progress monitor
     */
    public static void cloneRepository(final String url, final File directory,
                                       final CredentialsProvider credentials,
                                       final ProgressMonitor monitor) throws Exception
    {
        cloneRepository(url, directory, false, credentials, monitor);
    }

    /** Clone a repository
     *  @param url Remote URL
     *  @param directory Directory for the new work tree, must not exist or be empty
     *  @param submodules Also clone submodules?
     *  @param credentials Credentials provider
     *  @param monitor Progress monitor
     */
    public static void cloneRepository(final String url, final File directory, final boolean submodules,
                                       final CredentialsProvider credentials,
                                       final ProgressMonitor monitor) throws Exception
    {
        if (directory.exists()  &&  directory.list().length > 0)
            throw new Exception("Directory is not empty: " + directory);
        final var command = Git.cloneRepository()
                               .setURI(url)
                               .setDirectory(directory)
                               .setCloneSubmodules(submodules)
                               .setProgressMonitor(monitor);
        configure(command, credentials);
        command.call().close();
    }

    /** Create a new, empty repository
     *  @param directory Directory for the new work tree
     */
    public static void init(final File directory) throws Exception
    {
        if (new File(directory, Constants.DOT_GIT).exists())
            throw new Exception("Already a git repository: " + directory);
        Git.init().setDirectory(directory).call().close();
    }

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

    /** Directories to watch for changes
     *
     *  <p>Directories of the work tree that are not ignored,
     *  plus the git directory (HEAD, index) and its 'refs' (branches, tags).
     *
     *  @param max Maximum number of work tree directories
     *  @return Directories
     */
    public synchronized List<File> getWatchDirectories(final int max) throws Exception
    {
        final Repository repository = git.getRepository();
        final File top = repository.getWorkTree();
        final List<File> dirs = new ArrayList<>();
        dirs.add(top);
        try (TreeWalk walk = new TreeWalk(repository))
        {
            // Iterator skips '.git' and can tell which entries are ignored
            walk.addTree(new FileTreeIterator(repository));
            walk.setRecursive(false);
            while (walk.next()  &&  dirs.size() < max)
                if (walk.isSubtree()  &&  ! walk.getTree(0, WorkingTreeIterator.class).isEntryIgnored())
                {
                    dirs.add(new File(top, walk.getPathString()));
                    walk.enterSubtree();
                }
        }
        final File git_dir = repository.getDirectory();
        dirs.add(git_dir);
        final File refs = new File(git_dir, "refs");
        if (refs.isDirectory())
            try (var paths = Files.walk(refs.toPath()))
            {
                paths.filter(Files::isDirectory).forEach(path -> dirs.add(path.toFile()));
            }
        return dirs;
    }

    /** @return Top directory of the work tree */
    public File getWorkTree()
    {
        return git.getRepository().getWorkTree();
    }

    /** @return Current branch name */
    public synchronized String getBranch() throws Exception
    {
        return git.getRepository().getBranch();
    }

    /** @param log_count Maximum number of commits to read
     *  @param all_branches Show history of all branches, or only the current one?
     *  @return Current state of the repository
     */
    public synchronized State getState(final int log_count, final boolean all_branches) throws Exception
    {
        return getState(log_count, all_branches, null);
    }

    /** @param log_count Maximum number of commits to read
     *  @param all_branches Show history of all branches, or only the current one?
     *  @param search Text to search in commit message, author, id; <code>null</code> or empty for all commits
     *  @return Current state of the repository
     */
    public synchronized State getState(final int log_count, final boolean all_branches, final String search) throws Exception
    {
        return new State(getBranch(), getStatus(), getLog(log_count, all_branches, search),
                         getBranches(), getRemoteBranches(), getRemoteUrls(), getTags(), getStashes(), getSubmodules(),
                         getReflog(MAX_REFLOG));
    }

    /** @return URL of each remote, by remote name */
    public synchronized Map<String, String> getRemoteUrls()
    {
        final Map<String, String> result = new LinkedHashMap<>();
        for (String remote : git.getRepository().getRemoteNames().stream().sorted().toList())
            result.put(remote, git.getRepository().getConfig().getString("remote", remote, "url"));
        return result;
    }

    /** @param name Name of new remote
     *  @param url Remote URL
     */
    public synchronized void addRemote(final String name, final String url) throws Exception
    {
        if (git.getRepository().getRemoteNames().contains(name))
            throw new Exception("Remote '" + name + "' already exists");
        git.remoteAdd().setName(name).setUri(new URIish(url)).call();
    }

    /** @param name Remote
     *  @param url New URL
     */
    public synchronized void setRemoteUrl(final String name, final String url) throws Exception
    {
        git.remoteSetUrl().setRemoteName(name).setRemoteUri(new URIish(url)).call();
    }

    /** @param name Remote to remove, including its remote tracking branches */
    public synchronized void removeRemote(final String name) throws Exception
    {
        git.remoteRemove().setRemoteName(name).call();
    }

    /** @return Local branches */
    public synchronized List<BranchInfo> getBranches() throws Exception
    {
        final Repository repository = git.getRepository();
        final String current = repository.getFullBranch();
        final List<BranchInfo> result = new ArrayList<>();
        for (Ref ref : git.branchList().call())
        {
            // With detached HEAD, for example during a rebase, the list includes "HEAD"
            if (! ref.getName().startsWith(Constants.R_HEADS))
                continue;
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
    public synchronized Map<String, List<RemoteBranch>> getRemoteBranches() throws Exception
    {
        final Map<String, List<RemoteBranch>> result = new LinkedHashMap<>();
        for (String remote : git.getRepository().getRemoteNames().stream().sorted().toList())
            result.put(remote, new ArrayList<>());
        for (Ref ref : git.branchList().setListMode(ListMode.REMOTE).call())
        {
            // Skip "origin/HEAD", and a detached "HEAD" that the list includes for example during a rebase
            if (ref.isSymbolic()  ||  ! ref.getName().startsWith(Constants.R_REMOTES))
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
    public synchronized List<TagInfo> getTags() throws Exception
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
    public synchronized List<StashInfo> getStashes() throws Exception
    {
        final List<StashInfo> result = new ArrayList<>();
        int index = 0;
        for (RevCommit commit : git.stashList().call())
            result.add(new StashInfo(index++, commit.name(), commit.getShortMessage()));
        return result;
    }

    /** State of files for decorating them in a file browser
     *  @param changes Changed files by path, staged or not
     *  @param ignored Ignored files and directories that are not in the index
     *  @param branch Current branch
     *  @param ahead Commits ahead of upstream
     *  @param behind Commits behind upstream
     */
    public record FileStates(Map<String, ChangeType> changes, Set<String> ignored, String branch, int ahead, int behind) {}

    /** @return State of files */
    public synchronized FileStates getFileStates() throws Exception
    {
        final Status status = git.status().call();
        final Map<String, ChangeType> changes = new HashMap<>();
        for (String path : status.getUntracked())
            changes.put(path, ChangeType.UNTRACKED);
        for (String path : status.getAdded())
            changes.put(path, ChangeType.ADDED);
        for (String path : status.getRemoved())
            changes.put(path, ChangeType.DELETED);
        for (String path : status.getMissing())
            changes.put(path, ChangeType.DELETED);
        for (String path : status.getChanged())
            changes.put(path, ChangeType.MODIFIED);
        for (String path : status.getModified())
            changes.put(path, ChangeType.MODIFIED);
        for (String path : status.getConflicting())
            changes.put(path, ChangeType.CONFLICT);
        final String branch = git.getRepository().getBranch();
        int ahead = 0, behind = 0;
        final BranchTrackingStatus tracking = BranchTrackingStatus.of(git.getRepository(), branch);
        if (tracking != null)
        {
            ahead = tracking.getAheadCount();
            behind = tracking.getBehindCount();
        }
        return new FileStates(changes, Set.copyOf(status.getIgnoredNotInIndex()), branch, ahead, behind);
    }

    /** @return Staged and unstaged changes */
    public synchronized FileStatus getStatus() throws Exception
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
        final RepositoryState state = repository.getRepositoryState();
        final boolean merging = isMerging(state);
        return new FileStatus(staged, unstaged, merging, merging ? getMergeMessage(repository) : null,
                              state.isRebasing());
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
    public synchronized List<CommitInfo> getLog(final int count, final boolean all_branches) throws Exception
    {
        return getLog(count, all_branches, null);
    }

    /** Commit filter for a text in message, author, committer or id */
    private static class SearchFilter extends RevFilter
    {
        private final String text;

        SearchFilter(final String text)
        {
            this.text = text.toLowerCase(Locale.ROOT);
        }

        private boolean matches(final PersonIdent person)
        {
            return person.getName().toLowerCase(Locale.ROOT).contains(text)  ||
                   person.getEmailAddress().toLowerCase(Locale.ROOT).contains(text);
        }

        @Override
        public boolean include(final RevWalk walker, final RevCommit commit)
        {
            return commit.name().startsWith(text)  ||
                   commit.getFullMessage().toLowerCase(Locale.ROOT).contains(text)  ||
                   matches(commit.getAuthorIdent())  ||
                   matches(commit.getCommitterIdent());
        }

        @Override
        public RevFilter clone()
        {
            return this;
        }

        @Override
        public boolean requiresCommitBody()
        {
            return true;
        }
    }

    /** @param count Maximum number of commits
     *  @param all_branches Include all local and remote branches and tags, or only HEAD?
     *  @param search Text to search in commit message, author, id; <code>null</code> or empty for all commits
     *  @return Most recent (matching) commits with commit graph information, newest first
     */
    public synchronized List<CommitInfo> getLog(final int count, final boolean all_branches, final String search) throws Exception
    {
        final Repository repository = git.getRepository();
        final List<CommitInfo> result = new ArrayList<>();
        try (PlotWalk walk = new PlotWalk(repository))
        {
            // Children before parents, otherwise newest first
            walk.sort(RevSort.TOPO, true);
            walk.sort(RevSort.COMMIT_TIME_DESC, true);
            if (search != null  &&  ! search.isBlank())
                walk.setRevFilter(new SearchFilter(search.trim()));

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
            {
                // fillTo() may read a few more
                if (result.size() >= count)
                    break;
                result.add(new CommitInfo(commit.name(),
                                          commit.getShortMessage(),
                                          commit.getAuthorIdent().getName(),
                                          Instant.ofEpochSecond(commit.getCommitTime()),
                                          commit));
            }
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
    public synchronized String getStagedDiff(final String path) throws Exception
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
    public synchronized String getUnstagedDiff(final String path) throws Exception
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
        try (DiffFormatter formatter = createFormatter(out))
        {
            formatter.setRepository(git.getRepository());
            formatter.setPathFilter(PathFilter.create(path));
            formatter.format(old_tree, new_tree);
            formatter.flush();
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    /** Compare a commit with its first parent, detecting renames
     *  @param walk Walk for parsing the commit
     *  @param formatter Formatter to use for the comparison
     *  @param id Commit id
     */
    private List<DiffEntry> scanCommit(final RevWalk walk, final DiffFormatter formatter, final String id) throws Exception
    {
        final RevCommit commit = walk.parseCommit(ObjectId.fromString(id));
        final RevTree parent = commit.getParentCount() > 0
                             ? walk.parseCommit(commit.getParent(0)).getTree()
                             : null;
        formatter.setRepository(git.getRepository());
        formatter.setDetectRenames(true);
        formatter.getRenameDetector().setRenameScore(RENAME_SCORE);
        return formatter.scan(parent, commit.getTree());
    }

    /** @param id Commit id
     *  @return Files changed by the commit, compared to its first parent
     */
    public synchronized List<CommitFile> getCommitFiles(final String id) throws Exception
    {
        final List<CommitFile> result = new ArrayList<>();
        try (RevWalk walk = new RevWalk(git.getRepository());
             DiffFormatter formatter = createFormatter(DisabledOutputStream.INSTANCE))
        {
            for (DiffEntry entry : scanCommit(walk, formatter, id))
                result.add(toCommitFile(entry));
        }
        result.sort(Comparator.comparing(CommitFile::path));
        return result;
    }

    /** @param id Commit id
     *  @param path File path in that commit, new or old path of a rename
     *  @return Unified diff of that file in the commit, compared to its first parent
     */
    public synchronized String getCommitFileDiff(final String id, final String path) throws Exception
    {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (RevWalk walk = new RevWalk(git.getRepository());
             DiffFormatter formatter = createFormatter(out))
        {
            for (DiffEntry entry : scanCommit(walk, formatter, id))
                if (path.equals(entry.getNewPath())  ||  path.equals(entry.getOldPath()))
                {
                    formatter.format(entry);
                    break;
                }
            formatter.flush();
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    /** History of a file, following renames like 'git log --follow'
     *  @param path Current path of the file
     *  @param count Maximum number of commits
     *  @return Commits that changed the file, newest first, with the file's path in each commit
     */
    public synchronized List<FileVersion> getFileHistory(final String path, final int count) throws Exception
    {
        final Repository repository = git.getRepository();
        final List<FileVersion> result = new ArrayList<>();
        final ObjectId head = repository.resolve(Constants.HEAD);
        if (head == null)
            return result;
        try (RevWalk walk = new RevWalk(repository))
        {
            // Renames found while walking, new path -> old path.
            // The walk evaluates commits ahead of returning them, so the callback
            // cannot tell which returned commit had the rename. Instead, each commit
            // is checked for the current path, and older commits that lack it use the old path.
            final Map<String, String> renames = new HashMap<>();
            final FollowFilter filter = FollowFilter.create(path, repository.getConfig().get(DiffConfig.KEY));
            filter.setRenameCallback(new RenameCallback()
            {
                @Override
                public void renamed(final DiffEntry entry)
                {
                    renames.put(entry.getNewPath(), entry.getOldPath());
                }
            });
            walk.setTreeFilter(filter);
            walk.markStart(walk.parseCommit(head));
            String current = path;
            for (RevCommit commit : walk)
            {
                if (result.size() >= count)
                    break;
                while (TreeWalk.forPath(repository, current, commit.getTree()) == null  &&
                       renames.containsKey(current))
                    current = renames.get(current);
                result.add(new FileVersion(new CommitInfo(commit.name(),
                                                          commit.getShortMessage(),
                                                          commit.getAuthorIdent().getName(),
                                                          Instant.ofEpochSecond(commit.getCommitTime()),
                                                          null),
                                           current));
            }
        }
        return result;
    }

    /** Show who last changed each line of a file, following renames
     *  @param path File path relative to the work tree
     *  @return Lines of the file in the work tree with their last change
     *  @throws Exception if the file is not tracked
     */
    public synchronized List<BlameLine> blame(final String path) throws Exception
    {
        final BlameResult blame = git.blame().setFilePath(path).setFollowFileRenames(true).call();
        if (blame == null)
            throw new Exception("No history for " + path);
        blame.computeAll();
        final RawText text = blame.getResultContents();
        final List<BlameLine> result = new ArrayList<>(text.size());
        for (int i=0; i<text.size(); ++i)
        {
            final RevCommit commit = blame.getSourceCommit(i);
            final PersonIdent author = blame.getSourceAuthor(i);
            // For uncommitted lines, JGit reports a placeholder author "Not Committed Yet"
            result.add(new BlameLine(i + 1,
                                     commit == null ? null : commit.name(),
                                     commit == null  ||  author == null ? "" : author.getName(),
                                     commit == null ? null : Instant.ofEpochSecond(commit.getCommitTime()),
                                     text.getString(i)));
        }
        return result;
    }

    /** @param ignore Ignore white space changes when showing diffs?
     *  Hunk operations always use the complete diff
     */
    public void setIgnoreWhitespace(final boolean ignore)
    {
        ignore_whitespace = ignore;
    }

    /** @param out Where the diff is written
     *  @return Formatter that honors the white space option
     */
    private DiffFormatter createFormatter(final OutputStream out)
    {
        final DiffFormatter formatter = new DiffFormatter(out);
        formatter.setDiffComparator(ignore_whitespace ? RawTextComparator.WS_IGNORE_ALL : RawTextComparator.DEFAULT);
        return formatter;
    }

    /** @param paths Changed files, relative to the work tree
     *  @return Unified diff of these files from HEAD to the work tree, i.e. all uncommitted changes
     */
    public synchronized String getUncommittedDiff(final Collection<String> paths) throws Exception
    {
        if (paths.isEmpty())
            return "";
        final Repository repository = git.getRepository();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ObjectReader reader = repository.newObjectReader();
             DiffFormatter formatter = createFormatter(out))
        {
            formatter.setRepository(repository);
            // Limit to the changed files, the work tree may contain many ignored files
            formatter.setPathFilter(PathFilterGroup.createFromStrings(paths));
            formatter.format(getHeadTree(reader), new FileTreeIterator(repository));
            formatter.flush();
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    /** @param id Full commit id
     *  @return Commit header and unified diff against its first parent
     */
    public synchronized String getCommitDiff(final String id) throws Exception
    {
        final Repository repository = git.getRepository();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (RevWalk walk = new RevWalk(repository);
             DiffFormatter formatter = createFormatter(out))
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
            formatter.getRenameDetector().setRenameScore(RENAME_SCORE);
            formatter.format(parent, commit.getTree());
            formatter.flush();
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    /** Stage files, like 'git add' or 'git rm --cached' for deleted files
     *  @param paths File paths relative to the work tree
     */
    public synchronized void stage(final Collection<String> paths) throws Exception
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
    public synchronized void unstage(final Collection<String> paths) throws Exception
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
    public synchronized void discard(final Collection<String> paths) throws Exception
    {
        final DirCache index = git.getRepository().readDirCache();
        final List<String> tracked = new ArrayList<>();
        for (String path : paths)
            if (index.findEntry(path) >= 0)
                tracked.add(path);
            else
                deleteUntracked(path);
        if (! tracked.isEmpty())
            git.checkout().addPaths(tracked).call();
    }

    /** Delete untracked file, and parent directories that are then empty
     *  @param path File path relative to the work tree
     */
    private void deleteUntracked(final String path) throws Exception
    {
        final File top = getWorkTree();
        final File file = new File(top, path);
        Files.deleteIfExists(file.toPath());
        for (File parent = file.getParentFile();
             parent != null  &&  ! parent.equals(top);
             parent = parent.getParentFile())
        {
            final String[] content = parent.list();
            if (content == null  ||  content.length > 0)
                break;
            Files.delete(parent.toPath());
        }
    }

    /** Old and new content of a file, empty if the file does not exist on that side */
    public record Versions(byte[] old_content, byte[] new_content) {}

    private static final byte[] NO_CONTENT = new byte[0];

    /** Similarity in percent for detecting renames, same as the 'git' command (JGit uses 60) */
    private static final int RENAME_SCORE = 50;

    /** @param tree Tree, <code>null</code> for none
     *  @param path File path
     *  @return Content of the file in the tree, empty if not found
     */
    private byte[] getContent(final RevTree tree, final String path) throws Exception
    {
        if (tree == null  ||  path == null)
            return NO_CONTENT;
        final Repository repository = git.getRepository();
        try (TreeWalk walk = TreeWalk.forPath(repository, path, tree))
        {
            if (walk == null)
                return NO_CONTENT;
            return repository.open(walk.getObjectId(0), Constants.OBJ_BLOB).getBytes();
        }
    }

    /** @return Tree of HEAD, <code>null</code> if there is no commit */
    private RevTree getHeadRevTree(final RevWalk walk) throws Exception
    {
        final ObjectId head = git.getRepository().resolve(Constants.HEAD);
        return head == null ? null : walk.parseCommit(head).getTree();
    }

    /** @return Content of the file in the index (stage 0), empty if not in index */
    private byte[] getIndexContent(final String path) throws Exception
    {
        final DirCache index = git.getRepository().readDirCache();
        final DirCacheEntry entry = index.getEntry(path);
        return entry == null ? NO_CONTENT : getContent(entry);
    }

    /** @return Content of the file in the work tree, empty if it does not exist */
    private byte[] getWorkTreeContent(final String path) throws Exception
    {
        final Path file = new File(getWorkTree(), path).toPath();
        return Files.isRegularFile(file) ? Files.readAllBytes(file) : NO_CONTENT;
    }

    /** @param path File path
     *  @return Staged change: HEAD and index
     */
    public synchronized Versions getStagedVersions(final String path) throws Exception
    {
        try (RevWalk walk = new RevWalk(git.getRepository()))
        {
            return new Versions(getContent(getHeadRevTree(walk), path), getIndexContent(path));
        }
    }

    /** @param path File path
     *  @return Unstaged change: Index and work tree
     */
    public synchronized Versions getUnstagedVersions(final String path) throws Exception
    {
        return new Versions(getIndexContent(path), getWorkTreeContent(path));
    }

    /** @param path File path
     *  @return All uncommitted changes: HEAD and work tree
     */
    public synchronized Versions getUncommittedVersions(final String path) throws Exception
    {
        try (RevWalk walk = new RevWalk(git.getRepository()))
        {
            return new Versions(getContent(getHeadRevTree(walk), path), getWorkTreeContent(path));
        }
    }

    /** @param id Commit id
     *  @param path File path in that commit, new or old path of a rename
     *  @return File before (first parent, old path of a rename) and after the commit
     */
    public synchronized Versions getCommitFileVersions(final String id, final String path) throws Exception
    {
        try (RevWalk walk = new RevWalk(git.getRepository());
             DiffFormatter formatter = createFormatter(DisabledOutputStream.INSTANCE))
        {
            final RevCommit commit = walk.parseCommit(ObjectId.fromString(id));
            final RevTree parent = commit.getParentCount() > 0 ? walk.parseCommit(commit.getParent(0)).getTree() : null;
            String old_path = path, new_path = path;
            for (DiffEntry entry : scanCommit(walk, formatter, id))
                if (path.equals(entry.getNewPath())  ||  path.equals(entry.getOldPath()))
                {
                    old_path = entry.getChangeType() == DiffEntry.ChangeType.ADD ? null : entry.getOldPath();
                    new_path = entry.getChangeType() == DiffEntry.ChangeType.DELETE ? null : entry.getNewPath();
                    break;
                }
            return new Versions(getContent(parent, old_path), getContent(commit.getTree(), new_path));
        }
    }

    /** @param ref Branch, remote branch, tag, commit id or expression like 'HEAD~2'
     *  @return Commit
     *  @throws Exception if the ref is unknown
     */
    public synchronized Revision.Commit resolve(final String ref) throws Exception
    {
        final ObjectId id = git.getRepository().resolve(ref + "^{commit}");
        if (id == null)
            throw new Exception(Messages.UnknownRevision + ": " + ref);
        final String label = ref.matches("[0-9a-fA-F]{8,40}") ? ref.substring(0, 7) : ref;
        return new Revision.Commit(id.name(), label);
    }

    /** @param revision Revision
     *  @param reader Reader for commits
     *  @param walk Walk for parsing commits
     *  @return Tree iterator for that revision
     */
    private AbstractTreeIterator getTree(final Revision revision, final ObjectReader reader, final RevWalk walk) throws Exception
    {
        final Repository repository = git.getRepository();
        if (revision instanceof Revision.WorkTree)
            return new FileTreeIterator(repository);
        if (revision instanceof Revision.Index)
            return new DirCacheIterator(repository.readDirCache());
        if (revision instanceof Revision.Commit commit)
        {
            final CanonicalTreeParser parser = new CanonicalTreeParser();
            parser.reset(reader, walk.parseCommit(ObjectId.fromString(commit.id())).getTree());
            return parser;
        }
        throw new IllegalArgumentException("Cannot compare " + revision);
    }

    /** @param entry Difference
     *  @return Changed file
     */
    private static CommitFile toCommitFile(final DiffEntry entry)
    {
        return switch (entry.getChangeType())
        {
            case ADD    -> new CommitFile(ChangeType.ADDED, entry.getNewPath(), null);
            case COPY   -> new CommitFile(ChangeType.ADDED, entry.getNewPath(), null);
            case DELETE -> new CommitFile(ChangeType.DELETED, entry.getOldPath(), null);
            case RENAME -> new CommitFile(ChangeType.RENAMED, entry.getNewPath(), entry.getOldPath());
            default     -> new CommitFile(ChangeType.MODIFIED, entry.getNewPath(), null);
        };
    }

    /** Compare two revisions, ignoring untracked files of the work tree
     *  @param a Old side
     *  @param b New side
     *  @param paths Paths to compare, <code>null</code> for all
     *  @param formatter Formatter to use
     *  @return Differences
     */
    private List<DiffEntry> scan(final Revision a, final Revision b, final Collection<String> paths,
                                 final DiffFormatter formatter) throws Exception
    {
        final Repository repository = git.getRepository();
        try (ObjectReader reader = repository.newObjectReader();
             RevWalk walk = new RevWalk(reader))
        {
            formatter.setRepository(repository);
            if (paths != null)
                formatter.setPathFilter(PathFilterGroup.createFromStrings(paths));
            final AbstractTreeIterator old_tree = getTree(a, reader, walk);
            final AbstractTreeIterator new_tree = getTree(b, reader, walk);
            final List<DiffEntry> entries = formatter.scan(old_tree, new_tree);
            final DirCache index = a instanceof Revision.WorkTree  ||  b instanceof Revision.WorkTree
                                 ? repository.readDirCache()
                                 : null;
            final List<DiffEntry> tracked = new ArrayList<>();
            for (DiffEntry entry : entries)
            {
                // Untracked files in the work tree are not part of a version
                if (index != null)
                {
                    if (b instanceof Revision.WorkTree  &&  entry.getChangeType() == DiffEntry.ChangeType.ADD  &&
                        index.findEntry(entry.getNewPath()) < 0)
                        continue;
                    if (a instanceof Revision.WorkTree  &&  entry.getChangeType() == DiffEntry.ChangeType.DELETE  &&
                        index.findEntry(entry.getOldPath()) < 0)
                        continue;
                }
                tracked.add(entry);
            }
            // Detect renames after removing untracked files,
            // reading work tree files from the work tree
            final RenameDetector renames = new RenameDetector(repository);
            renames.setRenameScore(RENAME_SCORE);
            renames.addAll(tracked);
            return renames.compute(new ContentSource.Pair(getSource(a, reader), getSource(b, reader)),
                                   NullProgressMonitor.INSTANCE);
        }
    }

    /** @param revision Revision
     *  @param reader Reader for objects
     *  @return Source of file content for that revision
     */
    private ContentSource getSource(final Revision revision, final ObjectReader reader)
    {
        if (revision instanceof Revision.WorkTree)
            return ContentSource.create(new FileTreeIterator(git.getRepository()));
        return ContentSource.create(reader);
    }

    /** @param a Old side
     *  @param b New side
     *  @param paths Files or directories to compare, <code>null</code> for all
     *  @return Files that differ between the revisions, with renames.
     *          Untracked files of the work tree are ignored
     */
    public synchronized List<CommitFile> compare(final Revision a, final Revision b, final Collection<String> paths) throws Exception
    {
        final List<CommitFile> result = new ArrayList<>();
        try (DiffFormatter formatter = createFormatter(DisabledOutputStream.INSTANCE))
        {
            for (DiffEntry entry : scan(a, b, paths, formatter))
                result.add(toCommitFile(entry));
        }
        result.sort(Comparator.comparing(CommitFile::path));
        return result;
    }

    /** @param a Old side
     *  @param b New side
     *  @param paths Files or directories to compare, including the old path of renamed files, <code>null</code> for all
     *  @return Unified diff
     */
    public synchronized String getDiff(final Revision a, final Revision b, final Collection<String> paths) throws Exception
    {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DiffFormatter formatter = createFormatter(out))
        {
            for (DiffEntry entry : scan(a, b, paths, formatter))
                formatter.format(entry);
            formatter.flush();
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    /** @param revision Revision
     *  @param path File path
     *  @return Content, <code>null</code> if the file does not exist in that revision
     */
    private byte[] findContent(final Revision revision, final String path) throws Exception
    {
        final Repository repository = git.getRepository();
        if (revision instanceof Revision.WorkTree)
        {
            final Path file = new File(getWorkTree(), path).toPath();
            return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
        }
        if (revision instanceof Revision.Commit commit)
        {
            try (RevWalk walk = new RevWalk(repository);
                 TreeWalk tree = TreeWalk.forPath(repository, path, walk.parseCommit(ObjectId.fromString(commit.id())).getTree()))
            {
                if (tree == null  ||  (tree.getFileMode(0).getBits() & FileMode.TYPE_MASK) != FileMode.TYPE_FILE)
                    return null;
                return repository.open(tree.getObjectId(0), Constants.OBJ_BLOB).getBytes();
            }
        }
        final int stage = revision instanceof Revision.Stage s ? s.stage() : DirCacheEntry.STAGE_0;
        final DirCache index = repository.readDirCache();
        for (int i = index.findEntry(path);
             i >= 0  &&  i < index.getEntryCount()  &&  index.getEntry(i).getPathString().equals(path);
             ++i)
            if (index.getEntry(i).getStage() == stage)
                return getContent(index.getEntry(i));
        return null;
    }

    /** @param revision Revision
     *  @param path File path
     *  @return Content of the file, empty if it does not exist in that revision
     */
    public synchronized byte[] getContent(final Revision revision, final String path) throws Exception
    {
        final byte[] content = findContent(revision, path);
        return content == null ? NO_CONTENT : content;
    }

    /** @param revision Revision
     *  @param path File path
     *  @return Does the file exist in that revision?
     */
    public synchronized boolean exists(final Revision revision, final String path) throws Exception
    {
        return findContent(revision, path) != null;
    }

    /** @param a Old side
     *  @param b New side
     *  @param file File, with old path if renamed
     *  @return Content of the file in both revisions
     */
    public synchronized Versions getVersions(final Revision a, final Revision b, final CommitFile file) throws Exception
    {
        return new Versions(getContent(a, file.old_path() == null ? file.path() : file.old_path()),
                            getContent(b, file.path()));
    }

    /** Replace a file with its content in another revision
     *
     *  <p>If the file does not exist in that revision, it is deleted.
     *
     *  @param revision Commit or index
     *  @param source Path of the file in that revision, differs from the path after a rename
     *  @param path File path
     *  @param index_too Also update the index, otherwise only the work tree
     */
    public synchronized void restore(final Revision revision, final String source, final String path, final boolean index_too) throws Exception
    {
        final byte[] content = findContent(revision, source);
        final File file = new File(getWorkTree(), path);
        if (! source.equals(path))
        {
            // Older version of a renamed file: Write content to current path
            file.getParentFile().mkdirs();
            Files.write(file.toPath(), content == null ? NO_CONTENT : content);
            if (index_too)
                git.add().addFilepattern(path).call();
            return;
        }
        if (index_too  &&  revision instanceof Revision.Commit commit)
        {
            if (content == null)
            {
                if (git.getRepository().readDirCache().findEntry(path) >= 0)
                    git.rm().setCached(true).addFilepattern(path).call();
                Files.deleteIfExists(file.toPath());
            }
            else
                git.checkout().setStartPoint(commit.id()).addPath(path).call();
            return;
        }
        if (content == null)
            Files.deleteIfExists(file.toPath());
        else
        {
            file.getParentFile().mkdirs();
            Files.write(file.toPath(), content);
        }
    }

    /** Write the files of a commit's directory to another location
     *
     *  <p>Used to open an old version of a display
     *  with the files that it references by relative path.
     *
     *  @param commit Commit
     *  @param directory Directory in the repository, empty for the top
     *  @param target Where to write the files, using their path in the repository
     */
    public synchronized void export(final Revision.Commit commit, final String directory, final File target) throws Exception
    {
        final Repository repository = git.getRepository();
        try (RevWalk walk = new RevWalk(repository);
             TreeWalk tree = new TreeWalk(repository))
        {
            tree.addTree(walk.parseCommit(ObjectId.fromString(commit.id())).getTree());
            tree.setRecursive(true);
            if (! directory.isEmpty())
                tree.setFilter(PathFilter.create(directory));
            while (tree.next())
            {
                if ((tree.getFileMode(0).getBits() & FileMode.TYPE_MASK) != FileMode.TYPE_FILE)
                    continue;
                final File file = new File(target, tree.getPathString());
                file.getParentFile().mkdirs();
                Files.write(file.toPath(), repository.open(tree.getObjectId(0), Constants.OBJ_BLOB).getBytes());
            }
        }
    }

    /** Resolve a conflict by using one side's version of the file, then stage it
     *  @param path Conflicting file
     *  @param ours Use our version (current branch), or theirs (branch being merged)?
     */
    public synchronized void resolveConflict(final String path, final boolean ours) throws Exception
    {
        final DirCache index = git.getRepository().readDirCache();
        final int stage = ours ? DirCacheEntry.STAGE_2 : DirCacheEntry.STAGE_3;
        boolean have_stage = false;
        for (int i = index.findEntry(path);
             i >= 0  &&  i < index.getEntryCount()  &&  index.getEntry(i).getPathString().equals(path);
             ++i)
            if (index.getEntry(i).getStage() == stage)
                have_stage = true;
        if (have_stage)
        {
            git.checkout().setStage(ours ? Stage.OURS : Stage.THEIRS).addPath(path).call();
            git.add().addFilepattern(path).call();
        }
        else
        {
            // That side deleted the file
            Files.deleteIfExists(new File(getWorkTree(), path).toPath());
            git.rm().addFilepattern(path).call();
        }
    }

    /** @param path File path
     *  @param old_tree Old side
     *  @param new_tree New side
     *  @return Raw unified diff of the file, never ignoring white space
     */
    private byte[] getRawDiff(final String path, final AbstractTreeIterator old_tree, final AbstractTreeIterator new_tree) throws Exception
    {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DiffFormatter formatter = new DiffFormatter(out))
        {
            formatter.setRepository(git.getRepository());
            formatter.setPathFilter(PathFilter.create(path));
            formatter.format(old_tree, new_tree);
            formatter.flush();
        }
        return out.toByteArray();
    }

    /** @param diff Raw unified diff of one file
     *  @param index Index of the hunk
     *  @return Hunk
     *  @throws Exception if there is no such hunk
     */
    private static HunkHeader getHunk(final byte[] diff, final int index) throws Exception
    {
        final Patch patch = new Patch();
        patch.parse(new ByteArrayInputStream(diff));
        if (patch.getFiles().isEmpty())
            throw new Exception("No changes, refresh and try again");
        final FileHeader file = patch.getFiles().get(0);
        if (index < 0  ||  index >= file.getHunks().size())
            throw new Exception("Hunk " + (index + 1) + " not found, refresh and try again");
        return file.getHunks().get(index);
    }

    /** @param content File content
     *  @return Lines of the content, each including its '\n' unless it's the last line without newline
     */
    private static List<byte[]> splitLines(final byte[] content, final int start, final int end)
    {
        final List<byte[]> lines = new ArrayList<>();
        int line_start = start;
        for (int i=start; i<end; ++i)
            if (content[i] == '\n')
            {
                lines.add(java.util.Arrays.copyOfRange(content, line_start, i + 1));
                line_start = i + 1;
            }
        if (line_start < end)
            lines.add(java.util.Arrays.copyOfRange(content, line_start, end));
        return lines;
    }

    /** Line of a hunk
     *  @param type ' ', '-' or '+'
     *  @param text Text including line end
     *  @param selected Is the line selected?
     *  @param no_newline Is it the last line of the file, without line end?
     */
    private record HunkLine(char type, byte[] text, boolean selected, boolean no_newline) {}

    /** Apply one hunk to file content
     *
     *  <p>The lines around the change must exactly match,
     *  otherwise the file changed since the diff was computed.
     *
     *  <p>Only selected lines of the hunk can be applied:
     *  A removed line that is not selected remains,
     *  an added line that is not selected is skipped.
     *  Selected added lines take the place of the first selected removed line
     *  of the same block of changes, so replacing part of a block keeps the order of lines.
     *  In reverse, the other way around.
     *
     *  @param content Content of the file on the old side (or new side when reversed)
     *  @param hunk Hunk of a unified diff
     *  @param reverse Apply in reverse, i.e. undo the hunk?
     *  @param selected Indices of lines in the hunk to apply, 0 being the line after the '@@' header,
     *                  <code>null</code> to apply all
     *  @return Updated content
     *  @throws Exception if the hunk does not match the content
     */
    static byte[] applyHunk(final byte[] content, final HunkHeader hunk, final boolean reverse,
                            final Set<Integer> selected) throws Exception
    {
        // Lines of the hunk without the '@@' header line
        final List<byte[]> body = splitLines(hunk.getBuffer(), hunk.getStartOffset(), hunk.getEndOffset());
        body.remove(0);
        final List<HunkLine> lines = new ArrayList<>();
        int index = -1;
        for (byte[] line : body)
        {
            ++index;
            if (line.length == 0)
                continue;
            final char type = (char) line[0];
            if (type == ' '  ||  type == '-'  ||  type == '+')
                lines.add(new HunkLine(type, java.util.Arrays.copyOfRange(line, 1, line.length),
                                       selected == null  ||  selected.contains(index), false));
            else if (type == '\\'  &&  ! lines.isEmpty())
            {
                // "\ No newline at end of file" applies to the preceding line
                final HunkLine last = lines.get(lines.size() - 1);
                lines.set(lines.size() - 1, new HunkLine(last.type(), stripNewline(last.text()), last.selected(), true));
            }
            else
                break;  // End of hunk, for example the next 'diff --git'
        }

        // Side that the content has, side that it gets
        final char from_type = reverse ? '+' : '-';
        final List<HunkLine> from = new ArrayList<>(), to = new ArrayList<>();
        final List<HunkLine> block = new ArrayList<>();
        for (HunkLine line : lines)
        {
            if (line.type() == ' ')
            {
                applyBlock(block, from_type, from, to);
                from.add(line);
                to.add(line);
            }
            else
                block.add(line);
        }
        applyBlock(block, from_type, from, to);

        final int start = reverse ? hunk.getNewStartLine() : hunk.getOldImage().getStartLine();
        final int count = reverse ? hunk.getNewLineCount() : hunk.getOldImage().getLineCount();
        // For an empty range, the start is the line after which to insert
        final int position = count == 0 ? start : start - 1;

        final List<byte[]> file = splitLines(content, 0, content.length);
        if (position < 0  ||  position + from.size() > file.size())
            throw new Exception("The file changed, refresh and try again");
        for (int i=0; i<from.size(); ++i)
            if (! java.util.Arrays.equals(file.get(position + i), from.get(i).text()))
                throw new Exception("The file changed, refresh and try again");

        // Line end for a line that had none but is now followed by others
        final byte[] newline = lines.stream().anyMatch(l -> endsWith(l.text(), "\r\n")) ? "\r\n".getBytes() : "\n".getBytes();
        final ByteArrayOutputStream out = new ByteArrayOutputStream(content.length);
        for (int i=0; i<position; ++i)
            out.write(file.get(i));
        for (int i=0; i<to.size(); ++i)
        {
            out.write(to.get(i).text());
            final boolean more = i < to.size() - 1  ||  position + from.size() < file.size();
            if (to.get(i).no_newline()  &&  more)
                out.write(newline);
        }
        for (int i=position + from.size(); i<file.size(); ++i)
            out.write(file.get(i));
        return out.toByteArray();
    }

    /** Apply a block of changed lines
     *  @param block Removed and added lines between context lines, cleared when done
     *  @param from_type Type of lines that the content has: '-', or '+' when reversed
     *  @param from Lines that the content has
     *  @param to Lines that the content gets
     */
    private static void applyBlock(final List<HunkLine> block, final char from_type,
                                   final List<HunkLine> from, final List<HunkLine> to)
    {
        if (block.isEmpty())
            return;
        // Existing lines that remain, and the new lines to insert where the first existing line is replaced
        final List<HunkLine> remain = new ArrayList<>(), insert = new ArrayList<>();
        int insert_at = -1;
        for (HunkLine line : block)
            if (line.type() == from_type)
            {
                from.add(line);
                if (line.selected())
                {
                    if (insert_at < 0)
                        insert_at = remain.size();
                }
                else
                    remain.add(line);
            }
            else if (line.selected())
                insert.add(line);
        if (insert_at < 0)
            insert_at = remain.size();
        to.addAll(remain.subList(0, insert_at));
        to.addAll(insert);
        to.addAll(remain.subList(insert_at, remain.size()));
        block.clear();
    }

    private static boolean endsWith(final byte[] text, final String end)
    {
        final byte[] bytes = end.getBytes();
        if (text.length < bytes.length)
            return false;
        for (int i=0; i<bytes.length; ++i)
            if (text[text.length - bytes.length + i] != bytes[i])
                return false;
        return true;
    }

    /** @param line Line
     *  @return Line without trailing '\n'
     */
    private static byte[] stripNewline(final byte[] line)
    {
        if (line.length > 0  &&  line[line.length - 1] == '\n')
            return java.util.Arrays.copyOf(line, line.length - 1);
        return line;
    }

    /** @param path File path
     *  @return Index entry of the file, <code>null</code> if not in index
     *  @throws Exception if the file has a conflict
     */
    private DirCacheEntry getIndexEntry(final String path) throws Exception
    {
        final DirCache index = git.getRepository().readDirCache();
        final int i = index.findEntry(path);
        if (i < 0)
            return null;
        final DirCacheEntry entry = index.getEntry(i);
        if (entry.getStage() != DirCacheEntry.STAGE_0)
            throw new Exception("Resolve the conflict of " + path + " first");
        return entry;
    }

    /** @param entry Index entry or <code>null</code>
     *  @return Content of the index entry, empty if <code>null</code>
     */
    private byte[] getContent(final DirCacheEntry entry) throws Exception
    {
        if (entry == null)
            return new byte[0];
        return git.getRepository().open(entry.getObjectId(), Constants.OBJ_BLOB).getBytes();
    }

    /** Write new content for a file into the index
     *  @param path File path
     *  @param content New content
     *  @param mode File mode
     */
    private void writeIndex(final String path, final byte[] content, final FileMode mode) throws Exception
    {
        final Repository repository = git.getRepository();
        final ObjectId id;
        try (ObjectInserter inserter = repository.newObjectInserter())
        {
            id = inserter.insert(Constants.OBJ_BLOB, content);
            inserter.flush();
        }
        final DirCache index = repository.lockDirCache();
        try
        {
            final DirCacheEditor editor = index.editor();
            editor.add(new DirCacheEditor.PathEdit(path)
            {
                @Override
                public void apply(final DirCacheEntry entry)
                {
                    entry.setFileMode(mode);
                    entry.setObjectId(id);
                    entry.setLength(content.length);
                    // Index content differs from the work tree, force a content check
                    entry.setLastModified(Instant.EPOCH);
                }
            });
            editor.commit();
        }
        finally
        {
            index.unlock();
        }
    }

    /** Stage one hunk of the unstaged changes
     *  @param path File path
     *  @param hunk Index of the hunk in the unstaged diff of the file
     */
    public synchronized void stageHunk(final String path, final int hunk) throws Exception
    {
        stageHunk(path, hunk, null);
    }

    /** @param path File path
     *  @param hunk Index of the hunk in the unstaged diff of the file
     *  @param lines Lines of the hunk, <code>null</code> for all
     */
    private void stageHunk(final String path, final int hunk, final Set<Integer> lines) throws Exception
    {
        final Repository repository = git.getRepository();
        final DirCacheEntry entry = getIndexEntry(path);
        final byte[] diff = getRawDiff(path, new DirCacheIterator(repository.readDirCache()), new FileTreeIterator(repository));
        final byte[] content = applyHunk(getContent(entry), getHunk(diff, hunk), false, lines);
        writeIndex(path, content, entry == null ? FileMode.REGULAR_FILE : entry.getFileMode());
    }

    /** Stage selected lines of the unstaged changes
     *  @param path File path
     *  @param lines Line indices by hunk index, see {@link #applyHunk(byte[], HunkHeader, boolean, Set)}
     */
    public synchronized void stageLines(final String path, final Map<Integer, Set<Integer>> lines) throws Exception
    {
        // Last hunk first, so that the index of the remaining hunks does not change
        for (int hunk : lines.keySet().stream().sorted(Comparator.reverseOrder()).toList())
            stageHunk(path, hunk, lines.get(hunk));
    }

    /** Unstage selected lines of the staged changes
     *  @param path File path
     *  @param lines Line indices by hunk index
     */
    public synchronized void unstageLines(final String path, final Map<Integer, Set<Integer>> lines) throws Exception
    {
        for (int hunk : lines.keySet().stream().sorted(Comparator.reverseOrder()).toList())
            unstageHunk(path, hunk, lines.get(hunk));
    }

    /** Discard selected lines of the unstaged changes
     *  @param path File path
     *  @param lines Line indices by hunk index
     */
    public synchronized void discardLines(final String path, final Map<Integer, Set<Integer>> lines) throws Exception
    {
        for (int hunk : lines.keySet().stream().sorted(Comparator.reverseOrder()).toList())
            discardHunk(path, hunk, lines.get(hunk));
    }

    /** Unstage one hunk of the staged changes
     *  @param path File path
     *  @param hunk Index of the hunk in the staged diff of the file
     */
    public synchronized void unstageHunk(final String path, final int hunk) throws Exception
    {
        unstageHunk(path, hunk, null);
    }

    private void unstageHunk(final String path, final int hunk, final Set<Integer> lines) throws Exception
    {
        final Repository repository = git.getRepository();
        final DirCacheEntry entry = getIndexEntry(path);
        if (entry == null)
            throw new Exception(path + " is not staged");
        final byte[] diff;
        try (ObjectReader reader = repository.newObjectReader())
        {
            diff = getRawDiff(path, getHeadTree(reader), new DirCacheIterator(repository.readDirCache()));
        }
        final byte[] content = applyHunk(getContent(entry), getHunk(diff, hunk), true, lines);
        writeIndex(path, content, entry.getFileMode());
    }

    /** Discard one hunk of the unstaged changes in the work tree
     *  @param path File path
     *  @param hunk Index of the hunk in the unstaged diff of the file
     */
    public synchronized void discardHunk(final String path, final int hunk) throws Exception
    {
        discardHunk(path, hunk, null);
    }

    private void discardHunk(final String path, final int hunk, final Set<Integer> lines) throws Exception
    {
        final Repository repository = git.getRepository();
        final byte[] diff = getRawDiff(path, new DirCacheIterator(repository.readDirCache()), new FileTreeIterator(repository));
        final Path file = new File(getWorkTree(), path).toPath();
        final byte[] content = applyHunk(Files.readAllBytes(file), getHunk(diff, hunk), true, lines);
        Files.write(file, content);
    }

    /** @param old_name Current name of local branch
     *  @param new_name New name
     */
    public synchronized void renameBranch(final String old_name, final String new_name) throws Exception
    {
        git.branchRename().setOldName(old_name).setNewName(new_name).call();
    }

    /** Add a pattern to the .gitignore at the top of the work tree
     *  @param pattern Pattern, for example "build/" or "*.log"
     */
    public synchronized void addToIgnore(final String pattern) throws Exception
    {
        final Path ignore = new File(getWorkTree(), Constants.GITIGNORE_FILENAME).toPath();
        String text = Files.exists(ignore) ? Files.readString(ignore) : "";
        if (! text.isEmpty()  &&  ! text.endsWith("\n"))
            text += "\n";
        Files.writeString(ignore, text + pattern + "\n");
    }

    /** @return Full message of the HEAD commit, <code>null</code> if there is none */
    public synchronized String getHeadMessage() throws Exception
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
    public synchronized void commit(final String message, final boolean amend) throws Exception
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
    public synchronized boolean merge(final String name, final boolean no_ff) throws Exception
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

    /** How {@link #reset} treats index and work tree */
    public enum ResetMode
    {
        /** Keep index and work tree, only move the branch */
        SOFT(ResetType.SOFT),
        /** Reset the index, keep the work tree */
        MIXED(ResetType.MIXED),
        /** Reset index and work tree, discarding all changes */
        HARD(ResetType.HARD);

        private final ResetType type;

        private ResetMode(final ResetType type)
        {
            this.type = type;
        }
    }

    /** Reset the current branch to a commit, like 'git reset --soft|--mixed|--hard'
     *  @param id Commit id
     *  @param mode Reset mode
     */
    public synchronized void reset(final String id, final ResetMode mode) throws Exception
    {
        git.reset().setRef(id).setMode(mode.type).call();
    }

    /** Abort a merge, cherry-pick or revert: Reset index and work tree to HEAD
     *
     *  <p>Like 'git merge --abort', discards all uncommitted changes.
     */
    public synchronized void abortMerge() throws Exception
    {
        git.reset().setMode(ResetType.HARD).call();
    }

    /** Apply the changes of a commit to the current branch
     *  @param id Commit id
     *  @return <code>true</code> if it stopped with conflicts that need to be resolved
     */
    public synchronized boolean cherryPick(final String id) throws Exception
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
    public synchronized boolean revert(final String id) throws Exception
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
    public synchronized void createTag(final String name, final String message, final String commit) throws Exception
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
    public synchronized void deleteTag(final String name) throws Exception
    {
        git.tagDelete().setTags(name).call();
    }

    /** @param branch Local branch to check out */
    public synchronized void checkout(final String branch) throws Exception
    {
        git.checkout().setName(branch).call();
    }

    /** Create local branch that tracks a remote branch, and check it out
     *  @param remote_branch Remote branch
     *  @param name Name of the new local branch
     */
    public synchronized void checkoutRemote(final RemoteBranch remote_branch, final String name) throws Exception
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
    public synchronized void createBranch(final String name, final String start_point, final boolean checkout) throws Exception
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
    public synchronized void deleteBranch(final String name, final boolean force) throws Exception
    {
        git.branchDelete().setBranchNames(Constants.R_HEADS + name).setForce(force).call();
    }

    /** @param id Stash commit id
     *  @return Diff of the stashed changes, including untracked files
     */
    public synchronized String getStashDiff(final String id) throws Exception
    {
        final StringBuilder result = new StringBuilder(getCommitDiff(id));
        // Untracked files are stored in a third parent commit
        final Repository repository = git.getRepository();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (RevWalk walk = new RevWalk(repository);
             DiffFormatter formatter = createFormatter(out))
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
    public synchronized void stash(final String message, final boolean include_untracked) throws Exception
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
    public synchronized void applyStash(final int index, final boolean drop) throws Exception
    {
        git.stashApply().setStashRef(Constants.STASH + "@{" + index + "}").setRestoreUntracked(true).call();
        if (drop)
            dropStash(index);
    }

    /** @param index Stash index to delete */
    public synchronized void dropStash(final int index) throws Exception
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
    public synchronized void fetch(final CredentialsProvider credentials) throws Exception
    {
        for (String remote : git.getRepository().getRemoteNames())
        {
            final var command = git.fetch().setRemote(remote).setRemoveDeletedRefs(isPrune(remote));
            final TagOpt tags = RemoteConfig.getAllRemoteConfigs(git.getRepository().getConfig()).stream()
                                            .filter(r -> r.getName().equals(remote))
                                            .map(RemoteConfig::getTagOpt)
                                            .findFirst().orElse(TagOpt.AUTO_FOLLOW);
            command.setTagOpt(tags);
            configure(command, credentials);
            command.call();
        }
    }

    /** @param remote Remote
     *  @return Remove branches that were deleted on the remote when fetching?
     *          Setting of the remote, else general setting, else <code>true</code>
     */
    private boolean isPrune(final String remote)
    {
        final StoredConfig config = git.getRepository().getConfig();
        if (config.getNames("remote", remote).contains("prune"))
            return config.getBoolean("remote", remote, "prune", true);
        if (config.getNames("fetch").contains("prune"))
            return config.getBoolean("fetch", null, "prune", true);
        return true;
    }

    /** Settings of a remote
     *  @param name Name
     *  @param fetch_url URL for fetching
     *  @param push_url URL for pushing, empty to use the fetch URL
     *  @param fetch_specs Fetch ref specs
     *  @param push_specs Push ref specs, empty for default
     *  @param prune Remove branches deleted on the remote when fetching?
     *  @param tags How to fetch tags
     */
    public record RemoteSettings(String name, String fetch_url, String push_url,
                                 List<String> fetch_specs, List<String> push_specs,
                                 boolean prune, TagOpt tags) {}

    /** @param name Remote name
     *  @return Settings of the remote
     */
    public synchronized RemoteSettings getRemoteSettings(final String name) throws Exception
    {
        final RemoteConfig remote = new RemoteConfig(git.getRepository().getConfig(), name);
        return new RemoteSettings(name,
                                  remote.getURIs().isEmpty() ? "" : remote.getURIs().get(0).toString(),
                                  remote.getPushURIs().isEmpty() ? "" : remote.getPushURIs().get(0).toString(),
                                  remote.getFetchRefSpecs().stream().map(RefSpec::toString).toList(),
                                  remote.getPushRefSpecs().stream().map(RefSpec::toString).toList(),
                                  isPrune(name),
                                  remote.getTagOpt());
    }

    /** @param settings New settings of the remote */
    public synchronized void setRemoteSettings(final RemoteSettings settings) throws Exception
    {
        final StoredConfig config = git.getRepository().getConfig();
        final RemoteConfig remote = new RemoteConfig(config, settings.name());
        for (URIish uri : List.copyOf(remote.getURIs()))
            remote.removeURI(uri);
        remote.addURI(new URIish(settings.fetch_url()));
        for (URIish uri : List.copyOf(remote.getPushURIs()))
            remote.removePushURI(uri);
        if (! settings.push_url().isBlank())
            remote.addPushURI(new URIish(settings.push_url().trim()));
        final List<RefSpec> fetch = new ArrayList<>();
        for (String spec : settings.fetch_specs())
            if (! spec.isBlank())
                fetch.add(new RefSpec(spec.trim()));
        remote.setFetchRefSpecs(fetch);
        final List<RefSpec> push = new ArrayList<>();
        for (String spec : settings.push_specs())
            if (! spec.isBlank())
                push.add(new RefSpec(spec.trim()));
        remote.setPushRefSpecs(push);
        remote.setTagOpt(settings.tags());
        remote.update(config);
        config.setBoolean("remote", settings.name(), "prune", settings.prune());
        config.save();
    }

    // --- Patches ---------------------------------------------------------------

    private static final DateTimeFormatter PATCH_DATE = DateTimeFormatter.RFC_1123_DATE_TIME;

    /** Create a patch in the format of 'git format-patch', all commits in one mailbox
     *  @param ids Commits, any order
     *  @return Patch, oldest commit first
     */
    public synchronized String formatPatch(final Collection<String> ids) throws Exception
    {
        final Repository repository = git.getRepository();
        final List<RevCommit> commits = new ArrayList<>();
        try (RevWalk walk = new RevWalk(repository))
        {
            // Parents before children
            final Set<ObjectId> selected = new HashSet<>();
            for (String id : ids)
            {
                final RevCommit commit = walk.parseCommit(ObjectId.fromString(id));
                selected.add(commit.getId());
                walk.markStart(commit);
            }
            walk.sort(RevSort.TOPO, true);
            walk.sort(RevSort.REVERSE, true);
            for (RevCommit commit : walk)
                if (selected.contains(commit.getId()))
                    commits.add(commit);
            walk.reset();
            final StringBuilder patch = new StringBuilder();
            for (int i=0; i<commits.size(); ++i)
            {
                final RevCommit commit = commits.get(i);
                final PersonIdent author = commit.getAuthorIdent();
                final String number = commits.size() > 1 ? "[PATCH " + (i+1) + "/" + commits.size() + "] " : "[PATCH] ";
                patch.append("From ").append(commit.name()).append(" Mon Sep 17 00:00:00 2001\n")
                     .append("From: ").append(author.getName()).append(" <").append(author.getEmailAddress()).append(">\n")
                     .append("Date: ").append(PATCH_DATE.format(author.getWhenAsInstant().atZone(author.getZoneId()))).append("\n")
                     .append("Subject: ").append(number).append(commit.getShortMessage()).append("\n\n");
                final String body = commit.getFullMessage().substring(commit.getShortMessage().length()).strip();
                if (! body.isEmpty())
                    patch.append(body).append("\n");
                patch.append("---\n");
                final ByteArrayOutputStream out = new ByteArrayOutputStream();
                try (DiffFormatter formatter = new DiffFormatter(out))
                {
                    formatter.setRepository(repository);
                    formatter.setDetectRenames(true);
                    formatter.getRenameDetector().setRenameScore(RENAME_SCORE);
                    formatter.format(commit.getParentCount() > 0 ? walk.parseCommit(commit.getParent(0)).getTree() : null,
                                     commit.getTree());
                }
                patch.append(out.toString(StandardCharsets.UTF_8))
                     .append("-- \nphoebus git\n\n");
            }
            return patch.toString();
        }
    }

    /** @param patch Patch
     *  @return Files changed by the patch
     */
    public static List<CommitFile> getPatchFiles(final byte[] patch) throws Exception
    {
        final Patch parsed = new Patch();
        parsed.parse(new ByteArrayInputStream(patch));
        if (! parsed.getErrors().isEmpty())
            throw new Exception(parsed.getErrors().get(0).getMessage());
        final List<CommitFile> result = new ArrayList<>();
        for (FileHeader file : parsed.getFiles())
            result.add(toCommitFile(file));
        return result;
    }

    /** Apply a patch to the work tree
     *  @param patch Patch, unified diff or mailbox of 'git format-patch'
     *  @return Updated files
     *  @throws Exception if the patch does not apply
     */
    public synchronized List<String> applyPatch(final byte[] patch) throws Exception
    {
        final ApplyResult result = git.apply().setPatch(new ByteArrayInputStream(patch)).call();
        final File top = getWorkTree().getAbsoluteFile();
        return result.getUpdatedFiles().stream()
                     .map(file -> top.toPath().relativize(file.getAbsoluteFile().toPath()).toString().replace(File.separatorChar, '/'))
                     .sorted()
                     .toList();
    }

    // --- Submodules ------------------------------------------------------------

    /** State of a submodule */
    public enum SubmoduleState
    {
        /** Not initialized, no work tree */
        UNINITIALIZED,
        /** Checked out commit differs from the one recorded in the parent */
        DIFFERENT,
        /** Checked out commit as recorded */
        OK,
        /** Configured, but missing */
        MISSING
    }

    /** Submodule
     *  @param path Path in the parent repository
     *  @param url URL
     *  @param recorded Commit recorded in the parent
     *  @param actual Commit checked out in the submodule, <code>null</code> if not initialized
     *  @param state State
     */
    public record SubmoduleInfo(String path, String url, String recorded, String actual, SubmoduleState state) {}

    /** @return Submodules */
    public synchronized List<SubmoduleInfo> getSubmodules() throws Exception
    {
        final List<SubmoduleInfo> result = new ArrayList<>();
        // Avoid walking the index when there are no submodules
        if (! new File(getWorkTree(), Constants.DOT_GIT_MODULES).exists())
            return result;
        for (Map.Entry<String, SubmoduleStatus> entry : new TreeMap<>(git.submoduleStatus().call()).entrySet())
        {
            final SubmoduleStatus status = entry.getValue();
            final SubmoduleState state = switch (status.getType())
            {
                case MISSING -> SubmoduleState.MISSING;
                case UNINITIALIZED -> SubmoduleState.UNINITIALIZED;
                case REV_CHECKED_OUT -> SubmoduleState.DIFFERENT;
                default -> SubmoduleState.OK;
            };
            String url = "";
            try (SubmoduleWalk walk = SubmoduleWalk.forIndex(git.getRepository()))
            {
                walk.setFilter(PathFilter.create(entry.getKey()));
                if (walk.next())
                    url = walk.getModulesUrl() == null ? "" : walk.getModulesUrl();
            }
            result.add(new SubmoduleInfo(entry.getKey(), url,
                                         status.getIndexId() == null ? null : status.getIndexId().name(),
                                         status.getHeadId() == null ? null : status.getHeadId().name(),
                                         state));
        }
        return result;
    }

    /** Initialize and update submodules: Clone if needed, check out recorded commit
     *  @param paths Submodule paths, empty for all
     *  @param credentials Credentials provider
     */
    public synchronized void updateSubmodules(final Collection<String> paths, final CredentialsProvider credentials) throws Exception
    {
        final var init = git.submoduleInit();
        paths.forEach(init::addPath);
        init.call();
        // After 'git submodule deinit', the submodule's repository remains in .git/modules.
        // Re-connect it to the work tree, JGit would otherwise fail to clone into that existing directory.
        for (Map.Entry<String, SubmoduleStatus> entry : git.submoduleStatus().call().entrySet())
            if (entry.getValue().getType() == SubmoduleStatusType.UNINITIALIZED  &&
                (paths.isEmpty()  ||  paths.contains(entry.getKey()))  &&
                entry.getValue().getIndexId() != null)
                reconnectSubmodule(entry.getKey(), entry.getValue().getIndexId());
        final var update = git.submoduleUpdate();
        paths.forEach(update::addPath);
        configure(update, credentials);
        update.call();
    }

    /** @param paths Changed files, relative to the work tree
     *  @return Patch of the uncommitted changes from HEAD to the work tree, never ignoring white space
     */
    public synchronized String getUncommittedPatch(final Collection<String> paths) throws Exception
    {
        final Repository repository = git.getRepository();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ObjectReader reader = repository.newObjectReader();
             DiffFormatter formatter = new DiffFormatter(out))
        {
            formatter.setRepository(repository);
            formatter.setDetectRenames(true);
            formatter.getRenameDetector().setRenameScore(RENAME_SCORE);
            formatter.setPathFilter(PathFilterGroup.createFromStrings(paths));
            formatter.format(getHeadTree(reader), new FileTreeIterator(repository));
            formatter.flush();
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    /** Re-connect a submodule whose repository exists in .git/modules
     *  @param path Submodule path
     *  @param recorded Commit recorded for the submodule
     */
    private void reconnectSubmodule(final String path, final ObjectId recorded) throws Exception
    {
        final File module = new File(git.getRepository().getDirectory(), Constants.MODULES + "/" + path);
        final File work = new File(getWorkTree(), path);
        if (! module.isDirectory()  ||  new File(work, Constants.DOT_GIT).exists())
            return;
        work.mkdirs();
        Files.writeString(new File(work, Constants.DOT_GIT).toPath(),
                          "gitdir: " + work.toPath().relativize(module.toPath()).toString().replace(File.separatorChar, '/') + "\n");
        try (Repository sub = new FileRepositoryBuilder().setGitDir(module).setWorkTree(work).build();
             Git sub_git = Git.wrap(sub))
        {
            final StoredConfig config = sub.getConfig();
            config.setString(ConfigConstants.CONFIG_CORE_SECTION, null, ConfigConstants.CONFIG_KEY_WORKTREE,
                             module.toPath().relativize(work.toPath()).toString().replace(File.separatorChar, '/'));
            config.save();
            sub_git.reset().setMode(ResetType.HARD).setRef(recorded.name()).call();
        }
    }

    /** Maximum number of reflog entries read for the state */
    static final int MAX_REFLOG = 500;

    /** Entry of the reflog: Where HEAD pointed, and why
     *  @param id Commit that HEAD pointed to
     *  @param old_id Previous commit
     *  @param comment What moved HEAD, for example "commit: message" or "reset: moving to HEAD~1"
     *  @param who Who did it
     *  @param date When
     */
    public record ReflogInfo(String id, String old_id, String comment, String who, Instant date) {}

    /** @param max Maximum number of entries
     *  @return Reflog of HEAD, newest first, empty if there is none
     */
    public synchronized List<ReflogInfo> getReflog(final int max) throws Exception
    {
        final List<ReflogInfo> result = new ArrayList<>();
        final ReflogReader reader = git.getRepository().getReflogReader(Constants.HEAD);
        if (reader == null)
            return result;
        for (ReflogEntry entry : reader.getReverseEntries(max))
        {
            final PersonIdent who = entry.getWho();
            result.add(new ReflogInfo(entry.getNewId().name(), entry.getOldId().name(), entry.getComment(),
                                      who.getName(), who.getWhenAsInstant()));
        }
        return result;
    }

    /** @param ids Commit ids
     *  @return Same commits, oldest (ancestors) first
     */
    public synchronized List<String> sortOldestFirst(final Collection<String> ids) throws Exception
    {
        final List<String> result = new ArrayList<>();
        try (RevWalk walk = new RevWalk(git.getRepository()))
        {
            final Set<ObjectId> selected = new HashSet<>();
            for (String id : ids)
            {
                final RevCommit commit = walk.parseCommit(ObjectId.fromString(id));
                selected.add(commit.getId());
                walk.markStart(commit);
            }
            walk.sort(RevSort.TOPO, true);
            walk.sort(RevSort.REVERSE, true);
            for (RevCommit commit : walk)
                if (selected.contains(commit.getId()))
                    result.add(commit.name());
        }
        return result;
    }

    /** Cherry-pick several commits, oldest first, stopping at a conflict
     *  @param ids Commit ids
     *  @return <code>true</code> if it stopped with conflicts
     */
    public synchronized boolean cherryPick(final Collection<String> ids) throws Exception
    {
        for (String id : sortOldestFirst(ids))
            if (cherryPick(id))
                return true;
        return false;
    }

    /** Revert several commits, newest first, stopping at a conflict
     *  @param ids Commit ids
     *  @return <code>true</code> if it stopped with conflicts
     */
    public synchronized boolean revert(final Collection<String> ids) throws Exception
    {
        final List<String> sorted = new ArrayList<>(sortOldestFirst(ids));
        java.util.Collections.reverse(sorted);
        for (String id : sorted)
            if (revert(id))
                return true;
        return false;
    }

    // --- Git configuration --------------------------------------------------------

    /** Entry of a git configuration file
     *  @param key Section, optional subsection, name, for example "user.name" or "remote.origin.url"
     *  @param value Value
     */
    public record ConfigEntry(String key, String value) {}

    /** @return Configuration file of the user, '~/.gitconfig' */
    static File getUserConfigFile()
    {
        return new File(FS.DETECTED.userHome(), Constants.DOT_GIT + "config");
    }

    /** @param global User configuration, or the one of this repository?
     *  @return Configuration file, loaded, without inherited settings
     */
    private FileBasedConfig loadConfig(final boolean global) throws Exception
    {
        final File file = global ? getUserConfigFile() : new File(git.getRepository().getDirectory(), Constants.CONFIG);
        final FileBasedConfig config = new FileBasedConfig(null, file, FS.DETECTED);
        if (file.exists())
            config.load();
        return config;
    }

    /** @param config Configuration
     *  @return Entries, in the order of sections; keys with several values have several entries
     */
    static List<ConfigEntry> readConfig(final Config config)
    {
        final List<ConfigEntry> result = new ArrayList<>();
        for (String section : config.getSections())
        {
            for (String name : config.getNames(section))
                for (String value : config.getStringList(section, null, name))
                    result.add(new ConfigEntry(section + "." + name, value));
            for (String subsection : config.getSubsections(section))
                for (String name : config.getNames(section, subsection))
                    for (String value : config.getStringList(section, subsection, name))
                        result.add(new ConfigEntry(section + "." + subsection + "." + name, value));
        }
        return result;
    }

    /** @param entries Entries
     *  @return Values by key, keys in order of first appearance
     */
    private static Map<String, List<String>> group(final List<ConfigEntry> entries)
    {
        final Map<String, List<String>> result = new LinkedHashMap<>();
        for (ConfigEntry entry : entries)
            result.computeIfAbsent(entry.key().trim(), k -> new ArrayList<>()).add(entry.value());
        return result;
    }

    /** Update configuration to have exactly the given entries
     *
     *  <p>Only changed keys are written, other content of the file remains.
     *
     *  @param config Configuration
     *  @param entries Desired entries
     *  @throws Exception if a key is not 'section.name' or 'section.subsection.name'
     */
    static void writeConfig(final StoredConfig config, final List<ConfigEntry> entries) throws Exception
    {
        final Map<String, List<String>> before = group(readConfig(config));
        final Map<String, List<String>> after = group(entries);
        for (String key : before.keySet())
            if (! after.containsKey(key))
            {
                final String[] parts = splitKey(key);
                config.unset(parts[0], parts[1], parts[2]);
            }
        for (Map.Entry<String, List<String>> entry : after.entrySet())
            if (! entry.getValue().equals(before.get(entry.getKey())))
            {
                final String[] parts = splitKey(entry.getKey());
                config.setStringList(parts[0], parts[1], parts[2], entry.getValue());
            }
        config.save();
    }

    /** @param key "section.name" or "section.subsection.name"
     *  @return Section, subsection or <code>null</code>, name
     */
    private static String[] splitKey(final String key) throws Exception
    {
        final int first = key.indexOf('.'), last = key.lastIndexOf('.');
        if (first <= 0  ||  last >= key.length() - 1)
            throw new Exception(Messages.ConfigBadKey + ": " + key);
        return new String[]
        {
            key.substring(0, first),
            first == last ? null : key.substring(first + 1, last),
            key.substring(last + 1)
        };
    }

    /** @param global User configuration, or the one of this repository?
     *  @return Entries
     */
    public synchronized List<ConfigEntry> getConfig(final boolean global) throws Exception
    {
        return readConfig(loadConfig(global));
    }

    /** @param global User configuration, or the one of this repository?
     *  @param entries New entries
     */
    public synchronized void setConfig(final boolean global, final List<ConfigEntry> entries) throws Exception
    {
        writeConfig(loadConfig(global), entries);
    }

    /** Pull from the configured upstream
     *  @param credentials Credentials provider
     */
    public synchronized void pull(final CredentialsProvider credentials) throws Exception
    {
        if (pull(credentials, false))
            throw new Exception("Pull stopped with merge conflicts");
    }

    /** Pull from the configured upstream
     *  @param credentials Credentials provider
     *  @param rebase Rebase local commits onto the upstream instead of merging?
     *  @return <code>true</code> if it stopped with conflicts that need to be resolved
     */
    public synchronized boolean pull(final CredentialsProvider credentials, final boolean rebase) throws Exception
    {
        final var command = git.pull().setRebase(rebase);
        configure(command, credentials);
        final PullResult result = command.call();
        if (result.getMergeResult() != null  &&
            result.getMergeResult().getMergeStatus() == MergeResult.MergeStatus.CONFLICTING)
            return true;
        if (result.getRebaseResult() != null)
            return checkRebase(result.getRebaseResult());
        if (! result.isSuccessful())
            throw new Exception("Pull did not complete\n" + result);
        return false;
    }

    /** @param result Result of rebase operation
     *  @return <code>true</code> if rebase stopped with conflicts
     *  @throws Exception if rebase failed
     */
    private static boolean checkRebase(final RebaseResult result) throws Exception
    {
        return switch (result.getStatus())
        {
            // Conflicts, or 'edit' step of interactive rebase
            case STOPPED, EDIT -> true;
            case OK, UP_TO_DATE, FAST_FORWARD, ABORTED -> false;
            case UNCOMMITTED_CHANGES -> throw new Exception("Rebase needs a clean work tree, commit or stash changes first: "
                                                            + result.getUncommittedChanges());
            case CONFLICTS -> throw new Exception("Rebase cannot start, local changes conflict: " + result.getConflicts());
            case NOTHING_TO_COMMIT -> throw new Exception("Nothing to commit for this step of the rebase. Use 'Skip'");
            default -> throw new Exception("Rebase " + result.getStatus() +
                                           (result.getFailingPaths() != null ? ": " + result.getFailingPaths() : ""));
        };
    }

    /** Rebase the current branch onto another branch
     *  @param upstream Branch, tag or commit to rebase onto
     *  @return <code>true</code> if it stopped with conflicts that need to be resolved
     */
    public synchronized boolean rebase(final String upstream) throws Exception
    {
        return checkRebase(git.rebase().setUpstream(upstream).call());
    }

    /** Continue rebase after resolving and staging conflicts
     *  @return <code>true</code> if it stopped again with conflicts
     */
    public synchronized boolean continueRebase() throws Exception
    {
        return checkRebase(interactive(git.rebase().setOperation(RebaseCommand.Operation.CONTINUE)).call());
    }

    /** Action of an interactive rebase step */
    public enum RebaseAction
    {
        /** Use the commit */
        PICK,
        /** Use the commit, edit its message */
        REWORD,
        /** Use the commit, stop to amend it */
        EDIT,
        /** Combine with previous commit, edit the combined message */
        SQUASH,
        /** Combine with previous commit, keep the previous message */
        FIXUP,
        /** Remove the commit */
        DROP
    }

    /** Step of an interactive rebase
     *  @param action Action
     *  @param commit Commit
     */
    public record RebaseStep(RebaseAction action, CommitInfo commit) {}

    /** Edits commit messages of reword and squash steps, or <code>null</code> to keep them */
    private volatile Function<String, String> message_editor = null;

    /** @param editor Called with a commit message during an interactive rebase,
     *                returns the edited message. Called on a background thread.
     */
    public void setRebaseMessageEditor(final Function<String, String> editor)
    {
        message_editor = editor;
    }

    /** @param command Rebase command
     *  @return Command that uses the message editor
     */
    private RebaseCommand interactive(final RebaseCommand command)
    {
        final Function<String, String> editor = message_editor;
        if (editor == null)
            return command;
        return command.runInteractively(new RebaseCommand.InteractiveHandler()
        {
            @Override
            public void prepareSteps(final List<RebaseTodoLine> steps)
            {
                // Steps were prepared when the rebase started
            }

            @Override
            public String modifyCommitMessage(final String message)
            {
                return editor.apply(message);
            }
        });
    }

    /** @param base Commit after which to start
     *  @return Commits from base (exclusive) to HEAD, oldest first
     *  @throws Exception if there is a merge in between, or base is not an ancestor of HEAD
     */
    public synchronized List<CommitInfo> getCommitsAfter(final String base) throws Exception
    {
        final Repository repository = git.getRepository();
        final List<CommitInfo> result = new ArrayList<>();
        try (RevWalk walk = new RevWalk(repository))
        {
            final RevCommit head = walk.parseCommit(repository.resolve(Constants.HEAD));
            final RevCommit start = walk.parseCommit(ObjectId.fromString(base));
            if (! walk.isMergedInto(start, head))
                throw new Exception(Messages.RebaseNotAncestor);
            walk.reset();
            walk.markStart(head);
            walk.markUninteresting(start);
            for (RevCommit commit : walk)
            {
                if (commit.getParentCount() > 1)
                    throw new Exception(Messages.RebaseMerges + ": " + commit.getShortMessage());
                result.add(0, new CommitInfo(commit.name(), commit.getShortMessage(), commit.getAuthorIdent().getName(),
                                             Instant.ofEpochSecond(commit.getCommitTime()), null));
            }
        }
        return result;
    }

    /** @param ids Commit ids
     *  @return Those commits that are on a remote branch, i.e. have been pushed
     */
    public synchronized Set<String> getPushedCommits(final Collection<String> ids) throws Exception
    {
        final Repository repository = git.getRepository();
        final Set<String> pushed = new HashSet<>();
        try (RevWalk walk = new RevWalk(repository))
        {
            final List<RevCommit> remotes = new ArrayList<>();
            for (Ref ref : repository.getRefDatabase().getRefsByPrefix(Constants.R_REMOTES))
                if (ref.getObjectId() != null)
                    remotes.add(walk.parseCommit(ref.getObjectId()));
            for (String id : ids)
            {
                final RevCommit commit = walk.parseCommit(ObjectId.fromString(id));
                for (RevCommit remote : remotes)
                    if (walk.isMergedInto(commit, remote))
                    {
                        pushed.add(id);
                        break;
                    }
            }
        }
        return pushed;
    }

    /** Interactive rebase: Re-apply commits after a base with the given actions and order
     *  @param base Commit after which the steps start
     *  @param steps Steps, oldest first
     *  @return <code>true</code> if it stopped with conflicts or for an 'edit' step
     */
    public synchronized boolean rebaseInteractive(final String base, final List<RebaseStep> steps) throws Exception
    {
        final Function<String, String> editor = message_editor;
        final RebaseCommand command = git.rebase().setUpstream(base).runInteractively(new RebaseCommand.InteractiveHandler()
        {
            @Override
            public void prepareSteps(final List<RebaseTodoLine> lines)
            {
                lines.clear();
                for (RebaseStep step : steps)
                {
                    if (step.action() == RebaseAction.DROP)
                        continue;
                    lines.add(new RebaseTodoLine(RebaseTodoLine.Action.valueOf(step.action().name()),
                                                 AbbreviatedObjectId.fromString(step.commit().id()),
                                                 step.commit().message()));
                }
            }

            @Override
            public String modifyCommitMessage(final String message)
            {
                return editor == null ? message : editor.apply(message);
            }
        });
        return checkRebase(command.call());
    }

    /** Skip the current commit of the rebase
     *  @return <code>true</code> if it stopped again with conflicts
     */
    public synchronized boolean skipRebase() throws Exception
    {
        return checkRebase(interactive(git.rebase().setOperation(RebaseCommand.Operation.SKIP)).call());
    }

    /** Abort rebase, restoring the original branch */
    public synchronized void abortRebase() throws Exception
    {
        checkRebase(git.rebase().setOperation(RebaseCommand.Operation.ABORT).call());
    }

    /** Push the current branch to the configured remote
     *  @param credentials Credentials provider
     */
    public synchronized void push(final CredentialsProvider credentials) throws Exception
    {
        push(PushOptions.DEFAULT, credentials);
    }

    /** Where a push goes
     *  @param branch Local branch
     *  @param remote Remote
     *  @param remote_branch Branch on the remote
     *  @param has_upstream Does the local branch already track a branch?
     */
    public record PushTarget(String branch, String remote, String remote_branch, boolean has_upstream) {}

    /** @param options Push options
     *  @return Where the push goes: Upstream of the branch on that remote, otherwise a branch of the same name
     */
    public synchronized PushTarget getPushTarget(final PushOptions options) throws Exception
    {
        final Repository repository = git.getRepository();
        final String branch = options.branch() != null ? options.branch() : repository.getBranch();
        if (repository.findRef(Constants.R_HEADS + branch) == null)
            throw new Exception("No local branch '" + branch + "' to push");
        final StoredConfig config = repository.getConfig();
        final String upstream_remote = config.getString("branch", branch, "remote");
        final String upstream_merge = config.getString("branch", branch, "merge");
        final String remote = options.remote() != null ? options.remote()
                            : upstream_remote != null ? upstream_remote
                            : getDefaultRemote();
        final String remote_branch = remote.equals(upstream_remote)  &&  upstream_merge != null
                                   ? Repository.shortenRefName(upstream_merge)
                                   : branch;
        return new PushTarget(branch, remote, remote_branch, upstream_remote != null);
    }

    /** @param options Push options
     *  @return Commits that a push would send, newest first:
     *          Commits of the branch that the remote branch does not have,
     *          or for a new branch, commits that no remote branch has
     */
    public synchronized List<String> getUnpushedCommits(final PushOptions options) throws Exception
    {
        final PushTarget target = getPushTarget(options);
        final Repository repository = git.getRepository();
        final List<String> result = new ArrayList<>();
        try (RevWalk walk = new RevWalk(repository))
        {
            walk.markStart(walk.parseCommit(repository.resolve(Constants.R_HEADS + target.branch())));
            final Ref remote_ref = repository.findRef(Constants.R_REMOTES + target.remote() + "/" + target.remote_branch());
            if (remote_ref != null)
                walk.markUninteresting(walk.parseCommit(remote_ref.getObjectId()));
            else
                for (Ref ref : repository.getRefDatabase().getRefsByPrefix(Constants.R_REMOTES))
                    if (! ref.isSymbolic())
                        walk.markUninteresting(walk.parseCommit(ref.getObjectId()));
            for (RevCommit commit : walk)
                result.add(commit.name());
        }
        return result;
    }

    /** Details of a commit for a logbook entry
     *  @param id Commit id
     *  @param message Complete message
     *  @param author Author name
     *  @param date Commit date
     */
    public record CommitDetails(String id, String message, String author, Instant date) {}

    /** @param id Commit id
     *  @return Details of the commit
     */
    public synchronized CommitDetails getCommitDetails(final String id) throws Exception
    {
        try (RevWalk walk = new RevWalk(git.getRepository()))
        {
            final RevCommit commit = walk.parseCommit(ObjectId.fromString(id));
            return new CommitDetails(commit.name(), commit.getFullMessage().trim(), commit.getAuthorIdent().getName(),
                                     Instant.ofEpochSecond(commit.getCommitTime()));
        }
    }

    /** @param path File path relative to the work tree
     *  @return Is the file in the index, i.e. part of the next commit?
     */
    public synchronized boolean isInIndex(final String path) throws Exception
    {
        return git.getRepository().readDirCache().findEntry(path) >= 0;
    }

    /** Push a branch
     *
     *  <p>Pushes to the branch's upstream if it has one on that remote,
     *  otherwise to a branch of the same name, which then becomes the upstream.
     *
     *  @param options Push options
     *  @param credentials Credentials provider
     */
    public synchronized void push(final PushOptions options, final CredentialsProvider credentials) throws Exception
    {
        final Repository repository = git.getRepository();
        final PushTarget target = getPushTarget(options);
        final String branch = target.branch();
        final String remote = target.remote();
        final String remote_branch = target.remote_branch();
        final StoredConfig config = repository.getConfig();
        final String upstream_remote = target.has_upstream() ? remote : null;
        final String destination = Constants.R_HEADS + remote_branch;
        RefSpec spec = new RefSpec(Constants.R_HEADS + branch + ":" + destination);
        final var command = git.push().setRemote(remote);
        if (options.force())
        {
            // Like 'push --force-with-lease': Only replace what we last fetched
            final ObjectId expected = repository.resolve(Constants.R_REMOTES + remote + "/" + remote_branch);
            spec = spec.setForceUpdate(true);
            command.setRefLeaseSpecs(new RefLeaseSpec(destination,
                                                      expected == null ? ObjectId.zeroId().name() : expected.name()));
        }
        command.setRefSpecs(spec);
        if (options.tags())
            command.setPushTags();
        configure(command, credentials);
        checkPushResults(command.call());

        // New branch: Track the pushed branch
        if (upstream_remote == null)
        {
            config.setString("branch", branch, "remote", remote);
            config.setString("branch", branch, "merge", destination);
            config.save();
        }
    }

    /** Push a tag
     *  @param name Tag name
     *  @param credentials Credentials provider
     */
    public synchronized void pushTag(final String name, final CredentialsProvider credentials) throws Exception
    {
        final String ref = Constants.R_TAGS + name;
        final var command = git.push().setRemote(getDefaultRemote()).setRefSpecs(new RefSpec(ref + ":" + ref));
        configure(command, credentials);
        checkPushResults(command.call());
    }

    /** Delete a tag on the remote, the local tag is kept
     *  @param name Tag name
     *  @param credentials Credentials provider
     */
    public synchronized void deleteRemoteTag(final String name, final CredentialsProvider credentials) throws Exception
    {
        final var command = git.push().setRemote(getDefaultRemote()).setRefSpecs(new RefSpec(":" + Constants.R_TAGS + name));
        configure(command, credentials);
        checkPushResults(command.call());
    }

    /** @return "origin", or the first remote if there is no "origin" */
    private String getDefaultRemote() throws Exception
    {
        final Set<String> remotes = git.getRepository().getRemoteNames();
        if (remotes.isEmpty())
            throw new Exception("No remote configured");
        return remotes.contains(Constants.DEFAULT_REMOTE_NAME)
             ? Constants.DEFAULT_REMOTE_NAME
             : remotes.stream().sorted().findFirst().get();
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
    public synchronized void close()
    {
        git.close();
    }
}
