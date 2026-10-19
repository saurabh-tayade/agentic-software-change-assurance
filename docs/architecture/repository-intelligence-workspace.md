# Change Assurance Workspace

Release entry points: [local setup](../getting-started.md), [60–90 second demo](../demo.md),
[product overview](../product-overview.md), and [measured results](../releases/v0.1.0-results.md).
This guide retains detailed implementation and operational procedures.

M1.9b introduced the local React presentation layer; M1.9c adds a platform shell,
read-only repository catalog, and in-app Help Center over the five existing
[M1.9a endpoints](repository-intelligence-api.md). It discovers immutable
publications, asks grounded questions with an explicit retrieval strategy, and
opens verified persisted source excerpts. It adds no retrieval, prompting,
publication mutation, database schema, or model integration logic.

## Architecture and dependencies

[frontend/](../../frontend/) uses React and React DOM, plus the narrowly scoped
Radix Dialog (1.3.1), Popover (1.3.1), and Tooltip (1.4.1) primitives. Radix supplies
modal focus trapping, dismissal, restoration hooks, and accessible contextual
explanations; no custom unmanaged modal overlays are used. TypeScript uses strict checking, including unchecked-index and exact
optional-property checks. Vite serves/builds the application. Vitest, jsdom, and
React Testing Library provide deterministic interaction tests. Dependencies are
pinned in [package.json](../../frontend/package.json) and the npm lockfile; no router,
state library, component framework, Markdown interpreter, or syntax executor is used.

- [api/types.ts](../../frontend/src/api/types.ts) mirrors the actual Java
  [v1 wire records](../../src/main/java/dev/changeassurance/intelligence/RepositoryIntelligenceDtos.java).
  Required arrays remain arrays; nullable profile/invocation/token fields remain
  nullable. Unavailable token counts are not replaced with zero.
- [api/client.ts](../../frontend/src/api/client.ts) owns relative native-fetch calls,
  API version checking, identifier validation, cancellation, and safe problem parsing.
  It sends only `question` and `retrievalStrategy` for inference. It never retries
  inference or imposes a shorter timeout than the server.
- `useCatalog` loads capabilities and bounded publication pages independently.
  `useWorkspace` owns repository selection, explicit publication selection, the draft question, answer, and evidence. Changing repositories clears the publication and aborts detail, answer, and evidence requests; a repository choice alone never selects a publication.
  Each request has an AbortController; identity/signal checks discard late results
  even if the transport ignores cancellation. A synchronous pending guard prevents
  duplicate inference. Unmount aborts outstanding requests.
- `App` owns the catalog/workspace hooks across module navigation.
  `PlatformShell` owns the compact persistent header, desktop rail, and mobile
  navigation dialog. `platform/features.ts` is the typed capability registry;
  `useRoute` uses hash URLs and browser history without a router dependency.
- `DashboardPage` separates a non-scrolling `DashboardHeader` from one named,
  keyboard-focusable `.dashboard-body` scroll region. Dashboard routes use the
  viewport height with `min-height: 0` on nested grid/flex items; only body content
  scrolls, keeping the global header, rail, and dashboard actions visible. At
  viewport heights below 600 CSS pixels, natural document scrolling preserves
  access when wrapped headers would leave too little usable content space.
  Navigation remounts the body at its start, including between planned modules.
  Repository Intelligence does not use this layout.
- `DashboardHeader` supplies a compact h2 title, description, optional actions,
  and compact variant for Overview, Repositories, and existing planned pages.
  Its white toolbar panel shares the existing border, radius, and shadow tokens;
  contextual actions align right and wrap naturally on narrow screens.
  The platform shell retains its h1; Explore retains its own compact structure
  without an added dashboard header.
- `Overview` uses responsive capability tiles: M1 Repository Intelligence is
  available; M2 Contract Intelligence, M3 Change Footprint, and M4 Assisted
  Verification link to existing planned previews. The documented M5 Production
  AI Engineering milestone is a roadmap-only tile with no invented destination.
  `features.ts` supplies existing routes and the typed roadmap-only entry; M3's
  engineering milestone remains Change-Impact Analysis. One `PlannedFeature`
  component renders the existing unavailable capability previews.
- `RepositoriesPage` groups loaded publications by their actual repository IDs
  into tiles, with readable names derived from those IDs, publication counts,
  and saved processing status. Counts say "loaded" while pagination remains.
  Inspect versions is a keyboard-accessible disclosure using `PublicationChoice`
  for the original revision, scope, configuration, and provenance controls.
  Missing origin information appears only in the expanded details. Opening a
  specific version selects that publication explicitly. A tile's Explore action
  opens Ask with the repository selected and the version cleared; it never picks
  HEAD, the newest revision, or the first publication. Detailed file coverage
  and limitations remain available through Publication Details in Explore.
  Refresh Catalog, error recovery, loading/empty states, and bounded pagination
  retain the existing catalog hook. Connection fields and Load Repository
  are disabled, with no ingestion handler or endpoint.
- `IntelligenceWorkspace` composes the unchanged `WorkspaceToolbar` with one
  **Explore** panel. Repository context and Details live in its header; Supporting
  Sources is a vertical tab attached to its right edge. Reserved padding prevents
  the tab from obscuring content. Ask and the answer preview share the panel body.
  Ask stays mounted and visible in the same position before and after answering.
  It is disabled until an explicit publication has successfully loaded, and while
  inference is pending. Repository selection alone never enables questioning.
  Editing a draft does not invoke inference or remove the previous answer. A changed
  draft makes the preview identify the question that produced it. Explicit submission
  clears the old response/evidence and starts the existing guarded request flow.
- `AnswerPanel` has a compact, card-free preview and a complete Full Answer view.
  Both use the original response text and `CitationButton`. The preview clamps the
  main answer to three lines, retaining status, direct citations, a brief uncertainty
  indication, and coverage/omission warnings. Its always-visible inline **...more**
  button is named “View full answer” for assistive technology. It opens the existing
  dialog with every claim, uncertainty, limitation, validation qualification, and
  diagnostic. No second model summary or inference is generated.
- `ExampleQuestions` uses the existing Radix Popover. Click/tap or keyboard opens
  it next to Ask without expanding the layout. Escape returns to its trigger;
  selecting an example fills the editable input and focuses it without submitting.
  Navigation or another workspace dialog dismisses the popover.
- `SupportingSourcesDrawer` uses the existing Radix `Modal` at every width.
  Responsive presentation is CSS-only; resizing neither remounts nor refetches
  the source. `PublicationDetails` remains an accessible dialog.
- `help/topics.ts` is the typed, version-controlled source for 15 help topics and
  contextual definitions. `HelpCenter` supplies search/category/topic navigation;
  `ContextHelp` uses touch-accessible Radix popovers linking to the same content.
  Search is local text matching, with no service calls or external CMS.
- The shared theme uses white surfaces, cool blue-gray backgrounds, indigo actions,
  lavender selection states, and restrained violet accents. Slate text and indigo
  focus outlines preserve readability. Success remains green, warnings amber, and
  errors red. No gradients, theme framework, or icon package were added.
  `NavigationIcon` provides consistent decorative inline SVGs, including the
  roadmap-only M5 tile; navigation still has six destinations. Link labels
  supply accessible names. Planned badges are shown on destination pages and
  Overview rather than repeated in the navigation rail.
- Existing CSS tokens remain authoritative. `styles.css` covers reusable evidence
  components and `platform.css` covers shell, pages, dialogs, and layout. At
  1440×900 and 1280×800 the toolbar and Explore header remain visible while the
  Explore body can scroll within the viewport. Supporting Sources overlays the
  unchanged workspace with a right-aligned drawer, bounded to 640px / 55vw on
  desktop. At 1000px and below it becomes a full-width sheet. Source content scrolls
  inside the modal body. Short viewports, mobile, and zoomed layouts scroll naturally.
  The short entrance transition respects reduced-motion preferences.

  Toolbar selects retain native interaction, with `appearance: none`, a single
  local SVG background chevron, and reserved text padding. Forced-colors mode
  restores the native indicator; disabled colors and focus outlines remain visible.
- Dashboard pages use the available width beside the navigation rail, with 24px
  padding (16px on mobile), 16px tile gaps, and no centered page-width cap.
  Named CSS container queries lay out one, two, or three columns according to
  actual content width (two at 620px, three at 1000px). Tiles share token-based
  surfaces, borders, focus treatments, and compact typography. Version entries
  stack inside expanded repository tiles so hashes and scope paths can wrap.
  Dashboard collections scroll within the body at normal viewport heights, with
  natural document scrolling below 600 CSS pixels; no hidden
  overflow is used. The existing bounded Explore styles are unchanged.


There is no browser storage or conversation persistence. Changing publications
clears the answer and source, cancels pending work, and retains the editable question.
Re-selecting a publication whose details loaded successfully preserves its answer
and excerpt without another request; a failed detail load still has an explicit
retry. Starting another question clears the previous answer/source. Evidence failures
leave the answer intact and offer a GET retry. Submission failures preserve the
question and require an explicit new submission. Cancellation is not an error.

## Navigation and Help

| URL fragment | Available experience |
| --- | --- |
| `#/overview` | Platform purpose, v0.1 foundation, and implemented vs planned capabilities |
| `#/repositories` | Read-only grouped catalog and disabled connection preview |
| `#/intelligence` | Complete question, answer, and supporting-source workflow |
| `#/contracts` | Contract & Dependency Intelligence — planned M2 |
| `#/impact` | Change Footprint — planned M3 (engineering milestone: Change-Impact Analysis) |
| `#/verification` | Assisted Verification — planned M4 |
| `#/intelligence?help=search-strategies` | Direct link to a Help topic within a module |

The root opens Overview; unknown module/topic identifiers fall back to Overview /
Getting Started. Links, back, and forward use browser hash history. Publication
selection remains explicit and is not encoded in the URL. Navigating away keeps
workspace state and pending work in memory; returning does not repeat inference.
Reloading clears answers and sources. Changing publications retains the original
cancellation/identity guards. Full Answer and Publication Details close when navigating away or opening
Help. The source overlay is suppressed while another workspace dialog is open,
so modal dialogs do not overlap. A citation in Full Answer closes it and moves
focus to Supporting Sources; Escape returns focus to the opener. If the original
control no longer exists, Help restores focus to the global Help button.

Change Footprint is the product-facing name for the planned M3 capability. Its preview
explains possible positive, negative, or neutral effects across components, dependencies,
and interfaces. The route and engineering roadmap milestone remain unchanged.

The global header keeps the logo and **Change Assurance — Evidence-backed Engineering**
branding alongside Help. Page context remains in content headings and the active
navigation link, without repeating the page name or local-mode label in the header.
The local, read-only operating restrictions are unchanged and remain documented in
Help. On mobile, platform navigation uses a Radix dialog. Help remains at the right end of the header. Dialogs trap keyboard
focus, support Escape, and have labeled titles/descriptions; on mobile they fill
the viewport with scrollable content. Source regions support keyboard scrolling. The expanded Repository Intelligence Help
topic explains preparation, selection, search, citations, outcomes, and incomplete
coverage with product-owner, QA, developer, and system-engineer examples. All content
remains in the searchable version-controlled topic registry.
Focus outlines and prior text/boundary contrast improvements are retained. These
checks are focused accessibility verification, not a claim of full WCAG compliance.

The catalog calls a project a repository and a processed snapshot a version or
publication. Version numbers identify entries within each repository's loaded
catalog; they are not dates, stable identifiers, or latest-version assertions.
Scope/configuration and durable IDs remain in version details. The interface does
not invent source URLs: it says **Source origin not recorded**. The connection
preview opens from **Connect Repository — Planned** beside **Refresh Catalog** in
the Repositories toolbar, rather than occupying a dashboard section. It uses the
shared Radix modal with Escape/backdrop dismissal, focus trapping and restoration,
and mobile scrolling. Opening Help through browser history closes the preview.
Its disabled fields illustrate Remote Git (GitHub/GitLab/Bitbucket) and local folders only;
no credentials, browser filesystem access, cloning, ingestion, or writes exist.
Other version-control adapters and all M2–M4 capabilities remain planned.

## Simplified question and answer flow

1. In the workspace toolbar, choose **Repository**, then **Version**. Repository
   options are the recorded IDs, grouped once each from loaded catalog pages.
   Version options contain a short commit, scope, concise configuration label,
   and abbreviated publication ID; order never implies recency. Load more remains
   explicit. The primary toolbar has no Refresh button; initial discovery, bounded
   pagination, and explicit retry after a failed GET remain available.
2. Choose **Search Method** in the third dropdown. Its initial value comes from the
   capabilities API. Keyword, Vector, and Hybrid retain distinct configured,
   unconfigured, loading, and failed-discovery states. The information button opens
   the shared Search Strategies Help topic, with plain-language examples. The Help
   button sits immediately beside the dropdown with a 40px touch target. No
   automatic routing or model retry is introduced.
3. Inside **Explore**, the compact summary shows recorded repository ID, short revision, publication
   ID, and processing status. **Details** opens a Radix dialog with full identities,
   scope, coverage, and disclosed processing configuration, limitations, and file
   provenance. Scope never guarantees complete coverage, and PARTIAL is not a
   request failure. Chunk limits are absent from the primary summary.
4. **Ask** is disabled until a repository and explicit version load successfully.
   Its guidance explains this selection requirement without suggesting model failure.
   Enter a question or choose an editable example. After success, Ask remains visible
   and editable above the original answer preview. Editing alone preserves the previous
   response; a changed draft shows the submitted question next to that response.
   **Ask Question** starts a new request and invalidates the previous answer/evidence.
   The inline **...more** control opens Full Answer with every original claim,
   uncertainty, citation, and diagnostic. Preview truncation is visual only.
5. **Supporting Sources** starts closed. Its vertical right-edge tab, labeled without a plus symbol, opens a modal
   slide-over without changing Explore width. Close, Escape, or a desktop backdrop
   click dismisses it. A citation opens it automatically using the actual returned
   evidence identity. Closing preserves both answer and evidence; reopening makes
   no GET or POST. Changing publications invalidates both. Opening a full-answer
   citation closes that dialog, opens sources, and moves focus to the source heading.
6. Escape closes the source overlay at every width and restores focus
   to its tab. Details and Full Answer also support Escape and focus restoration.
   The source viewer remains escaped text with original line numbers, relative path,
   citation, revision, and collapsed Source Details. It is not a full-file browser.

## Actual API integration

All paths are relative to the browser's origin. Success envelopes identify
`apiVersion: repository-intelligence-v1`.

| Method and route | Interaction and response fields |
| --- | --- |
| `GET /api/v1/capabilities` | Configured strategies, default strategy, advertised UTF-16 question limit. Server settings are not editable. Configuration is not a readiness guarantee. |
| `GET /api/v1/publications?limit=20&after=…` | `items` and nullable `nextAfter`; explicit Load more, no automatic selection. The cursor is passed back unchanged. |
| `GET /api/v1/publications/{publicationId}` | `publication`, `coverage`, artifacts, ingestion coverage, and limitations. |
| `POST /api/v1/publications/{publicationId}/answers` | `{ "question": "Where is shipping cost calculated?", "retrievalStrategy": "VECTOR" }`; answer text/status, claims, citations, uncertainty, and same-execution diagnostics. |
| `GET /api/v1/publications/{publicationId}/evidence/{chunkId}` | `evidence` authoritative provenance, `content` original excerpt, and `structuralContext`. |

An answer's publication identity is at **`publication.publication.publicationId`**:
its outer `publication` is a full publication-detail response. Citation identity
is at `citations[].evidence`; claims reference citations through `evidenceKeys`.
The UI resolves returned identities, never paths/line coordinates. It also checks
that the resolved response matches publication, chunk, snapshot, and Git revision.
The API remains responsible for membership and persisted integrity verification.

## Publication and answer semantics

No entry is chosen automatically and ordering does not imply recency. A publication
is more than a repository/commit pair: entries show scope, a version label, processing status, and a friendly
configuration description; version details expose chunking limits and IDs. Full IDs, commit SHA,
policy, configuration identity, snapshot/derivation, and per-artifact coverage are
available in provenance details. COMPLETE, PARTIAL, and FAILED remain distinct.
PARTIAL is saved coverage information, not an HTTP execution failure.

KEYWORD is the default advertised by the API; VECTOR and HYBRID are enabled only
when configured. A configured strategy can still fail due to an incomplete index
or unavailable model. There is no silent fallback. Questions must be nonblank,
within the server's UTF-16 length limit, valid Unicode, and NUL-free. The composer
shows a compact repository/revision/publication summary, with scope and processing limits
in the Details dialog so publications sharing a commit remain distinguishable. Loading or failed
capabilities are labelled unknown/unavailable; only a successful capabilities
response can label a strategy unconfigured. The server remains authoritative for
validation. Ctrl/Cmd+Enter and the submit button share
the same pending/validation guard.

SUPPORTED, PARTIALLY_SUPPORTED, and INSUFFICIENT_EVIDENCE are application answer
results, never independent truth judgments. All returned uncertainties remain
visible in Full Answer, with a concise processing-coverage and context-omission summary. General
ingestion/processing policies remain available in a counted native disclosure.
No returned uncertainty is filtered or rewritten. Technical details show requested/effective
strategy, ranked references and scores, context selection/omission reasons,
model/prompt metadata, invocation timing/tokens, and the exact statement:

```text
CITATION_IDENTITIES_VALIDATED_SEMANTIC_SUPPORT_NOT_VERIFIED
```

Ranking scores are not confidence percentages. Every returned citation is a button,
including citations not attached to a claim. Ranked evidence is also navigable.
Zero-citation answers say "No citations returned." The exact backend validation
statement remains in diagnostics for every answer. Opening a citation focuses the
evidence heading for keyboard/mobile navigation.
The explorer shows original inclusive source coordinates, line-numbered escaped
Java/Markdown, repository/revision, format, citation key, and expandable hashes,
Git blob, parser, chunking, and structural context. It is not a full-file browser.
Long source lines scroll inside the excerpt rather than widening the page.

## Errors and local security

The central parser maps stable `application/problem+json` codes to fixed UI messages.
It never renders arbitrary server `detail`, provider bodies, SQL, or stack traces.
Invalid requests, unknown publications/evidence, incomplete indexes/profile
mismatch, unavailable services, timeouts, invalid model output, and integrity
failures remain operational errors. Unknown codes use a generic message; the safe
code remains visible for diagnostics. They never become INSUFFICIENT_EVIDENCE.

Source, paths, Markdown, structural context, and model output are untrusted text.
React escapes them; there is no `dangerouslySetInnerHTML`, Markdown/HTML execution,
filesystem navigation, or URL derived from source paths. Answers/excerpts are
kept in memory; requests use `cache: no-store` and no credentials. The backend's
no-store responses and integrity checks are preserved through the proxy.

Both processes bind to loopback. The Vite proxy changes Host to the backend's
actual `127.0.0.1:8080`, **retains the browser's Origin**, and adds no CORS grant.
Configure the API with the exact browser origin below. Do not strip Origin, enable
wildcard CORS, bind to all interfaces, or turn off backend Host checks. All local
database publications are visible to the trusted operator. This has no authentication
or RBAC and must not be publicly exposed; localhost is not enterprise authorization.

## Installation and local startup

Use Node 24 LTS (24.15+ within major 24) and npm 11. The checked development/CI
version is pinned in [frontend/.nvmrc](../../frontend/.nvmrc). Java 21, Docker,
the existing PostgreSQL publications/indexes, and Ollama models are needed for
live use. Frontend tests/build need none of those backend services.

From the repository root:

```sh
cd frontend
nvm use # if nvm is installed; otherwise use the Node version in .nvmrc
npm ci
npm run typecheck
npm test
npm run build
cd ..
```

In the shell holding your existing local database credentials:

```sh
: "${EVIDENCE_DB_USER:?Export your existing local database user}"
: "${EVIDENCE_DB_PASSWORD:?Export your existing local database password}"
export EVIDENCE_DB_URL="${EVIDENCE_DB_URL:-jdbc:postgresql://localhost:5432/evidence}"
docker compose up -d postgres
docker compose ps postgres
```

If Ollama is not already running, start it in another terminal:

```sh
ollama serve
```

Check the existing models (no automatic downloads or index creation):

```sh
ollama list
curl --fail-with-body http://127.0.0.1:11434/api/tags
```

The current chat default is `qwen3:4b-instruct`; the existing embedding profile
normally uses `nomic-embed-text:v1.5`. Follow the [embedding guide](embedding-indexing.md)
if preparation is missing. Never substitute a different model for an existing profile.
In the credentials shell, start the read-only API:

```sh
export INTELLIGENCE_API_BROWSER_ORIGIN=http://127.0.0.1:5173
export INTELLIGENCE_API_EMBEDDING_PROFILE_ID=6360aef03c6233a05a91583849c68ae0d6a97a66b1ef985de22e92876aa2aa0b
./mvnw spring-boot:run \
  -Dspring-boot.run.profiles=intelligence-api,embeddings
```

The profile ID is an example, not a default. Use the compatible ID already in your
local database. API startup excludes CLI runners and disables Flyway/schema writes;
it does not ingest, index, or prepare missing data. Backend startup/CLI behavior is
unchanged by M1.9b.

In another terminal, from the repository root:

```sh
npm run dev --prefix frontend
```

Open **http://127.0.0.1:5173/** (use this exact origin, not `localhost`). Vite's
strict port setting prevents silently moving to a port the API does not allow.
If that port is occupied, leave the other application alone: restart your API with
`INTELLIGENCE_API_BROWSER_ORIGIN=http://127.0.0.1:5174`, run
`npm run dev --prefix frontend -- --port 5174`, and open that exact URL. Changing
the environment does not reconfigure an already-running API process.

`npm run build --prefix frontend` writes ignored `frontend/dist/`. To inspect
that build locally, stop Vite dev and run `npm run preview --prefix frontend`;
preview uses the same loopback port and proxy. This is a local preview procedure,
not a public deployment configuration. Stop owned foreground processes with Ctrl+C.

## Demo startup and preflight

Use the existing installation steps and Node version above. Start services only
when they are not already running. Coordinate any restart with the owner; do not
stop unrelated listeners. For a demo on **http://127.0.0.1:5174**, use the following
commands from the repository root in the indicated terminals.

In the credentials/API terminal (existing credentials, no new database or index):

```sh
: "${EVIDENCE_DB_USER:?Export your existing local database user}"
: "${EVIDENCE_DB_PASSWORD:?Export your existing local database password}"
export EVIDENCE_DB_URL="${EVIDENCE_DB_URL:-jdbc:postgresql://localhost:5432/evidence}"
docker compose up -d postgres
docker compose ps postgres
docker compose exec -T postgres sh -c 'pg_isready -U "$POSTGRES_USER" -d evidence'
export INTELLIGENCE_API_BROWSER_ORIGIN=http://127.0.0.1:5174
export INTELLIGENCE_API_EMBEDDING_PROFILE_ID=6360aef03c6233a05a91583849c68ae0d6a97a66b1ef985de22e92876aa2aa0b
./mvnw spring-boot:run \
  -Dspring-boot.run.profiles=intelligence-api,embeddings
```

The profile ID is an example for the prepared Parcel Quotes index. Substitute the
compatible profile already stored in your database; it is not a hardcoded default.
Use your actual configured database port in `EVIDENCE_DB_URL` if different.

If Ollama is not already running, start it in a separate terminal:

```sh
ollama serve
```

In the frontend terminal, after installing dependencies with `npm ci --prefix frontend`:

```sh
npm run dev --prefix frontend -- --host 127.0.0.1 --port 5174
```

In a preflight terminal, verify the **same origin the browser will open**:

```sh
export WORKSPACE_ORIGIN=http://127.0.0.1:5174
export DEMO_PUBLICATION=40b677d1bb8efeb02506e0de2ef624acb046a26cc2b57a65a8d7d9ab906c702c
export DEMO_PROFILE=6360aef03c6233a05a91583849c68ae0d6a97a66b1ef985de22e92876aa2aa0b
export DEMO_CHECK_DIR="$(mktemp -d /tmp/repository-demo-preflight.XXXXXX)"
ollama list
curl --fail-with-body http://127.0.0.1:11434/api/tags
curl --fail-with-body http://127.0.0.1:8080/api/v1/capabilities
curl --fail-with-body "$WORKSPACE_ORIGIN/" -o /dev/null
curl --fail-with-body -H "Origin: $WORKSPACE_ORIGIN" \
  "$WORKSPACE_ORIGIN/api/v1/capabilities" -o "$DEMO_CHECK_DIR/capabilities.json"
curl --fail-with-body -H "Origin: $WORKSPACE_ORIGIN" \
  "$WORKSPACE_ORIGIN/api/v1/publications?limit=20" -o "$DEMO_CHECK_DIR/publications.json"
curl --fail-with-body -H "Origin: $WORKSPACE_ORIGIN" \
  "$WORKSPACE_ORIGIN/api/v1/publications/$DEMO_PUBLICATION" -o "$DEMO_CHECK_DIR/publication.json"
python3 - <<'CHECK'
import json, os
from pathlib import Path
root = Path(os.environ['DEMO_CHECK_DIR'])
caps = json.loads((root / 'capabilities.json').read_text())
page = json.loads((root / 'publications.json').read_text())
detail = json.loads((root / 'publication.json').read_text())
assert caps['apiVersion'] == 'repository-intelligence-v1'
assert {'KEYWORD', 'VECTOR'} <= set(caps['configuredRetrievalStrategies'])
assert caps['embeddingProfileId'] == os.environ['DEMO_PROFILE']
assert detail['publication']['publicationId'] == os.environ['DEMO_PUBLICATION']
print('Configured chat model:', caps['generationSettings']['model'])
print('Configured embedding profile:', caps['embeddingProfileId'])
print('Catalog page:', len(page['items']), 'publications; next cursor:', page['nextAfter'])
print('Selected publication:', detail['publication'])
print('Coverage:', detail['coverage'])
CHECK
```

Confirm the advertised chat model is installed in `ollama list`. Also confirm the
embedding model configured in the API startup shell (`EMBEDDINGS_MODEL`, normally
`nomic-embed-text:v1.5`) is installed and matches the existing profile described in
the [embedding guide](embedding-indexing.md). The API does not expose a profile
management endpoint. If the intended publication is beyond the first catalog page,
use **Load more publications** in the UI; ordering does not indicate recency.

These checks establish reachability, readable metadata, and configured strategies.
They do not prove a complete embedding index or successful inference. One real
VECTOR question tests that selected publication's current retrieval/model path;
inspect any safe operational error rather than silently switching publications,
creating an index, or assuming readiness. The API process performs no migration
or ingestion as part of this procedure.

### Diagnosing a frontend-only startup

A page loading does not prove the API proxy is active. If direct API capabilities
return 200 but the same route through Vite returns 404, check the Vite terminal,
working directory, and whether `frontend/vite.config.ts` was loaded. During the
M1.9c audit the existing Vite process predated creation of that config file and
listened on `[::1]:5174`; `localhost` served the UI, while `127.0.0.1` refused the
connection. Starting a fresh process with the checked-in configuration restored
IPv4 loopback access and both proxy routes without backend changes.

Restart only your own stale Vite process after coordinating with its owner, using
the frontend command above. Use the URL printed by that fresh process. The exact
scheme, host, and port must match `INTELLIGENCE_API_BROWSER_ORIGIN` in the running
API process; exporting a new value elsewhere does not reconfigure it. A 403 origin
rejection is distinct from a missing proxy. Never strip Origin, relax Host checks,
or add wildcard CORS. Do not substitute `localhost` for `127.0.0.1`.

## Five-minute engineering demonstration

Complete preflight before sharing the screen. Keep technical disclosures closed
initially; the main workflow should explain itself in roughly 30 seconds.

| Time | Action and explanation |
| --- | --- |
| 0:00–0:45 | Introduce Overview (v0.1 available, M2–M4 planned), open Repositories, inspect versions, and explicitly select the full Parcel Quotes publication by ID, all-path scope, and 10,000-chunk limit. Show its immutable Git revision and coverage. Explain that scope/configuration also define publication identity and PARTIAL is saved coverage, not an HTTP failure. |
| 0:45–1:30 | Ask **Where is shipping cost calculated?** with **Vector** in the Search Method dropdown. Describe meaning-based search while the local model runs. Do not resubmit while pending. |
| 1:30–2:30 | Read the actual status and preview; open **...more** for claims. Open the returned citation for the pricing implementation; inspect `QuotePolicy.priceCents()` if it is cited. E5 and lines 4–7 are historical examples, not guaranteed outputs. |
| 2:30–3:15 | Show original line numbers, escaped source, revision, and Source Details. Close Supporting Sources to uncover the unchanged Explore panel. Read the brief uncertainty indicator and coverage/omission warning; open **...more** for complete uncertainty. Expand general policies only when relevant. Explain that identity checks do not independently verify semantic support or factual correctness. |
| 3:15–4:15 | Edit the existing Ask field if needed, select **Keyword** in the Search Method dropdown, and explicitly ask again. Explain the observed result, whether supported or insufficient. Insufficiency is not proof that functionality is absent; operational failure is a separate state. |
| 4:15–5:00 | Expand retrieval/execution details. Compare ranking, selected evidence, omissions, and model timing. Scores are not confidence. Connect this observation to the existing M1.8 evaluations below. |

The maintainer-reported M1.8a acceptance recorded **90 retrieval executions without
failures** and reproduction of the historical keyword baseline. M1.8b acceptance
recorded **six Q&A executions**, with one attributed review containing both an
incorrect and a correct claim despite valid citation identities; five executions
remained semantically unreviewed. M1.8c provides reproducible offline reports with
explicit input/review selection and denominators. These are historical acceptance
observations, not a fresh benchmark, proof of aggregate correctness, or evidence
that one retrieval strategy is generally superior. See the
[retrieval evaluation guide](retrieval-evaluation.md),
[Q&A evaluation guide](grounded-answer-evaluation.md), and
[reporting guide](evaluation-reporting.md).

Do not promise identical wording, status, citation keys, or timings. If a response
has no citations, discuss its uncertainty instead of presenting an unrelated
excerpt as support. If inference fails, show the safe error and preserved draft;
resolve the local service issue before retrying. No live results should be fabricated.

## Maintainer live acceptance

This procedure uses existing data and does not guarantee any particular model output.
The known full Parcel Quotes publication is an example, not hardcoded in the UI:

```sh
WORKSPACE_ORIGIN=${WORKSPACE_ORIGIN:-http://127.0.0.1:5173}
API="$WORKSPACE_ORIGIN/api/v1"
PUBLICATION=40b677d1bb8efeb02506e0de2ef624acb046a26cc2b57a65a8d7d9ab906c702c
curl --fail-with-body "$API/capabilities" | python3 -m json.tool
curl --fail-with-body "$API/publications?limit=20" | python3 -m json.tool
curl --fail-with-body "$API/publications/$PUBLICATION" | python3 -m json.tool
```

1. Open Overview, inspect planned previews, and open Repositories. Open **Connect
   Repository** and confirm its planned status, disabled Load Repository action,
   and disabled illustrative fields. Close the modal, then choose
   the full Parcel Quotes entry by the explicit publication ID, all-path scope,
   and 10,000 chunk limit; expand provenance to confirm the full ID and revision.
   The source-only and one-chunk entries must remain distinguishable, even with
   the same commit. The full publication may still be PARTIAL.
2. In Repository Intelligence, enter **Where is shipping cost calculated?**,
   choose **Vector** in Search Method, and submit.
   Observe the pending state and disabled resubmission. Wait for the actual result.
3. Inspect answer status and uncertainty; use **...more** for complete claims. If E5 is returned, click it and
   check `src/example/quotes/QuotePolicy.java`, original lines **4–7**. If the model
   returns different citations, inspect those and record the difference; never
   fabricate E5 or replace returned source. Identity checks do not prove the claim.
4. Expand retrieval/execution details and Source Details. Check requested versus
   effective strategy, rank, context omissions, prompt/model, timing, and nullable
   token counts. Keep the Ask field in place, change Search Method to Keyword, then Hybrid, explicitly
   asking again each time.
5. Try **Which production Kubernetes namespace is this service deployed to?**
   using Keyword. The controlled corpus does not establish deployment. Inspect the
   actual result; INSUFFICIENT_EVIDENCE must be an answer with uncertainty, not an
   error. Model variability means this question is not a deterministic guarantee.
6. Switch to the source-only or one-chunk publication, including while an answer
   or evidence request is pending. Old results must disappear and never repopulate
   the new selection. The question should remain editable.
7. Exercise keyboard-only dropdown selection, Cmd/Ctrl+Enter, citation
   activation, and details expansion. Repeat at 1440×900, 1280×800, 768px, and 390px. Check the
   drawer, Help search/category/topic navigation, Escape/focus restoration, full-answer
   dialog, and disabled connection preview. The Explore body and source overlay have bounded scrolling;
   narrow layouts use a full-width source sheet with natural page reflow. Long source lines scroll inside the excerpt. At browser
   zoom or larger text sizes, check reflow and reachability rather than clipping.
8. Navigate away and back, then re-select the same publication: preserve the answer
   without another POST. Open Help directly with `?help=search-strategies`, search
   Vector or Hybrid, and confirm the distinction between configuration and readiness.

Check safe malformed-input handling through the same proxy:

```sh
curl -i -H 'Content-Type: application/json' \
  -H "Origin: $WORKSPACE_ORIGIN" \
  --data '{"question":" ","retrievalStrategy":"VECTOR"}' \
  "$API/publications/$PUBLICATION/answers"
curl -i "$API/publications/not-a-publication"
```

Expect 400 with a fixed problem code/description, no SQL/provider details. To
review operational-error behavior, use a naturally occurring safe failure or the
deterministic frontend tests; do not interrupt a healthy demo service just to
produce an error. Submission failures preserve the draft question. An allowed-origin
misconfiguration also produces a safe ORIGIN_NOT_ALLOWED notice; fix startup
configuration instead of weakening the proxy. Never stop somebody else's process.

For a direct same-proxy inference/evidence check:

```sh
curl --fail-with-body -H 'Content-Type: application/json' \
  -H "Origin: $WORKSPACE_ORIGIN" \
  --data '{"question":"Where is shipping cost calculated?","retrievalStrategy":"VECTOR"}' \
  "$API/publications/$PUBLICATION/answers" > /tmp/repository-workspace-answer.json
python3 -m json.tool /tmp/repository-workspace-answer.json
CITATION_PATH=$(python3 - <<'PY'
import json, re
answer = json.load(open('/tmp/repository-workspace-answer.json'))
assert answer['citations'], 'No citation returned; inspect the answer status and uncertainty'
citation = next((c for c in answer['citations'] if c['evidence']['path'].endswith('QuotePolicy.java')), answer['citations'][0])
e = citation['evidence']
assert all(re.fullmatch('[0-9a-f]{64}', e[k]) for k in ('publicationId', 'chunkId'))
print('/publications/' + e['publicationId'] + '/evidence/' + e['chunkId'])
PY
) && curl --fail-with-body "$API$CITATION_PATH" | python3 -m json.tool
```

Keep live artifacts private and temporary. Do not commit answers/source captures
or treat one successful run as an aggregate quality measurement.

## Verification and remaining scope

From the repository root, the frontend checks match the single existing CI job:

```sh
npm ci --prefix frontend
npm run typecheck --prefix frontend
npm test --prefix frontend
npm run build --prefix frontend
./mvnw clean verify
python3 scripts/check_repository.py
git diff --check
git diff --cached --check
```

Vitest uses typed synthetic API fixtures and mocked fetch, not PostgreSQL/Ollama.
Tests cover discovery/pagination and explicit scopes, capabilities, validation,
submission and pending guards, all answer statuses, claim/citation navigation,
original lines and escaped malicious content, independent safe errors, publication
reset, cancellation/late responses, and keyboard interactions. Platform tests add
grouped catalogs, unavailable previews, disabled connection controls, hash routes,
Help search/category links, contextual guidance, focus restoration, compact/full
answers, and navigation state preservation. Workspace regressions also cover
repository grouping/version filtering, cancellation on repository changes, concise
summaries, details disclosures, drawer preservation without requests, and responsive
full-answer-to-source-sheet focus transfer, unified Explore presentation, example-popover focus and dismissal, and native long version labels. Further regressions cover disabled Ask states, stable editable input, draft/response association, inline full-answer navigation, the source tab, icon navigation, toolbar cleanup, and expanded Help. Browser checks verify the unchanged workspace width behind the overlay, stable input coordinates, select-arrow appearance, and theme. Java tests remain
unchanged and run the existing backend contracts and Testcontainers regressions.
Build/type checks and DOM tests do not replace responsive browser review or live
acceptance. Hosted CI must be checked on the revision under review.

No authentication, functional ingestion/connection UI, full-file browsing, persisted conversation history,
streaming, evaluation dashboard, impact analysis, agent orchestration, or public
hosting is included. The frontend trusts the versioned local API's successful
payload schema; TypeScript and version/identity checks are not a general runtime
schema validator. No new backend or model behavior is introduced.
