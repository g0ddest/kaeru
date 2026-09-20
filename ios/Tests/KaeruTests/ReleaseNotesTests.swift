import XCTest
@testable import Kaeru

/// A release body, as a screen with no markdown renderer on it shows it. The same cases Android's
/// `ReleaseNotesTest` covers: the two apps read one repository's notes.
final class ReleaseNotesTests: XCTestCase {
    func testNothingAtAllIsNothing() {
        XCTAssertEqual(ReleaseNotes.plain(nil), "")
        XCTAssertEqual(ReleaseNotes.plain("   \n  "), "")
    }

    func testAHeadingLosesItsHashes() {
        XCTAssertEqual(ReleaseNotes.plain("## Что нового"), "Что нового")
        XCTAssertEqual(ReleaseNotes.plain("###### Мелочи"), "Мелочи")
    }

    /// The one thing markup does for a release note that is worth keeping.
    func testEveryListMarkerBecomesOneBullet() {
        XCTAssertEqual(ReleaseNotes.plain("- первое\n* второе\n+ третье"), "• первое\n• второе\n• третье")
    }

    func testIndentationOfANestedBulletSurvives() {
        XCTAssertEqual(ReleaseNotes.plain("- первое\n  - вложенное"), "• первое\n  • вложенное")
    }

    func testALinkKeepsItsWordsAndLosesItsAddress() {
        XCTAssertEqual(ReleaseNotes.plain("см. [выпуск](https://example.test/x) на сайте"), "см. выпуск на сайте")
        XCTAssertEqual(ReleaseNotes.plain("см. [выпуск][one]"), "см. выпуск")
        XCTAssertEqual(ReleaseNotes.plain("пишите на <mailto:a@b.test>"), "пишите на mailto:a@b.test")
    }

    /// An image is a link with a `!` in front of it, so it goes first or its alt text survives as
    /// a stray word in the middle of a sentence.
    func testAnImageLeavesNothingBehind() {
        XCTAssertEqual(ReleaseNotes.plain("до ![скриншот](https://example.test/a.png) после"), "до после")
    }

    func testEmphasisIsRemovedWithoutLeavingItsCharacters() {
        XCTAssertEqual(ReleaseNotes.plain("**жирный** и _курсив_ и ~~зачёркнутый~~"), "жирный и курсив и зачёркнутый")
        XCTAssertEqual(ReleaseNotes.plain("__тоже жирный__ и *тоже курсив*"), "тоже жирный и тоже курсив")
    }

    func testInlineCodeKeepsItsWords() {
        XCTAssertEqual(ReleaseNotes.plain("правьте `local.properties`"), "правьте local.properties")
    }

    /// The one part of a note where the characters are the content: stripping asterisks out of a
    /// diff would corrupt it.
    func testFencedCodeIsLeftExactlyAsWritten() {
        let body = "было\n```\n- **не трогать**\n```\nстало"
        XCTAssertEqual(ReleaseNotes.plain(body), "было\n- **не трогать**\nстало")
    }

    func testRulesAndCommentsAndTagsAreGone() {
        XCTAssertEqual(ReleaseNotes.plain("один\n---\nдва"), "один\nдва")
        XCTAssertEqual(ReleaseNotes.plain("один\n***\nдва"), "один\nдва")
        XCTAssertEqual(ReleaseNotes.plain("<!-- служебное\nна двух строках -->видно"), "видно")
        XCTAssertEqual(ReleaseNotes.plain("<b>жирный</b> тег"), "жирный тег")
    }

    func testQuotesLoseTheirAngleBracket() {
        XCTAssertEqual(ReleaseNotes.plain("> цитата"), "цитата")
    }

    /// The same case Android's `an escaped asterisk is a plain asterisk` pins, and only that case:
    /// a *pair* of escaped asterisks is read as emphasis by both apps before either gets to the
    /// backslashes, so `\*не\*` comes out as `\не\` on the phone and on the television alike. Left
    /// alone rather than fixed here — the two apps read one repository's notes, and a note that
    /// renders differently on the two phones is the thing this port exists to avoid.
    func testAnEscapedAsteriskIsAPlainAsterisk() {
        XCTAssertEqual(ReleaseNotes.plain("5 \\* 3"), "5 * 3")
    }

    /// Blank lines are how a note is read; three of them in a row are not.
    func testARunOfBlankLinesBecomesOne() {
        XCTAssertEqual(ReleaseNotes.plain("один\n\n\n\nдва"), "один\n\nдва")
    }

    func testAWholeNoteReadsAsProse() {
        let body = """
        ## Что нового

        - **Обновления** внутри приложения
        - Исправлен [плеер](https://example.test/issue/7)

        <!-- черновик -->
        """
        XCTAssertEqual(ReleaseNotes.plain(body), "Что нового\n\n• Обновления внутри приложения\n• Исправлен плеер")
    }
}
