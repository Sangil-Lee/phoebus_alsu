package org.phoebus.applications.git;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.jgit.api.AddCommand;
import org.eclipse.jgit.api.CheckoutCommand.Stage;
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
import org.eclipse.jgit.api.TransportCommand;
import org.eclipse.jgit.blame.BlameResult;
import org.eclipse.jgit.diff.DiffConfig;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEditor;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.dircache.DirCacheIterator;
import org.eclipse.jgit.lib.BranchTrackingStatus;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.Ref;
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
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefLeaseSpec;
import org.eclipse.jgit.transport.RefSpec;
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
                        List<TagInfo> tags, List<StashInfo> stashes) {}

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
        if (directory.exists()  &&  directory.list().length > 0)
            throw new Exception("Directory is not empty: " + directory);
        final var command = Git.cloneRepository()
                               .setURI(url)
                               .setDirectory(directory)
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
                         getBranches(), getRemoteBranches(), getRemoteUrls(), getTags(), getStashes());
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
                result.add(switch (entry.getChangeType())
                {
                    case ADD    -> new CommitFile(ChangeType.ADDED, entry.getNewPath(), null);
                    case COPY   -> new CommitFile(ChangeType.ADDED, entry.getNewPath(), null);
                    case DELETE -> new CommitFile(ChangeType.DELETED, entry.getOldPath(), null);
                    case RENAME -> new CommitFile(ChangeType.RENAMED, entry.getNewPath(), entry.getOldPath());
                    default     -> new CommitFile(ChangeType.MODIFIED, entry.getNewPath(), null);
                });
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

    /** Apply one hunk to file content
     *
     *  <p>The lines around the change must exactly match,
     *  otherwise the file changed since the diff was computed.
     *
     *  @param content Content of the file on the old side (or new side when reversed)
     *  @param hunk Hunk of a unified diff
     *  @param reverse Apply in reverse, i.e. undo the hunk?
     *  @return Updated content
     *  @throws Exception if the hunk does not match the content
     */
    static byte[] applyHunk(final byte[] content, final HunkHeader hunk, final boolean reverse) throws Exception
    {
        // Lines of the hunk without the '@@' header line
        final List<byte[]> body = splitLines(hunk.getBuffer(), hunk.getStartOffset(), hunk.getEndOffset());
        body.remove(0);
        final List<byte[]> old_lines = new ArrayList<>(), new_lines = new ArrayList<>();
        char last = ' ';
        for (byte[] line : body)
        {
            if (line.length == 0)
                continue;
            final char type = (char) line[0];
            final byte[] text = java.util.Arrays.copyOfRange(line, 1, line.length);
            switch (type)
            {
            case ' ':
                old_lines.add(text);
                new_lines.add(text);
                break;
            case '-':
                old_lines.add(text);
                break;
            case '+':
                new_lines.add(text);
                break;
            case '\\':
                // "\ No newline at end of file" applies to the preceding line
                if (last == ' '  ||  last == '-')
                    stripNewline(old_lines);
                if (last == ' '  ||  last == '+')
                    stripNewline(new_lines);
                break;
            default:
                // End of hunk, for example the next 'diff --git'
                break;
            }
            if (type == ' '  ||  type == '-'  ||  type == '+')
                last = type;
        }

        final List<byte[]> from = reverse ? new_lines : old_lines;
        final List<byte[]> to   = reverse ? old_lines : new_lines;
        final int start = reverse ? hunk.getNewStartLine() : hunk.getOldImage().getStartLine();
        final int count = reverse ? hunk.getNewLineCount() : hunk.getOldImage().getLineCount();
        // For an empty range, the start is the line after which to insert
        final int position = count == 0 ? start : start - 1;

        final List<byte[]> lines = splitLines(content, 0, content.length);
        if (position < 0  ||  position + from.size() > lines.size())
            throw new Exception("The file changed, refresh and try again");
        for (int i=0; i<from.size(); ++i)
            if (! java.util.Arrays.equals(lines.get(position + i), from.get(i)))
                throw new Exception("The file changed, refresh and try again");

        final ByteArrayOutputStream out = new ByteArrayOutputStream(content.length);
        for (int i=0; i<position; ++i)
            out.write(lines.get(i));
        for (byte[] line : to)
            out.write(line);
        for (int i=position + from.size(); i<lines.size(); ++i)
            out.write(lines.get(i));
        return out.toByteArray();
    }

    private static void stripNewline(final List<byte[]> lines)
    {
        if (lines.isEmpty())
            return;
        final byte[] line = lines.get(lines.size() - 1);
        if (line.length > 0  &&  line[line.length - 1] == '\n')
            lines.set(lines.size() - 1, java.util.Arrays.copyOf(line, line.length - 1));
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
        final Repository repository = git.getRepository();
        final DirCacheEntry entry = getIndexEntry(path);
        final byte[] diff = getRawDiff(path, new DirCacheIterator(repository.readDirCache()), new FileTreeIterator(repository));
        final byte[] content = applyHunk(getContent(entry), getHunk(diff, hunk), false);
        writeIndex(path, content, entry == null ? FileMode.REGULAR_FILE : entry.getFileMode());
    }

    /** Unstage one hunk of the staged changes
     *  @param path File path
     *  @param hunk Index of the hunk in the staged diff of the file
     */
    public synchronized void unstageHunk(final String path, final int hunk) throws Exception
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
        final byte[] content = applyHunk(getContent(entry), getHunk(diff, hunk), true);
        writeIndex(path, content, entry.getFileMode());
    }

    /** Discard one hunk of the unstaged changes in the work tree
     *  @param path File path
     *  @param hunk Index of the hunk in the unstaged diff of the file
     */
    public synchronized void discardHunk(final String path, final int hunk) throws Exception
    {
        final Repository repository = git.getRepository();
        final byte[] diff = getRawDiff(path, new DirCacheIterator(repository.readDirCache()), new FileTreeIterator(repository));
        final Path file = new File(getWorkTree(), path).toPath();
        final byte[] content = applyHunk(Files.readAllBytes(file), getHunk(diff, hunk), true);
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
            final var command = git.fetch().setRemote(remote).setRemoveDeletedRefs(true);
            configure(command, credentials);
            command.call();
        }
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
            case STOPPED -> true;
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
        return checkRebase(git.rebase().setOperation(RebaseCommand.Operation.CONTINUE).call());
    }

    /** Skip the current commit of the rebase
     *  @return <code>true</code> if it stopped again with conflicts
     */
    public synchronized boolean skipRebase() throws Exception
    {
        return checkRebase(git.rebase().setOperation(RebaseCommand.Operation.SKIP).call());
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
