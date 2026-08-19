--liquibase formatted sql

--changeset anthony:20260819-20-01-binder-title-search
--comment: Titles are the highest-signal thing an author searches for — "where is the
--comment: lighthouse scene" is usually a title, not a phrase in the prose. They live on
--comment: binder_item rather than document, so they need their own index; folders have
--comment: titles too and must be findable.
ALTER TABLE binder_item
    ADD COLUMN title_tsv tsvector
        GENERATED ALWAYS AS (to_tsvector('english', coalesce(title, ''))) STORED;

CREATE INDEX binder_item_title_search_idx ON binder_item USING gin (title_tsv);
--rollback DROP INDEX IF EXISTS binder_item_title_search_idx;
--rollback ALTER TABLE binder_item DROP COLUMN title_tsv;

--changeset anthony:20260819-20-02-document-weighted-search
--comment: Widens document search to cover the author's own scaffolding. Synopses and notes
--comment: are never EXPORTED, but they are exactly what an author searches to find a scene
--comment: again — refusing to search them would make them write-only.
--comment:
--comment: Weighted so a match in a synopsis outranks one buried in a note. A generated
--comment: expression cannot be altered in place, so the column is replaced.
DROP INDEX IF EXISTS document_search_idx;
ALTER TABLE document DROP COLUMN search_tsv;

ALTER TABLE document
    ADD COLUMN search_tsv tsvector GENERATED ALWAYS AS (
        setweight(to_tsvector('english', coalesce(synopsis, '')), 'B')
        || setweight(to_tsvector('english', coalesce(search_text, '')), 'C')
        || setweight(to_tsvector('english', coalesce(notes, '')), 'D')
    ) STORED;

CREATE INDEX document_search_idx ON document USING gin (search_tsv);
--rollback DROP INDEX IF EXISTS document_search_idx;
--rollback ALTER TABLE document DROP COLUMN search_tsv;
--rollback ALTER TABLE document ADD COLUMN search_tsv tsvector GENERATED ALWAYS AS
--rollback     (to_tsvector('english', coalesce(search_text, ''))) STORED;
--rollback CREATE INDEX document_search_idx ON document USING gin (search_tsv);
