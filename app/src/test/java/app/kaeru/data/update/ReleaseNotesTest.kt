package app.kaeru.data.update

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A release body as the screen shows it. The app has no markdown renderer, so the markup comes
 * out and the two things that carry meaning without it — the line breaks and the bullets — stay.
 */
class ReleaseNotesTest {

    @Test
    fun `a heading loses its hashes and keeps its words`() {
        assertEquals("Что нового", releaseNotes("## Что нового"))
        assertEquals("Исправления", releaseNotes("###### Исправления"))
    }

    @Test
    fun `a list keeps its bullets`() {
        val body = """
            - плеер не падает на смене озвучки
            * поиск помнит запросы
            + загрузки считают место
        """.trimIndent()

        assertEquals(
            "• плеер не падает на смене озвучки\n• поиск помнит запросы\n• загрузки считают место",
            releaseNotes(body),
        )
    }

    @Test
    fun `a nested list keeps its indent`() {
        assertEquals("• верхний\n  • вложенный", releaseNotes("- верхний\n  - вложенный"))
    }

    @Test
    fun `emphasis comes out and the words stay`() {
        assertEquals("важно и очень важно", releaseNotes("**важно** и *очень важно*"))
        assertEquals("важно и очень важно", releaseNotes("__важно__ и _очень важно_"))
    }

    @Test
    fun `an underscore inside a word is left alone`() {
        assertEquals("файл file_paths.xml добавлен", releaseNotes("файл file_paths.xml добавлен"))
    }

    @Test
    fun `a link keeps its text and loses its address`() {
        assertEquals("см. выпуск", releaseNotes("см. [выпуск](https://github.com/g0ddest/kaeru)"))
    }

    @Test
    fun `an image disappears entirely`() {
        assertEquals("до и после", releaseNotes("до ![снимок](https://example.test/a.png) и после"))
    }

    @Test
    fun `a bare autolink becomes the address itself`() {
        assertEquals(
            "см. https://example.test/x",
            releaseNotes("см. <https://example.test/x>"),
        )
    }

    @Test
    fun `inline code loses its backticks`() {
        assertEquals("поле published_at теперь читается", releaseNotes("поле `published_at` теперь читается"))
    }

    @Test
    fun `line breaks survive`() {
        assertEquals("первая\nвторая", releaseNotes("первая\nвторая"))
        assertEquals("первая\nвторая", releaseNotes("первая\r\nвторая"))
    }

    @Test
    fun `a run of blank lines collapses to one`() {
        assertEquals("первая\n\nвторая", releaseNotes("первая\n\n\n\nвторая"))
    }

    @Test
    fun `a horizontal rule is dropped`() {
        assertEquals("сверху\nснизу", releaseNotes("сверху\n---\nснизу"))
        assertEquals("сверху\nснизу", releaseNotes("сверху\n***\nснизу"))
    }

    @Test
    fun `a blockquote loses its marker`() {
        assertEquals("предупреждение", releaseNotes("> предупреждение"))
    }

    /** The one place the characters are the content, so nothing inside it is touched. */
    @Test
    fun `fenced code is left as written`() {
        val body = "было\n```\nval a = b * c * d\n```\nстало"

        assertEquals("было\nval a = b * c * d\nстало", releaseNotes(body))
    }

    @Test
    fun `an html comment is not shown`() {
        assertEquals("видно", releaseNotes("<!-- скрыто -->видно"))
    }

    @Test
    fun `an html tag is not shown`() {
        assertEquals("жирно", releaseNotes("<b>жирно</b>"))
    }

    @Test
    fun `an escaped asterisk is a plain asterisk`() {
        assertEquals("5 * 3", releaseNotes("""5 \* 3"""))
    }

    @Test
    fun `an empty body is an empty string`() {
        assertEquals("", releaseNotes(null))
        assertEquals("", releaseNotes(""))
        assertEquals("", releaseNotes("   \n\n  "))
    }

    @Test
    fun `a whole release body comes out readable`() {
        val body = """
            ## Что нового

            - **Обновления** внутри приложения
            - Исправлен [плеер](https://example.test/issue/12)

            ### Известные проблемы
            * Каст не проверен
        """.trimIndent()

        assertEquals(
            "Что нового\n\n" +
                "• Обновления внутри приложения\n" +
                "• Исправлен плеер\n\n" +
                "Известные проблемы\n" +
                "• Каст не проверен",
            releaseNotes(body),
        )
    }
}
