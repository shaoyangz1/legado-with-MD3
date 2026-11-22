package io.legado.app.ui.book.explore

import io.legado.app.data.entities.SearchBook
import org.junit.Assert.assertEquals
import org.junit.Test

class ExploreShowDedupTest {

    private fun book(url: String, name: String = "name", author: String = "author") = SearchBook(
        bookUrl = url,
        name = name,
        author = author,
    )

    @Test
    fun skipsSameUrl() {
        val existing = listOf(book("u1", "作品A", "演员A"))
        val incoming = listOf(book("u1", "作品A", "演员A"), book("u2", "作品B", "演员B"))
        assertEquals(listOf("u2"), incoming.newBooksNotIn(existing).map { it.bookUrl })
    }

    @Test
    fun skipsSameNameAndAuthorWithDifferentUrl() {
        val existing = listOf(book("u1", "作品A", "演员A"))
        val incoming = listOf(book("u1?page=2", "作品A", "演员A"))
        assertEquals(emptyList<String>(), incoming.newBooksNotIn(existing).map { it.bookUrl })
    }

    @Test
    fun keepsSameNameWithDifferentAuthor() {
        val existing = listOf(book("u1", "作品A", "演员A"))
        val incoming = listOf(book("u2", "作品A", "演员B"))
        assertEquals(listOf("u2"), incoming.newBooksNotIn(existing).map { it.bookUrl })
    }

    @Test
    fun dropsEntryWhoseUrlIsTheListPageItself() {
        val listUrl = "https://example.com/ja/makers/list"
        val incoming = listOf(
            book("https://example.com/ja/item-001", "作品1", "演员"),
            book("$listUrl?page=2", "列表页自身", "演员"),
        )
        assertEquals(
            listOf("https://example.com/ja/item-001"),
            incoming.excludingListPage(listUrl).map { it.bookUrl },
        )
    }

    @Test
    fun keepsEntriesWhenListUrlMissing() {
        val incoming = listOf(book("https://example.com/ja/item-001", "作品1", "演员"))
        assertEquals(incoming, incoming.excludingListPage(null))
    }
}
