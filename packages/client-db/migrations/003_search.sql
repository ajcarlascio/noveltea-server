-- 003_search: offline full-text search.
--
-- The server uses a generated tsvector + GIN. SQLite's equivalent is FTS5 over an
-- external content table, which stores no duplicate copy of the text — the index
-- points back into `document` by rowid.
CREATE VIRTUAL TABLE document_fts USING fts5(
    search_text,
    content = 'document',
    content_rowid = 'rowid',
    tokenize = 'unicode61 remove_diacritics 2'
);

-- External-content FTS5 tables are not maintained automatically; these triggers
-- are mandatory, and the 'delete' command form is how fts5 retracts a row.
CREATE TRIGGER document_fts_insert AFTER INSERT ON document BEGIN
    INSERT INTO document_fts (rowid, search_text) VALUES (new.rowid, new.search_text);
END;

CREATE TRIGGER document_fts_delete AFTER DELETE ON document BEGIN
    INSERT INTO document_fts (document_fts, rowid, search_text)
        VALUES ('delete', old.rowid, old.search_text);
END;

CREATE TRIGGER document_fts_update AFTER UPDATE ON document BEGIN
    INSERT INTO document_fts (document_fts, rowid, search_text)
        VALUES ('delete', old.rowid, old.search_text);
    INSERT INTO document_fts (rowid, search_text) VALUES (new.rowid, new.search_text);
END;
