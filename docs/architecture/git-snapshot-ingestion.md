# Git repository snapshot ingestion

M1.2 implements the first executable Repository Intelligence capability:
`GitSnapshotIngestor`, a Spring component in `dev.changeassurance.ingestion` that
can also be constructed directly. It reads committed Java/Markdown source material
and reports coverage. It adds no HTTP endpoint, startup scan, persistence, syntax
parsing, chunking, search, retrieval, embeddings, or question answering.

This implements the snapshot/artifact parts of the
[Repository Intelligence contracts](repository-intelligence.md) within the existing
modular monolith. The existing ADRs remain unchanged.

## Calling the component

Use Java 21 and a trusted Git executable on `PATH`, version **2.48 or newer**.
The CLI must support `--no-lazy-fetch`; older versions fail explicitly rather than
silently permitting downloads. Integration tests require the same Git prerequisite.
The caller supplies a logical repository identity; the component does not infer
identity or ownership from a remote URL. The caller must be authorized to read it.

```java
import dev.changeassurance.ingestion.GitSnapshotIngestor;
import dev.changeassurance.ingestion.RepositorySelection;
import java.nio.file.Path;
import java.util.List;

var selection = new RepositorySelection(
        Path.of("/authorized/local/repository"),
        "example-repository",
        "HEAD", // A full commit ID, branch, tag, or commit-valued Git revision also works.
        new RepositorySelection.Scope(List.of("src", "docs"), List.of("docs/drafts")));
var result = new GitSnapshotIngestor().ingest(selection);
var snapshot = result.snapshot();
var artifacts = result.artifacts();
var coverage = result.coverage();
var counts = result.summary();
```

The three-argument `RepositorySelection(path, identity, revision)` selects all
paths subject to default exclusions. Scope lists are copied, deduplicated, and
sorted. Includes/excludes are literal, case-sensitive relative file paths or
directory prefixes, not glob patterns or Git pathspecs. Empty includes means all;
excludes win. A prefix matches itself and descendants separated by `/`, never a
similar name such as `src2`. Nonmatching includes produce an empty ingested list
with outside-scope coverage entries. Scope permits at most 100 prefixes per list,
4096 characters per prefix; revision expressions are capped at 1024 characters.

## Snapshot resolution and provenance

The supplied path must resolve to an accessible repository root with an ordinary
`.git` directory. Subdirectory selection, bare repositories, `.git` indirection
files/linked worktrees, and alternate/shared object stores are not supported.
Shallow/partial repositories can supply locally available objects only; an
unavailable revision/tree fails, and a missing blob is a per-file failure.

`rev-parse --verify --end-of-options <revision>^{commit}` resolves the selection
once to a full commit ID. Every subsequent read uses that ID or blob IDs from its
tree. Staged, unstaged, deleted working-tree files, and untracked changes are
ignored. Nothing is checked out or synchronized. Replacement refs are disabled,
so they cannot substitute content beneath an otherwise unchanged commit ID.

`ls-tree -r -z --full-tree` enumerates leaves, including gitlinks, with NUL-delimited
paths. Entries are sorted by Java string order of the exact UTF-8-decoded relative
path. Regular blobs are read with `cat-file -s` and `cat-file blob <object-id>`;
source paths are never opened in the working tree or passed as Git pathspecs.

The immutable result contains:

- `Snapshot`: caller identity, resolved commit, canonical scope, and selection
  policy version `git-snapshot-v1`. This tuple identifies the selected snapshot
  and its ingestion policy independently of a mutable clone location.
- `SourceArtifact`: snapshot provenance, path, source/test/documentation type,
  Java/Markdown format, Git blob ID, SHA256 of original bytes, and source text.
  Git blob digests (SHA-1 or SHA-256 according to repository object format) and
  reported sizes are also checked against the returned bytes.
- `CoverageEntry`: path, excluded/unsupported/failed disposition, reason, and
  diagnostic. These entries inherit the enclosing result's snapshot provenance.
- `Summary`: discovered, ingested, excluded, unsupported, and failed counts derived
  from the result lists, so discovered equals the sum of the other four counts.
- `limitations`: explicit coverage and interpretation boundaries.

## Selection and text policy

Every enumerated leaf has exactly one successful artifact or coverage entry.
Directories themselves are not counted; a submodule counts as one gitlink, with
no claim to have discovered its contents. Selection precedence is the table order.

| Condition | Outcome |
| --- | --- |
| Unsafe tree path | Failed: absolute, empty/dot/dot-dot components, `.git` components, backslashes, colons, or control characters |
| Outside includes or inside excludes | Excluded with the matching scope reason |
| Directory component `target`, `build`, `out`, `node_modules`, `vendor`, `.gradle` | Excluded as generated/dependency content, even if explicitly included |
| Symlink (mode 120000) | Unsupported; link content/target is not followed |
| Submodule (mode 160000) | Unsupported; no recursive ingestion |
| Other than regular `.java` or `.md` file | Unsupported file type; suffix matching is case-sensitive |
| Blob over 1 MiB, or would exceed 16 MiB retained source bytes | Excluded with the applicable resource-limit reason |
| Missing/unreadable/corrupt blob or failed blob command | Failed with Git/size/digest diagnostic |
| Invalid UTF-8 (including UTF-16/legacy encoding) | Unsupported encoding; no replacement decoding |
| UTF-8 containing control characters other than TAB/LF/CR | Unsupported binary content |
| Other supported regular file | Ingested |

Unsafe scope inputs are rejected before Git execution. Valid spaces, Unicode, and
leading hyphens in tree filenames remain literal. Non-UTF-8 tree paths cause a
snapshot-level failure rather than manufacturing replacement-character paths.

UTF-8 decoding preserves BOM, CRLF/LF, whitespace, and the final-newline state.
`contentBytes()` returns a fresh byte array reproducing the original UTF-8 blob.
There is no charset detection or normalization. Binary detection is a conservative
control-character rule, not universal format recognition. Java files under a path
component named `test` or `tests` are classified as tests; this heuristic does not
prove test framework use, execution, or success. `.gitignore`, generated-file
headers, build configuration, and language syntax are not analyzed. LFS pointer
files remain committed pointer text; LFS content is never downloaded or expanded.

## Git CLI decision and execution bounds

Git CLI was chosen over JGit because three built-in plumbing commands provide the
needed object access without another dependency. JGit would avoid subprocesses
and the installed-Git prerequisite but add a library and its maintenance surface.
For this bounded local capability, a small process runner is sufficient; there is
no generic Git adapter framework. The tradeoffs are process startup overhead and
requiring an installed supported Git version. See Git's official
[revision resolution](https://git-scm.com/docs/git-rev-parse),
[tree format](https://git-scm.com/docs/git-ls-tree),
[blob access](https://git-scm.com/docs/git-cat-file), and
[global safety options](https://git-scm.com/docs/git/2.48.0) documentation.

`ProcessBuilder` receives argument lists, never shell strings. Production only
runs `rev-parse`, `ls-tree`, and `cat-file`. It disables replacement objects, lazy
fetching, optional locks, hooks, filesystem monitors, and automatic maintenance;
network/transport protocols are denied. Inherited `GIT_*` overrides are removed,
and global/system Git configuration is disabled. Reads do not apply filters,
smudge commands, or text conversion. No checkout/reset/fetch/clone/clean commands
are used by ingestion. Tests alone create commits in temporary fixture repositories.

Each process has a 10-second timeout, stdout bounded to the operation's needs,
and stderr bounded to 16 KiB. Both streams are drained concurrently; output
limits terminate the child. Processes and streams are cleaned up on success,
failure, timeout, and interruption; interruption status is preserved. Descendant
cleanup is best effort when the OS permits enumeration; direct children are
always terminated. The permitted Git plumbing commands do not require helpers.

A snapshot has a 60-second execution budget, a 4 MiB tree-listing cap, and a
10,000-leaf cap. These are deliberate initial limits, not a scalable repository
index. Successful original source bytes retained in memory total at most 16 MiB;
Java strings, metadata, diagnostics, and bounded transient buffers add overhead.
A large selected repository can fail the whole-tree limits even with a narrow
scope because coverage inventories the entire tree before filtering.

Invalid selection throws `IllegalArgumentException`; repository, revision, tree,
process startup, and overall-budget failures throw `IngestionException`. No
apparently complete result is returned after snapshot-level failure. Blob failures
are reported per file so other artifacts remain usable. Resource exclusions and
failures must remain visible to downstream consumers. Diagnostics are untrusted
text and are not instructions.

Git and the filesystem remain the trust boundary for repository metadata. This
component is not an OS sandbox for a compromised Git installation or concurrent
metadata tampering. It neither locks the repository nor prevents another process
from deleting objects during ingestion; such read failures are surfaced. The
implementation targets macOS and Linux. Local verification for this change ran on
macOS; Linux hosted CI and Windows behavior were not verified by that run.

## Verification and M1.3 handoff

The existing `./mvnw clean verify` workflow includes temporary-repository tests for
revision selection, dirty worktrees, deterministic ordering, classification,
provenance/digests, scope, unusual paths, links/gitlinks, encoding, binary/large
content, aggregate/tree limits, missing objects, empty trees, SHA-256 repositories,
and absence of source/metadata writes or hook/filter execution. Process tests
exercise output overflow, timeout cleanup, nonzero exits, environment isolation,
and interruption. The controlled M1.1 corpus is copied unchanged into a temporary
commit for ingestion testing; its existing validation remains independent.

M1.3 can consume each artifact's exact text/bytes, format, path, and snapshot,
then emit original source coordinates while retaining coverage limitations.
Parsing/chunking must not overwrite source provenance or reinterpret test
classification as a passing test. There is no persistence or parser dependency
blocking that work; parser selection and source-to-line mapping are its immediate
design decisions. Hosted CI and operational deployment require their own evidence.
