CREATE TABLE repository_snapshot (
    snapshot_id text PRIMARY KEY CHECK (snapshot_id ~ '^[0-9a-f]{64}$'),
    repository_id text NOT NULL CHECK (length(repository_id) > 0),
    commit_sha text NOT NULL CHECK (commit_sha ~ '^([0-9a-f]{40}|[0-9a-f]{64})$'),
    scope jsonb NOT NULL CHECK (jsonb_typeof(scope) = 'object'),
    policy_version text NOT NULL
);

CREATE TABLE source_artifact (
    snapshot_id text NOT NULL REFERENCES repository_snapshot,
    path text NOT NULL CHECK (length(path) > 0),
    path_key text NOT NULL CHECK (path_key ~ '^[0-9a-f]{64}$'),
    artifact_type text NOT NULL,
    source_format text NOT NULL,
    git_blob_id text NOT NULL CHECK (git_blob_id ~ '^([0-9a-f]{40}|[0-9a-f]{64})$'),
    source_hash text NOT NULL CHECK (source_hash ~ '^[0-9a-f]{64}$'),
    content text NOT NULL CHECK (octet_length(content) <= 1048576),
    PRIMARY KEY (snapshot_id, path_key)
);

CREATE TABLE evidence_chunk (
    snapshot_id text NOT NULL,
    chunk_id text NOT NULL CHECK (chunk_id ~ '^[0-9a-f]{64}$'),
    path_key text NOT NULL,
    start_line integer NOT NULL CHECK (start_line > 0),
    end_line integer NOT NULL CHECK (end_line >= start_line),
    content text NOT NULL CHECK (octet_length(content) BETWEEN 1 AND 1048576),
    content_hash text NOT NULL CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    parser text NOT NULL,
    configuration text NOT NULL,
    context jsonb NOT NULL CHECK (jsonb_typeof(context) = 'array'),
    PRIMARY KEY (snapshot_id, chunk_id),
    FOREIGN KEY (snapshot_id, path_key) REFERENCES source_artifact
);

CREATE TABLE processing_publication (
    publication_id text PRIMARY KEY CHECK (publication_id ~ '^[0-9a-f]{64}$'),
    snapshot_id text NOT NULL REFERENCES repository_snapshot,
    derivation_id text NOT NULL CHECK (derivation_id ~ '^[0-9a-f]{64}$'),
    configuration text NOT NULL,
    status text NOT NULL CHECK (status IN ('COMPLETE', 'PARTIAL', 'FAILED')),
    metadata jsonb NOT NULL CHECK (jsonb_typeof(metadata) = 'object'),
    UNIQUE (snapshot_id, configuration, derivation_id),
    UNIQUE (publication_id, snapshot_id),
    CHECK (status = metadata->>'status')
);

CREATE TABLE publication_chunk (
    publication_id text NOT NULL,
    snapshot_id text NOT NULL,
    chunk_id text NOT NULL,
    ordinal integer NOT NULL CHECK (ordinal >= 0),
    PRIMARY KEY (publication_id, chunk_id),
    UNIQUE (publication_id, ordinal),
    FOREIGN KEY (publication_id, snapshot_id) REFERENCES processing_publication (publication_id, snapshot_id),
    FOREIGN KEY (snapshot_id, chunk_id) REFERENCES evidence_chunk
);

-- Only exact-identity lookup and ordered publication reads are required in M1.4.
-- Primary/unique indexes cover those operations; no search indexes are introduced.
