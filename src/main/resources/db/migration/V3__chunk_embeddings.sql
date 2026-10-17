-- Derived indexes only: V1 publication identities and V2 keyword behavior are unchanged.
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE embedding_profile (
    profile_id text PRIMARY KEY CHECK (profile_id ~ '^[0-9a-f]{64}$'),
    provider text NOT NULL CHECK (provider = 'ollama'),
    model text NOT NULL,
    model_digest text NOT NULL CHECK (model_digest ~ '^[0-9a-f]{64}$'),
    dimensions integer NOT NULL CHECK (dimensions = 768),
    document_recipe text NOT NULL,
    query_recipe text NOT NULL,
    options jsonb NOT NULL CHECK (jsonb_typeof(options) = 'object'),
    distance_metric text NOT NULL CHECK (distance_metric = 'cosine')
);

CREATE TABLE chunk_embedding (
    snapshot_id text NOT NULL,
    chunk_id text NOT NULL,
    profile_id text NOT NULL REFERENCES embedding_profile,
    input_hash text NOT NULL CHECK (input_hash ~ '^[0-9a-f]{64}$'),
    vector_hash text NOT NULL CHECK (vector_hash ~ '^[0-9a-f]{64}$'),
    embedding vector(768) NOT NULL CHECK (vector_norm(embedding) > 0),
    PRIMARY KEY (snapshot_id, chunk_id, profile_id),
    FOREIGN KEY (snapshot_id, chunk_id) REFERENCES evidence_chunk
);

-- No approximate index, publication status mutation, or duplicated source coordinates.
