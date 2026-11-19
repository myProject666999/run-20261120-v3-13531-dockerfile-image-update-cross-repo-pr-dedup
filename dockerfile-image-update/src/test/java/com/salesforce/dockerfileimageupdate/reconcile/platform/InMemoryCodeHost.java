package com.salesforce.dockerfileimageupdate.reconcile.platform;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory stand-in for a code host. It stores repositories, branches, pull requests and their
 * states, counts every interface call, can inject failures per operation, and can force the
 * "same branch created twice" race with a fence.
 *
 * <p>Only for tests. Not thread-safe for reads during a write; the fence and the write lock are
 * enough for the concurrency scenarios this models.</p>
 */
public class InMemoryCodeHost implements CodeHost, com.salesforce.dockerfileimageupdate.reconcile.BranchReconciler.BranchDeleter {

    /** Operations that change platform state. A read-only rerun must perform none of them. */
    public enum Write {
        CREATE_BRANCH,
        RESET_BRANCH,
        COMMIT_TO_BRANCH,
        CREATE_PULL_REQUEST,
        UPDATE_PULL_REQUEST,
        DELETE_BRANCH,
        MERGE_PULL_REQUEST,
        CLOSE_PULL_REQUEST
    }

    /** Operations that only read platform state. */
    public enum Read {
        GET_DEFAULT_BRANCH,
        READ_FILE,
        GET_BRANCH,
        FIND_PULL_REQUESTS
    }

    public static final String TOOL_AUTHOR = "dockerfile-image-update";

    private final Object lock = new Object();
    private final Map<String, Repo> repos = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    private final Map<String, List<PlatformException>> injectedFailures = new ConcurrentHashMap<>();
    private final AtomicInteger shaCounter = new AtomicInteger();
    private final AtomicInteger prCounter = new AtomicInteger();
    private volatile java.util.concurrent.CyclicBarrier createFence;

    // ---------------------------------------------------------------- setup helpers

    /** Register a repository with a default branch holding {@code files}. */
    public InMemoryCodeHost addRepo(String name, Map<String, String> files) {
        return addRepo(name, "main", files);
    }

    public InMemoryCodeHost addRepo(String name, String defaultBranch, Map<String, String> files) {
        Repo repo = new Repo(defaultBranch);
        repo.defaultSha = nextSha();
        repo.defaultFiles.putAll(files);
        repo.ancestors.add(repo.defaultSha);
        repos.put(name, repo);
        return this;
    }

    /** Rename a repository's default branch, to prove runs do not assume "main". */
    public InMemoryCodeHost renameDefaultBranch(String repoName, String newName) {
        Repo repo = repo(repoName);
        synchronized (lock) {
            repo.defaultBranch = newName;
        }
        return this;
    }

    /** Add a commit to the default branch, simulating upstream work between runs. */
    public InMemoryCodeHost commitToDefault(String repoName, Map<String, String> changedFiles) {
        Repo repo = repo(repoName);
        synchronized (lock) {
            repo.defaultFiles.putAll(changedFiles);
            repo.defaultSha = nextSha();
            repo.ancestors.add(repo.defaultSha);
        }
        return this;
    }

    /** Add a commit authored by a human to a branch, to test the "foreign commits" policy. */
    public InMemoryCodeHost addForeignCommit(String repoName, String branchName, Map<String, String> files) {
        Repo repo = repo(repoName);
        synchronized (lock) {
            Branch branch = repo.branches.get(branchName);
            if (branch == null) {
                throw new IllegalArgumentException("No such branch: " + branchName);
            }
            branch.files.putAll(files);
            branch.foreignCommits++;
            branch.headSha = nextSha();
        }
        return this;
    }

    /** Mark a pull request merged and put its content on the default branch. */
    public InMemoryCodeHost mergePullRequest(String repoName, long number) throws PlatformException {
        Repo repo = repo(repoName);
        synchronized (lock) {
            PullRequest pr = repo.pullRequests.get(number);
            require(pr != null, "No such pull request: " + number);
            Branch branch = repo.branches.get(pr.headBranch);
            repo.defaultFiles.putAll(branch.files);
            repo.defaultSha = nextSha();
            repo.ancestors.add(repo.defaultSha);
            pr.status = PullRequestState.Status.MERGED;
            count(Write.MERGE_PULL_REQUEST.name());
        }
        return this;
    }

    /** Close a pull request without merging, as a maintainer declining the change. */
    public InMemoryCodeHost closePullRequest(String repoName, long number) throws PlatformException {
        Repo repo = repo(repoName);
        synchronized (lock) {
            PullRequest pr = repo.pullRequests.get(number);
            require(pr != null, "No such pull request: " + number);
            pr.status = PullRequestState.Status.CLOSED;
            count(Write.CLOSE_PULL_REQUEST.name());
        }
        return this;
    }

    /** Make the next {@code times} calls of {@code write} on {@code repo} fail. */
    public InMemoryCodeHost failOn(Write write, String repo, int times, String message) {
        List<PlatformException> failures = injectedFailures.computeIfAbsent(key(write, repo),
                k -> Collections.synchronizedList(new ArrayList<PlatformException>()));
        for (int i = 0; i < times; i++) {
            failures.add(new PlatformException(message));
        }
        return this;
    }

    /** Make the next {@code times} reads of {@code read} on {@code repo} fail. */
    public InMemoryCodeHost failOnRead(Read read, String repo, int times, String message) {
        List<PlatformException> failures = injectedFailures.computeIfAbsent(key(read, repo),
                k -> Collections.synchronizedList(new ArrayList<PlatformException>()));
        for (int i = 0; i < times; i++) {
            failures.add(new PlatformException(message));
        }
        return this;
    }

    /**
     * Arm a fence that every {@link #createBranch} call must pass through before it takes the
     * write lock. Two threads that both observed "branch absent" will both enter, then serialize,
     * so exactly one creates and the other gets {@link BranchAlreadyExistsException}.
     */
    public InMemoryCodeHost armCreateBranchFence(int parties) {
        this.createFence = new java.util.concurrent.CyclicBarrier(parties);
        return this;
    }

    public void disarmCreateBranchFence() {
        this.createFence = null;
    }

    // ---------------------------------------------------------------- inspection helpers

    public int writes() {
        int total = 0;
        for (Write write : Write.values()) {
            total += countOf(write.name());
        }
        return total;
    }

    public int writeCount(Write write) {
        return countOf(write.name());
    }

    public int readCount(Read read) {
        return countOf(read.name());
    }

    public int callCount(String operation) {
        return countOf(operation);
    }

    public int branchCount(String repoName) {
        return repo(repoName).branches.size();
    }

    public int pullRequestCount(String repoName) {
        return repo(repoName).pullRequests.size();
    }

    public List<PullRequestState> pullRequests(String repoName) {
        Repo repo = repo(repoName);
        List<PullRequestState> result = new ArrayList<>();
        for (PullRequest pr : new TreeMap<>(repo.pullRequests).values()) {
            result.add(pr.toState());
        }
        return result;
    }

    public PullRequestState.Status pullRequestStatus(String repoName, long number) {
        return repo(repoName).pullRequests.get(number).status;
    }

    public int toolCommitCount(String repoName, String branchName) {
        return repo(repoName).branches.get(branchName).toolCommits;
    }

    public int foreignCommitCount(String repoName, String branchName) {
        return repo(repoName).branches.get(branchName).foreignCommits;
    }

    public boolean hasBranch(String repoName, String branchName) {
        return repo(repoName).branches.containsKey(branchName);
    }

    public String fileOn(String repoName, String branchName, String path) {
        Branch branch = repo(repoName).branches.get(branchName);
        return branch == null ? null : branch.files.get(path);
    }

    public void resetCounters() {
        calls.clear();
    }

    // ---------------------------------------------------------------- CodeHost

    @Override
    public String getDefaultBranch(String repoName) throws PlatformException {
        read(Read.GET_DEFAULT_BRANCH, repoName);
        return repo(repoName).defaultBranch;
    }

    @Override
    public String readFile(String repoName, String ref, String path) throws PlatformException {
        read(Read.READ_FILE, repoName);
        Repo repo = repo(repoName);
        synchronized (lock) {
            Map<String, String> files;
            if (ref.equals(repo.defaultBranch)) {
                files = repo.defaultFiles;
            } else {
                Branch branch = repo.branches.get(ref);
                files = branch == null ? null : branch.files;
            }
            return files == null ? null : files.get(path);
        }
    }

    @Override
    public BranchState getBranch(String repoName, String branchName) throws PlatformException {
        read(Read.GET_BRANCH, repoName);
        Repo repo = repo(repoName);
        synchronized (lock) {
            Branch branch = repo.branches.get(branchName);
            if (branch == null) {
                return null;
            }
            boolean toolOnly = branch.foreignCommits == 0;
            boolean fastForwardable = repo.ancestors.contains(branch.baseSha)
                    && branch.baseSha.equals(repo.defaultSha);
            return new BranchState(branchName, repo.defaultSha, branch.headSha,
                    toolOnly ? BranchState.Provenance.TOOL_ONLY : BranchState.Provenance.HAS_FOREIGN_COMMITS,
                    branch.foreignCommits, fastForwardable);
        }
    }

    @Override
    public void createBranch(String repoName, String branchName) throws PlatformException {
        awaitCreateFence();
        write(Write.CREATE_BRANCH, repoName);
        Repo repo = repo(repoName);
        synchronized (lock) {
            if (repo.branches.containsKey(branchName)) {
                throw new BranchAlreadyExistsException(
                        "Reference already exists: refs/heads/" + branchName);
            }
            Branch branch = new Branch(branchName);
            branch.baseSha = repo.defaultSha;
            branch.headSha = repo.defaultSha;
            branch.files.putAll(repo.defaultFiles);
            repo.branches.put(branchName, branch);
        }
    }

    @Override
    public void resetBranchToContent(String repoName, String branchName, List<FileChange> files)
            throws PlatformException {
        write(Write.RESET_BRANCH, repoName);
        Repo repo = repo(repoName);
        synchronized (lock) {
            Branch branch = repo.branches.get(branchName);
            require(branch != null, "No such branch: " + branchName);
            if (branch.foreignCommits > 0) {
                throw new PlatformException("Refusing to force-push over foreign commits on " + branchName);
            }
            branch.files.clear();
            branch.files.putAll(repo.defaultFiles);
            for (FileChange change : files) {
                branch.files.put(change.getPath(), change.getContent());
            }
            branch.baseSha = repo.defaultSha;
            branch.headSha = nextSha();
            branch.toolCommits++;
        }
    }

    @Override
    public void commitToBranch(String repoName, String branchName, List<FileChange> files, String message)
            throws PlatformException {
        write(Write.COMMIT_TO_BRANCH, repoName);
        Repo repo = repo(repoName);
        synchronized (lock) {
            Branch branch = repo.branches.get(branchName);
            require(branch != null, "No such branch: " + branchName);
            for (FileChange change : files) {
                branch.files.put(change.getPath(), change.getContent());
            }
            branch.headSha = nextSha();
            branch.toolCommits++;
        }
    }

    @Override
    public List<PullRequestState> findPullRequests(String repoName, String branchName) throws PlatformException {
        read(Read.FIND_PULL_REQUESTS, repoName);
        Repo repo = repo(repoName);
        List<PullRequestState> result = new ArrayList<>();
        synchronized (lock) {
            for (PullRequest pr : new TreeMap<>(repo.pullRequests).values()) {
                if (pr.headBranch.equals(branchName)) {
                    result.add(pr.toState());
                }
            }
        }
        return result;
    }

    @Override
    public long createPullRequest(String repoName, String headBranch, String title, String body)
            throws PlatformException {
        write(Write.CREATE_PULL_REQUEST, repoName);
        Repo repo = repo(repoName);
        synchronized (lock) {
            require(repo.branches.containsKey(headBranch), "No such branch: " + headBranch);
            for (PullRequest existing : repo.pullRequests.values()) {
                if (existing.headBranch.equals(headBranch) && existing.status == PullRequestState.Status.OPEN) {
                    throw new PlatformException("A pull request already exists for " + headBranch);
                }
            }
            long number = prCounter.incrementAndGet();
            repo.pullRequests.put(number, new PullRequest(number, headBranch, title, body));
            return number;
        }
    }

    @Override
    public void updatePullRequest(long number, String title, String body) throws PlatformException {
        String owner = null;
        synchronized (lock) {
            for (Map.Entry<String, Repo> entry : repos.entrySet()) {
                if (entry.getValue().pullRequests.containsKey(number)) {
                    owner = entry.getKey();
                    break;
                }
            }
        }
        write(Write.UPDATE_PULL_REQUEST, owner);
        for (Repo repo : repos.values()) {
            synchronized (lock) {
                PullRequest pr = repo.pullRequests.get(number);
                if (pr != null) {
                    pr.title = title;
                    pr.body = body;
                    return;
                }
            }
        }
        throw new PlatformException("No such pull request: " + number);
    }

    @Override
    public void deleteBranch(String repoName, String branchName) throws PlatformException {
        write(Write.DELETE_BRANCH, repoName);
        Repo repo = repo(repoName);
        synchronized (lock) {
            repo.branches.remove(branchName);
        }
    }

    // ---------------------------------------------------------------- internals

    private void awaitCreateFence() throws PlatformException {
        java.util.concurrent.CyclicBarrier fence = createFence;
        if (fence == null) {
            return;
        }
        try {
            fence.await(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new PlatformException("Timed out at create-branch fence", e);
        }
    }

    private void read(Read read, String repoName) throws PlatformException {
        count(read.name());
        consumeFailure(read, repoName);
    }

    private void write(Write write, String repoName) throws PlatformException {
        count(write.name());
        consumeFailure(write, repoName);
    }

    private void consumeFailure(Enum<?> operation, String repoName) throws PlatformException {
        List<PlatformException> failures = injectedFailures.get(key(operation, repoName));
        if (failures == null || failures.isEmpty()) {
            return;
        }
        synchronized (failures) {
            if (!failures.isEmpty()) {
                throw failures.remove(0);
            }
        }
    }

    private static String key(Enum<?> operation, String repoName) {
        return operation.name() + "@" + (repoName == null ? "*" : repoName);
    }

    private void count(String operation) {
        calls.computeIfAbsent(operation, k -> new AtomicInteger()).incrementAndGet();
    }

    private int countOf(String operation) {
        AtomicInteger counter = calls.get(operation);
        return counter == null ? 0 : counter.get();
    }


    private String nextSha() {
        return "sha-" + shaCounter.incrementAndGet();
    }

    private Repo repo(String name) {
        Repo repo = repos.get(name);
        if (repo == null) {
            throw new IllegalArgumentException("No such repository: " + name);
        }
        return repo;
    }

    private static void require(boolean condition, String message) throws PlatformException {
        if (!condition) {
            throw new PlatformException(message);
        }
    }

    private static final class Repo {
        private String defaultBranch;
        private String defaultSha;
        private final Map<String, String> defaultFiles = new LinkedHashMap<>();
        private final Set<String> ancestors = new LinkedHashSet<>();
        private final Map<String, Branch> branches = new LinkedHashMap<>();
        private final Map<Long, PullRequest> pullRequests = new TreeMap<>();

        private Repo(String defaultBranch) {
            this.defaultBranch = defaultBranch;
        }
    }

    private static final class Branch {
        private final String name;
        private String headSha;
        private String baseSha;
        private int toolCommits;
        private int foreignCommits;
        private final Map<String, String> files = new LinkedHashMap<>();

        private Branch(String name) {
            this.name = name;
        }
    }

    private static final class PullRequest {
        private final long number;
        private final String headBranch;
        private String title;
        private String body;
        private PullRequestState.Status status = PullRequestState.Status.OPEN;

        private PullRequest(long number, String headBranch, String title, String body) {
            this.number = number;
            this.headBranch = headBranch;
            this.title = title;
            this.body = body;
        }

        private PullRequestState toState() {
            return new PullRequestState(number, headBranch, status, title, body);
        }
    }
}
