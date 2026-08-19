-- 008_search_scope: mirrors server migration 20260819-20.
--
-- Widens offline search to cover titles, synopses and notes as well as body text. These
-- are never exported, but they are what an author searches to find a scene again, so
-- leaving them out would make them write-only.
DROP TRIGGER IF EXISTS document_fts_insert;
DROP TRIGGER IF EXISTS document_fts_delete;
DROP TRIGGER IF EXISTS document_fts_update;
DROP TABLE IF EXISTS document_fts;

-- Not an external-content table any more: it draws from two tables, so it holds its own
-- copy and the triggers below keep it current.
CREATE VIRTUAL TABLE document_fts USING fts5(
    document_id UNINDEXED,
    title,
    synopsis,
    body,
    notes,
    tokenize = 'unicode61 remove_diacritics 2'
);

CREATE TRIGGER document_fts_insert AFTER INSERT ON document BEGIN
    INSERT INTO document_fts (document_id, title, synopsis, body, notes)
    VALUES (
        new.id,
        (SELECT title FROM binder_item WHERE id = new.id),
        coalesce(new.synopsis, ''),
        coalesce(new.search_text, ''),
        coalesce(new.notes, ''));
END;

CREATE TRIGGER document_fts_delete AFTER DELETE ON document BEGIN
    DELETE FROM document_fts WHERE document_id = old.id;
END;

CREATE TRIGGER document_fts_update AFTER UPDATE ON document BEGIN
    DELETE FROM document_fts WHERE document_id = old.id;
    INSERT INTO document_fts (document_id, title, synopsis, body, notes)
    VALUES (
        new.id,
        (SELECT title FROM binder_item WHERE id = new.id),
        coalesce(new.synopsis, ''),
        coalesce(new.search_text, ''),
        coalesce(new.notes, ''));
END;

-- A rename must update the index too, or a document stays findable under its old title.
CREATE TRIGGER binder_item_fts_retitle AFTER UPDATE OF title ON binder_item BEGIN
    UPDATE document_fts SET title = new.title WHERE document_id = new.id;
END;
