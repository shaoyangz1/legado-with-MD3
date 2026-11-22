package io.legado.app.help.book

import io.legado.app.data.entities.Book
import org.junit.Assert.assertEquals
import org.junit.Test

/** 标签里的单独 `|` 是换行标记，不能被任何一次去重压掉。 */
class BookTagRowsTest {

    private fun book() = Book(
        name = "n",
        author = "a",
        bookUrl = "https://example.com/book",
        kind = "甲|https://e.com/1,|,乙|https://e.com/2,|,丙|https://e.com/3",
    )

    @Test
    fun displayTagsKeepRowBreaks() {
        assertEquals(
            listOf(
                "甲|https://e.com/1",
                "|",
                "乙|https://e.com/2",
                "|",
                "丙|https://e.com/3",
            ),
            book().getDisplayTagList(),
        )
    }

    @Test
    fun upKindKeepsRowBreaks() {
        val book = book()
        book.upKind()
        assertEquals(
            "甲|https://e.com/1,|,乙|https://e.com/2,|,丙|https://e.com/3",
            book.kind,
        )
    }
}
