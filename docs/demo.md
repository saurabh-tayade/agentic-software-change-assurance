# A 60–90 second Repository Intelligence demo

**Upcoming v0.1.0 — Repository Intelligence Foundation.** This is a demonstration of
repository understanding and evidence inspection, not automated change approval.
Use the [product overview](product-overview.md) for a longer introduction.

## Prepare before presenting

Follow [local setup](getting-started.md) or the existing
[workspace preflight](architecture/repository-intelligence-workspace.md#demo-startup-and-preflight).
Start the API with `intelligence-api,embeddings` and a matching Vite browser origin.
The screenshot session used `http://127.0.0.1:5174/`; Vite's default is 5173.
Keep database credentials privately loaded. Do not rebuild indexes during the demo.

The maintainer's prepared DDIP publication is:

```sh
export WEB=http://127.0.0.1:5174
export PUBLICATION_ID=03f03df316563a7c1b91030179962c8d60fc3da86b6868da7b699bdceac7d049
export INTELLIGENCE_API_EMBEDDING_PROFILE_ID=6360aef03c6233a05a91583849c68ae0d6a97a66b1ef985de22e92876aa2aa0b
```

These are recorded examples, not defaults. Exporting the profile here does not
reconfigure an API already running. A new database needs explicit preparation.
DDIP commit: `34819a23ac1cd0b83f2921d14665f330144dc215`; scope:
`README.md`, `docs`, `ingestion/src/main/java`, `services/content-api`.
Its 1,155 emitted chunks were completely indexed; publication coverage remains PARTIAL.
Python Silver/Gold implementation is outside this evidence coverage.

Preflight, without invoking the model:

```sh
docker compose exec -T postgres sh -c 'pg_isready -U "$POSTGRES_USER" -d evidence'
ollama list
curl --fail-with-body http://127.0.0.1:11434/api/tags
curl --fail-with-body http://127.0.0.1:8080/api/v1/capabilities
curl --fail-with-body "$WEB/api/v1/capabilities"
curl --fail-with-body "$WEB/api/v1/publications?repositoryId=distributed-data-intelligence-platform&limit=50"
curl --fail-with-body "$WEB/api/v1/publications/$PUBLICATION_ID"
```

Confirm the actual publication, pinned commit, configured VECTOR strategy, compatible
Nomic profile, and installed `qwen3:4b-instruct` and `nomic-embed-text:v1.5` models.
Configuration alone does not prove runtime readiness. Allow time for model startup;
the recorded screenshot answer took 12.991 seconds, not a promised response time.

## Live walkthrough

| Time | Action | Suggested explanation |
| --- | --- | --- |
| 0–10 s | Open Overview | “Repository Intelligence is implemented. Contract Intelligence, Change Footprint, and Assisted Verification are planned.” |
| 10–25 s | Open Repositories; inspect DDIP versions | “These are prepared immutable publications. We choose a revision and scope explicitly; this is not a live view of HEAD.” |
| 25–40 s | Open DDIP in Explore; select its version and VECTOR | “Meaning-based search finds relevant evidence within this publication.” |
| 40–60 s | Submit the question below; read the actual status | “This answer is based on bounded evidence. Its status is not a factual-verification stamp.” |
| 60–80 s | Open **...more**, then an actual Java citation | “The source overlay shows the original lines and Git revision. We can inspect the evidence behind the claim.” |
| 80–90 s | Explain uncertainty and scope | “Complete indexing does not mean complete repository coverage. We still need to review the interpretation and missing evidence.” |

Question:

> How does BoundedProcessRunner limit analytics subprocess concurrency and execution time?

In the recorded run, the answer was `PARTIALLY_SUPPORTED` and cited the constructor
in `BoundedProcessRunner.java` and configuration in `BoundedProcessRunnerTest.java`.
It did not establish the full runtime timeout-enforcement path. Follow whichever
citation the actual response returns; do not assume E1/E5 or identical wording.
If no relevant implementation is cited, explain that retrieval limitation rather
than repeatedly asking until a more favorable answer appears.

Full Answer retains claims, uncertainty, coverage, context omissions, and citation
qualifications. Opening a citation closes that dialog before opening Supporting
Sources. Source Details exposes further provenance. Finding a test does not mean
that this platform ran it.

## Offline fallback: 30–45 seconds

State explicitly: **“These are recorded screenshots and results, not a live inference.”**
Show [Overview](assets/v0.1.0/overview.png),
[the actual DDIP answer](assets/v0.1.0/full-answer.png),
[its Java source](assets/v0.1.0/supporting-java-source.png), and
[uncertainty](assets/v0.1.0/answer-uncertainty.png).
Use the [capture inventory](assets/v0.1.0/README.md) and
[measured-results record](releases/v0.1.0-results.md) to explain exactly what was observed.
No database, model, or fresh embedding generation is required for this fallback.
