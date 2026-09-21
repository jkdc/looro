import XCTest
@testable import LooroCore

/// Mirrors the JVM `WordPracticeTest`; runs under the macOS gate.
final class WordPracticeTests: XCTestCase {
    func testExactFormIsAccepted() {
        XCTAssertTrue(WordPractice.isCorrect(answer: "radio", form: "radio", lemma: "radio"))
    }

    func testCaseAndSurroundingPunctuationAreIgnored() {
        XCTAssertTrue(WordPractice.isCorrect(answer: "  Radio!! ", form: "radio", lemma: "radio"))
        XCTAssertTrue(WordPractice.isCorrect(answer: "“gikk”", form: "gikk", lemma: "å gå"))
    }

    func testTheLemmaIsAcceptedAsWell() {
        XCTAssertTrue(WordPractice.isCorrect(answer: "volere", form: "vorrei", lemma: "volere"))
    }

    func testDiacriticsAreSignificant() {
        XCTAssertFalse(WordPractice.isCorrect(answer: "fragor", form: "frågor", lemma: "fråga"))
        XCTAssertTrue(WordPractice.isCorrect(answer: "FRÅGOR", form: "frågor", lemma: "fråga"))
    }

    func testAnEmptyAnswerIsNeverCorrect() {
        XCTAssertFalse(WordPractice.isCorrect(answer: "   ", form: "radio", lemma: "radio"))
    }

    func testNormalizeCollapsesWhitespaceAndStripsSurroundingPunctuation() {
        XCTAssertEqual(WordPractice.normalize("  ¡Un   buen día! "), "un buen día")
    }

    func testMaskedExampleBlanksTheFormAndReportsAbsenceAsEmpty() {
        XCTAssertEqual(WordPractice.maskedExample("Jeg gikk i skogen.", form: "gikk"), "Jeg ___ i skogen.")
        XCTAssertEqual(WordPractice.maskedExample("Ingen treff her.", form: "gikk"), "")
    }
}
