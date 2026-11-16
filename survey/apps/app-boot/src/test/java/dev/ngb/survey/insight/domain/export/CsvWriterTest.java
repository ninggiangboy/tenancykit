package dev.ngb.survey.insight.domain.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The CSV writer's two jobs: a cell that a spreadsheet would run as a formula is neutralised, and a cell that contains a
 * separator or a quote is escaped the RFC 4180 way.
 */
class CsvWriterTest {

    @Test
    void aCellStartingWithAFormulaCharacterIsNeutralised() {
        assertThat(CsvWriter.cell("=1+1")).isEqualTo("'=1+1");
        assertThat(CsvWriter.cell("+SUM(A1)")).isEqualTo("'+SUM(A1)");
        assertThat(CsvWriter.cell("-2+2")).isEqualTo("'-2+2");
        assertThat(CsvWriter.cell("@import")).isEqualTo("'@import");
    }

    @Test
    void anOrdinaryValueIsLeftAlone() {
        assertThat(CsvWriter.cell("hello")).isEqualTo("hello");
        assertThat(CsvWriter.cell("")).isEmpty();
        assertThat(CsvWriter.cell(null)).isEmpty();
    }

    @Test
    void aCellWithASeparatorAQuoteOrANewlineIsQuoted() {
        assertThat(CsvWriter.cell("a,b")).isEqualTo("\"a,b\"");
        assertThat(CsvWriter.cell("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(CsvWriter.cell("line\nbreak")).isEqualTo("\"line\nbreak\"");
    }

    @Test
    void aRowJoinsItsCellsAndEndsWithANewline() {
        assertThat(CsvWriter.row(List.of("a", "b", "c"))).isEqualTo("a,b,c\n");
        assertThat(CsvWriter.row(List.of("a,b", "c"))).isEqualTo("\"a,b\",c\n");
    }

    @Test
    void aFormulaThatAlsoContainsACommaIsBothNeutralisedAndQuoted() {
        assertThat(CsvWriter.cell("=SUM(A1,B1)")).isEqualTo("\"'=SUM(A1,B1)\"");
    }
}
