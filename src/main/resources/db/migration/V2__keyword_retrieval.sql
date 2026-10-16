-- Versioned derived search data. Original evidence remains untouched.
-- Keep whole technical tokens as well as ASCII camel/acronym components.
CREATE FUNCTION keyword_text_v1(value text) RETURNS text
LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE AS $$
    SELECT lower(regexp_replace(value, '[^[:alnum:]_]+', ' ', 'g') || ' ' ||
        regexp_replace(regexp_replace(regexp_replace(value,
            '([A-Z])([A-Z][a-z])', '\1 \2', 'g'),
            '([a-z0-9])([A-Z])', '\1 \2', 'g'), '[^[:alnum:]]+', ' ', 'g'))
$$;

CREATE FUNCTION keyword_document_v1(content text, context jsonb) RETURNS tsvector
LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE AS $$
    SELECT setweight(to_tsvector('simple', keyword_text_v1(left(context::text, 65536))), 'A') ||
           setweight(to_tsvector('simple', keyword_text_v1(left(content, 65536))), 'B')
$$;

CREATE FUNCTION keyword_query_v1(value text) RETURNS tsquery
LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE AS $$
    SELECT coalesce(string_agg(quote_literal(term), ' | ' ORDER BY term)::tsquery, ''::tsquery)
    FROM unnest(tsvector_to_array(to_tsvector('simple', keyword_text_v1(value)))) term
$$;

ALTER TABLE evidence_chunk ADD COLUMN search_document tsvector
    GENERATED ALWAYS AS (keyword_document_v1(content, context)) STORED;
ALTER TABLE source_artifact ADD COLUMN search_path tsvector
    GENERATED ALWAYS AS (setweight(to_tsvector('simple', keyword_text_v1(path)), 'C')) STORED;
CREATE INDEX evidence_chunk_keyword_gin ON evidence_chunk USING gin (search_document);
CREATE INDEX source_artifact_keyword_gin ON source_artifact USING gin (search_path);
