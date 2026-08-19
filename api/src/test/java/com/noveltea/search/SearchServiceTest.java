package com.noveltea.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noveltea.binder.BinderService;
import com.noveltea.search.SearchService.SearchHit;
import com.noveltea.support.AbstractPostgresTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SearchServiceTest extends AbstractPostgresTest {

    @Autowired SearchService search;
    @Autowired BinderService binder;

    /** Creates a document with body text, and optionally a synopsis and notes. */
    private UUID writeDocument(String title, String body, String synopsis, String notes) {
        UUID id = binder.create(projectId, deviceA, null, "document", title, null);
        jdbc.sql("""
                INSERT INTO document (id, content, search_text, synopsis, notes, word_count)
                VALUES (:id, CAST(:content AS jsonb), :body, :synopsis, :notes, 10)
                """)
                .param("id", id).param("content", doc(body)).param("body", body)
                .param("synopsis", synopsis).param("notes", notes)
                .update();
        return id;
    }

    private List<String> titlesFrom(List<SearchHit> hits) {
        return hits.stream().map(SearchHit::title).toList();
    }

    // ----------------------------------------------------------------- basics

    @Test
    @DisplayName("finds a document by a word in its body")
    void findsByBody() {
        writeDocument("Chapter One", "the lighthouse keeper waited for morning", null, null);
        writeDocument("Chapter Two", "she took the train south", null, null);

        var results = search.search(projectId, "lighthouse", false, 20);

        assertThat(titlesFrom(results.hits())).containsExactly("Chapter One");
        assertThat(results.hits().get(0).snippet()).contains("lighthouse");
    }

    @Test
    @DisplayName("finds a document by its title, and says the title is why")
    void findsByTitle() {
        writeDocument("The Lighthouse", "she took the train south", null, null);

        var hit = search.search(projectId, "lighthouse", false, 20).hits().get(0);

        assertThat(hit.title()).isEqualTo("The Lighthouse");
        assertThat(hit.matchedTitle()).as("a client should be able to explain the match").isTrue();
    }

    @Test
    @DisplayName("finds folders too — they have titles worth searching")
    void findsFolders() {
        binder.create(projectId, deviceA, null, "folder", "Act Two: The Lighthouse", null);

        var results = search.search(projectId, "lighthouse", false, 20);

        assertThat(results.hits()).singleElement()
                .satisfies(hit -> assertThat(hit.type()).isEqualTo("folder"));
    }

    @Test
    @DisplayName("stemming works: searching a root finds its inflections")
    void stemsWords() {
        writeDocument("Chapter One", "she waited by the burning lamps", null, null);

        assertThat(search.search(projectId, "burn", false, 20).hits()).hasSize(1);
        assertThat(search.search(projectId, "lamp", false, 20).hits()).hasSize(1);
    }

    @Test
    @DisplayName("a search that matches nothing returns nothing, not an error")
    void noMatches() {
        writeDocument("Chapter One", "the lighthouse keeper", null, null);
        assertThat(search.search(projectId, "helicopter", false, 20).hits()).isEmpty();
    }

    // --------------------------------------------------------------- ranking

    @Test
    @DisplayName("a title match outranks a body match")
    void titleOutranksBody() {
        writeDocument("An Ordinary Chapter",
                "the lighthouse stood there and the lighthouse was cold and the lighthouse waited",
                null, null);
        writeDocument("The Lighthouse", "she took the train south", null, null);

        var hits = search.search(projectId, "lighthouse", false, 20).hits();

        assertThat(titlesFrom(hits).get(0))
                .as("someone searching a name usually wants the thing called that")
                .isEqualTo("The Lighthouse");
    }

    @Test
    @DisplayName("a synopsis match outranks a note match")
    void synopsisOutranksNotes() {
        writeDocument("Alpha", "unrelated prose", null, "a note mentioning the lighthouse");
        writeDocument("Beta", "unrelated prose", "the lighthouse scene", null);

        var hits = search.search(projectId, "lighthouse", false, 20).hits();
        assertThat(titlesFrom(hits)).containsExactly("Beta", "Alpha");
    }

    // ------------------------------------------------ the author's own notes

    @Test
    @DisplayName("SYNOPSES AND NOTES ARE SEARCHABLE even though they are never exported")
    void notesAreSearchableButNotExported() {
        writeDocument("Chapter Nine", "prose with nothing useful in it",
                "she confronts the keeper", "remember to check the tide times");

        assertThat(titlesFrom(search.search(projectId, "keeper", false, 20).hits()))
                .as("a synopsis the author cannot search is write-only")
                .containsExactly("Chapter Nine");
        assertThat(titlesFrom(search.search(projectId, "tide", false, 20).hits()))
                .containsExactly("Chapter Nine");
    }

    // ----------------------------------------------------------- query syntax

    @Test
    @DisplayName("a quoted phrase matches only the phrase")
    void phraseSearch() {
        writeDocument("Alpha", "the lighthouse keeper waited", null, null);
        writeDocument("Beta", "the keeper left the lighthouse", null, null);

        var hits = search.search(projectId, "\"lighthouse keeper\"", false, 20).hits();
        assertThat(titlesFrom(hits)).containsExactly("Alpha");
    }

    @Test
    @DisplayName("a minus sign excludes")
    void exclusion() {
        writeDocument("Alpha", "the lighthouse keeper waited", null, null);
        writeDocument("Beta", "the lighthouse stood empty", null, null);

        var hits = search.search(projectId, "lighthouse -keeper", false, 20).hits();
        assertThat(titlesFrom(hits)).containsExactly("Beta");
    }

    @Test
    @DisplayName("punctuation and operators in author input cannot break the query")
    void hostileInputIsHarmless() {
        writeDocument("Alpha", "the lighthouse keeper waited", null, null);

        for (String query : List.of("'; DROP TABLE document; --", "&&&", "()", "!!!", "a & | b")) {
            assertThat(search.search(projectId, query, false, 20).hits())
                    .as("query %s must be survivable", query)
                    .isNotNull();
        }
        assertThat(jdbc.sql("SELECT count(*) FROM document").query(Long.class).single())
                .as("nothing may have been executed")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("an empty search is refused rather than returning the whole binder")
    void emptyQueryRefused() {
        assertThatThrownBy(() -> search.search(projectId, "   ", false, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> search.search(projectId, null, false, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> search.search(null, "x", false, 20))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("projectId");
    }

    // ---------------------------------------------------------------- scoping

    @Test
    @DisplayName("search never crosses a project boundary")
    void neverCrossesProjects() {
        UUID otherUser = UUID.randomUUID();
        UUID otherProject = UUID.randomUUID();
        jdbc.sql("INSERT INTO app_user (id, email) VALUES (:id, :e)")
                .param("id", otherUser).param("e", otherUser + "@example.com").update();
        jdbc.sql("INSERT INTO project (id, owner_id, title) VALUES (:id, :o, 'Theirs')")
                .param("id", otherProject).param("o", otherUser).update();
        UUID theirs = binder.create(otherProject, deviceA, null, "document", "Their Lighthouse", null);
        jdbc.sql("INSERT INTO document (id, search_text) VALUES (:id, 'their secret lighthouse prose')")
                .param("id", theirs).update();

        writeDocument("Mine", "my own lighthouse", null, null);

        var results = search.search(projectId, "lighthouse", false, 20);
        assertThat(titlesFrom(results.hits())).containsExactly("Mine");
        assertThat(results.toString()).doesNotContain("secret");
    }

    @Test
    @DisplayName("trashed items are excluded by default and findable on request")
    void trashedItemsAreOptional() {
        UUID id = writeDocument("Cut Scene", "the lighthouse at dawn", null, null);
        binder.trash(id, deviceA);

        assertThat(search.search(projectId, "lighthouse", false, 20).hits())
                .as("a search should not surface things the author threw away")
                .isEmpty();

        var included = search.search(projectId, "lighthouse", true, 20).hits();
        assertThat(included).singleElement().satisfies(hit -> {
            assertThat(hit.title()).isEqualTo("Cut Scene");
            assertThat(hit.inTrash()).as("the client must be able to label it").isTrue();
        });
    }

    @Test
    @DisplayName("a tombstoned item is gone from search entirely")
    void tombstonedItemsNeverAppear() {
        UUID id = writeDocument("Deleted Scene", "the lighthouse at dusk", null, null);
        binder.trash(id, deviceA);
        binder.emptyTrash(projectId, deviceA);

        assertThat(search.search(projectId, "lighthouse", true, 20).hits()).isEmpty();
    }

    // ----------------------------------------------------------------- limits

    @Test
    @DisplayName("results are capped and the caller is told when there are more")
    void resultsAreCapped() {
        for (int i = 0; i < 8; i++) {
            writeDocument("Chapter " + i, "the lighthouse in scene " + i, null, null);
        }

        var page = search.search(projectId, "lighthouse", false, 3);

        assertThat(page.hits()).hasSize(3);
        assertThat(page.truncated()).as("a client must know to offer 'show more'").isTrue();

        var everything = search.search(projectId, "lighthouse", false, 50);
        assertThat(everything.hits()).hasSize(8);
        assertThat(everything.truncated()).isFalse();
    }

    @Test
    @DisplayName("a document with no body still matches on its title")
    void emptyDocumentsStillMatchByTitle() {
        binder.create(projectId, deviceA, null, "document", "The Lighthouse", null);

        var hits = search.search(projectId, "lighthouse", false, 20).hits();
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).snippet()).as("nothing to quote from").isNull();
    }
}
