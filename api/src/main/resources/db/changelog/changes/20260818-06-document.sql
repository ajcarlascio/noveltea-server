--liquibase formatted sql

--changeset anthony:20260818-06-01-document
--comment: 1:1 with a binder_item of type 'document', sharing its id, so the large
--comment: content payload never bloats a tree query. content is opaque to the JVM
--comment: (A1): ProseMirror JSON is only ever parsed by the Node worker.
--comment:
--comment: search_text is a flattened plain-text projection written back by the
--comment: worker alongside word_count. The server cannot derive it, because doing
--comment: so would mean walking ProseMirror nodes in Java.
CREATE TABLE document (
    id                   uuid        PRIMARY KEY REFERENCES binder_item(id) ON DELETE CASCADE,
    content              jsonb       NOT NULL DEFAULT '{"type":"doc","content":[]}'::jsonb,
    search_text          text,
    search_tsv           tsvector    GENERATED ALWAYS AS
                             (to_tsvector('english', coalesce(search_text, ''))) STORED,
    word_count           integer     NOT NULL DEFAULT 0,
    synopsis             text,
    notes                text,
    version              bigint      NOT NULL DEFAULT 1,
    updated_by_device_id uuid        REFERENCES device(id) ON DELETE SET NULL,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT document_word_count_nonnegative CHECK (word_count >= 0)
);
CREATE INDEX document_search_idx  ON document USING gin (search_tsv);
CREATE INDEX document_content_idx ON document USING gin (content jsonb_path_ops);
CREATE TRIGGER document_set_updated_at BEFORE UPDATE ON document
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
--rollback DROP TABLE document;

--changeset anthony:20260818-06-02-document-requires-document-item splitStatements:false
--comment: a document row may only hang off a binder_item of type 'document'.
CREATE OR REPLACE FUNCTION document_assert_item_type() RETURNS trigger AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM binder_item b WHERE b.id = NEW.id AND b.type = 'document'
    ) THEN
        RAISE EXCEPTION 'document % does not correspond to a binder_item of type document', NEW.id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER document_item_type
    AFTER INSERT OR UPDATE OF id ON document
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION document_assert_item_type();
--rollback DROP TRIGGER IF EXISTS document_item_type ON document;
--rollback DROP FUNCTION IF EXISTS document_assert_item_type();
