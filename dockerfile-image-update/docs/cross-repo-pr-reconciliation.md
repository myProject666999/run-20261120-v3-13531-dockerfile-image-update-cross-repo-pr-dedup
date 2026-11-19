# Cross-repository Dockerfile updates: reconciliation design

One base image is referenced by dozens of repositories. When its tag moves, the tool must propose
a reviewable change to each affected repository. The hard part is not the first proposal — it is
every run after that.

This document records the decisions implemented in
`com.salesforce.dockerfileimageupdate.reconcile`, the rationale, and what each decision costs.

## Vocabulary

| Term | Meaning |
| --- | --- |
| identity | A canonical image name, e.g. `docker.io/library/nginx`. Tags are never part of identity. |
| group | (repository, identity). One group produces at most one branch and one request. |
| plan | The unit of work: a repository, one or more identity/tag pairs, and their candidate files. |
| tool-only branch | A branch on which every commit was authored by this tool. |

## 1. Image identity

`nginx`, `library/nginx`, `docker.io/nginx`, `docker.io/library/nginx` and
`index.docker.io/library/nginx` are the same image. If the tool treated them as different, it would
open several requests for one update and re-propose the same change on every run.

`ImageRef.parse` normalizes every name to `registry/namespace/name`, always spelling out
`docker.io`. That one function is used by grouping, branch naming, and file-line matching, so the
three can never disagree. Matching inside a file goes through the same normalizer rather than a
substring check, which is what makes `FROM nginx:1.25` update correctly when the request was worded
`docker.io/library/nginx:1.27`.

## 2. Deterministic branch names

A branch name is a pure function of (repository, normalized image): `dfiu/<slug>`, or
`dfiu/<truncated slug>-<sha256>` when the slug would exceed git's limits. It contains no timestamp,
counter, or random suffix.

This is what lets a rerun find the work of the previous run instead of starting over. It is also
why the same race outcome is always possible: two concurrent executors compute the same name by
construction (see §7).

The readable form is preferred; hashing is only the overflow path, and it hashes repository plus
identity so two repositories with the same very long image name still get distinct branches.

## 3. What to do with an existing branch

| Platform state | Action | Why |
| --- | --- | --- |
| Branch content already equals the target, request open | `BRANCH_ALREADY_CURRENT`, no writes | Idempotent rerun. |
| Branch content already equals the target, request merged | `EXPIRED_AFTER_MERGE`; delete the branch if tool-only | The work landed; the branch is spent. |
| Branch content already equals the target, request closed | `SKIPPED_CLOSED_SAME_TAG` | Respect the close. |
| Branch content already equals the target, no request | `RESUMED_INCOMPLETE_WORK` | A run died between commit and request creation. |
| Branch has an older tag | Append a commit, refresh the open request's title/body | The target stays explicit and the request count stays one. |
| Request closed for the same target | `SKIPPED_CLOSED_SAME_TAG` | A close is a human decision. Re-proposing the identical change is noise. |
| Request closed for a different target | `RESUBMITTED_AFTER_CLOSE` | The new tag is information the maintainers have not rejected. |
| Request merged, branch still present | `EXPIRED_AFTER_MERGE`, branch not reused | Reusing it would produce an empty or confusing request. |

Closed requests are matched on the target recorded in the request body (a machine-readable
`<!-- dfiu-plan: ... -->` marker), not on the title, so editing a title does not defeat the policy.
The marker is what makes "same change" and "different change" decidable across runs.

The choice to honor closes — rather than reopen — is deliberate. A closed request is the only
unambiguous negative signal available; re-proposing makes the tool fight maintainers and destroys
trust in the noise. The concession is that a maintainer who closes by mistake will not be
automatically re-asked for that same tag; a *newer* tag reopens the conversation legitimately.

## 4. Branches behind the default branch

Two options were considered: force-push a rebuild, or abandon the branch and open a new one.

Chosen: **rebuild only when the branch is tool-only.** If every commit on the branch came from this
tool, there is nothing to lose, so the branch is reset onto the current default-branch head with the
desired file content. If any commit is foreign, the branch is left untouched and reported as
`LEFT_UNTOUCHED_FOREIGN_COMMITS` with the count of foreign commits.

Rationale: force-pushing over a colleague's commit is unrecoverable from the maintainer's point of
view — the commit object survives, but the branch no longer points at it and nobody is told. The
alternative — start a new branch — costs an extra branch and request, and would duplicate a request
that may already be open. Doing neither and reporting is the only option that loses nothing; a human
then decides whether to rebase the branch or close the request.

Cleaning up a merged tool-only branch (deletion) is the same policy in reverse: only remove history
this tool created.

## 5. Files and grouping

Two dimensions:

* **Several Dockerfiles, one image, one repository.** They become one branch and one request. This is
  the common case and there is no reason to split it: the maintainer reviews one change.
* **Two images, one repository.** These become two requests, so a rejection of one does not hold back
  the other with a shared branch. But two requests that edit the same file conflict the moment the
  first merges.

The two ways to handle that conflict, and their trade-offs:

| Approach | Strength | Cost | Suits |
| --- | --- | --- | --- |
| Re-apply the second change after the first merges | Full independence; a rejected image never blocks another | The second request is conflicting and unmergeable until the tool notices the merge and rewrites its branch; needs a trigger (a later run or a merge hook) and briefly ships a broken request | Many images per repository, spread across separate files; merge cadence matters more than a clean diff |
| Detect the shared file at grouping time; combine into one request | Never conflicts; the repository stays buildable; one review for one document | A rejection now blocks both images | Images edited in the same file (one Dockerfile with several `FROM`s, one compose file) |

This implementation uses the second for overlapping files: plans whose file sets intersect in a
repository are merged into one plan, one branch, one request. Non-overlapping images stay separate,
which is exactly where independence pays off. The scheduler additionally never runs two plans of the
same repository that share a file at the same time (`conflictFreeRounds`), so even a future split
policy cannot interleave writes.

The "re-apply later" half of the first option is still present for the drift case: a tool-only branch
that fell behind is rebuilt on the current default head (§4), which is what re-application reduces to
when there is nothing foreign to preserve.

## 6. Request descriptions

The body always lists the image(s), the branch, the previous tags found in the files, the new tag(s),
and the changed files. It is regenerated wholesale on every update — never appended to — so reusing a
branch across tags does not accumulate one "summary section" per run. The first line is the
machine-readable plan marker (§3).

## 7. Concurrency and failure isolation

* One failing repository must not stop the batch. Each plan runs on a pool; any exception, including a
  platform error, becomes a `FAILED` result. No exception escapes to abort other plans.
* A rerun retries failures and skips successes: a repository whose plan is already satisfied performs
  no writes at all (asserted by counting writes on the fake host).
* Branches, not memory, are the record of progress. A branch without a request is resumed; a request
  whose content is stale is updated. Neither case creates a duplicate.
* **The same-branch race.** Two executors both observe "branch absent", both create it, one receives
  the platform's already-exists error. That error is not a repository failure; it means someone else
  created the branch, so the reconciler reloads state and continues on the existing-branch path. The
  test forces this with a barrier inside the fake host's `createBranch`, so both threads genuinely
  pass the existence check before either creates. The same tolerance is applied when creating the
  request, since two executors can also both decide to open it.
* A renamed default branch is read from the host on every plan (`getDefaultBranch`), never assumed to
  be `main` or `master`.

## 8. The in-memory platform

`InMemoryCodeHost` (test sources) stores repositories, branches, requests and their states, counts
every interface call (reads and writes separately), supports failure injection per operation and
repository, and exposes a creation fence. Merge and close are driven by the test directly, which is
how the "closed" and "merged" scenarios are produced.

Scenarios asserted:

| Scenario | Key assertions |
| --- | --- |
| First run | 1 branch, 1 request, title and body carry the new tag and old tags, one create + one commit write |
| Identical rerun | zero writes, unchanged counts |
| Newer tag | still 1 branch + 1 request, second commit, title/body refreshed, body has one summary section |
| Closed for same tag | no writes, no new request |
| Closed for newer tag | second request opens, references the closed one |
| Merged | branch cleaned (tool-only), no duplicate request on rerun |
| No changes | no branch, no commit |
| Several files, one image | one branch, one request, all files listed |
| Two images, disjoint files | two branches, two requests |
| Two images, shared file | one request, both tags in body, no conflict possible |
| Renamed default branch | run succeeds across a rename between runs |
| Branch created but commit failed | next run resumes; no second branch |
| Commit ok, request failed | next run creates only the missing request; no orphan |
| One repo failing in a parallel batch | others fully created; failure reported |
| Parallel rerun | zero create-branch writes for successful repositories, one create-request for the failed one |
| Forced same-branch race | both threads pass the check, one conflict, one branch, one request, no failure |

Remaining gap: the fake platform models content, not git history. Fast-forwardability is
approximated by "the branch's base equals the current default head", which is enough to exercise the
drift policy but does not exercise real merge conflicts.
