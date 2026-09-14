package com.elioo.baymax.extraction.adapter.out.persistence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A named parameter that appears in the SQL but is never bound fails only when the statement actually
 * runs, which here meant only inside a Testcontainers test — and on a machine whose Docker daemon was
 * hung, that meant only in CI. Adding a column to the document UPDATE and forgetting its bind cost a
 * red build for exactly that reason.
 *
 * <p>This reads the adapter's own source and checks the two sets agree, so the mistake is caught by a
 * plain unit test in a second, with no database and no container.</p>
 */
class DocumentUpdateBindingTest {

    private static final Path ADAPTER = Path.of(
            "src/main/java/com/elioo/baymax/extraction/adapter/out/persistence/"
                    + "PostgresDocumentRecordAdapter.java");

    private static final Pattern DECLARED = Pattern.compile(":([a-zA-Z][a-zA-Z0-9]*)");
    private static final Pattern BOUND = Pattern.compile("bind(?:OrNull)?\\((?:spec,\\s*)?\"([a-zA-Z0-9]+)\"");

    @Test
    void everyNamedParameterInEveryStatementIsBound() throws IOException {
        String source = Files.readString(ADAPTER, StandardCharsets.UTF_8);

        for (String method : new String[]{"writeDocument", "insertObservations", "insertMedications",
                "insertFollowUps"}) {
            String body = methodBody(source, method);
            Set<String> declared = matches(DECLARED, body);
            Set<String> bound = matches(BOUND, body);

            assertThat(declared)
                    .as("%s declares a parameter it never binds — the statement would fail at run time", method)
                    .isSubsetOf(bound);
            assertThat(bound)
                    .as("%s binds a parameter its SQL does not mention", method)
                    .isSubsetOf(declared);
        }
    }

    /**
     * Everything from the method *declaration* to the closing brace at its indentation.
     *
     * <p>Anchoring on the name alone finds the call site instead — {@code update()} calls
     * {@code writeDocument()} above where it is declared — which extracts a few characters, leaves both
     * sets empty, and makes the subset assertions pass while proving nothing. That is precisely how the
     * first version of this guard went green over a real bug.</p>
     */
    private static String methodBody(String source, String method) {
        Matcher declaration = Pattern
                .compile("(?m)^\\s*private\\s+[\\w.<>,?\\[\\] ]+\\s+" + method + "\\s*\\(")
                .matcher(source);
        assertThat(declaration.find()).as("no declaration of %s found in the adapter", method).isTrue();
        int start = declaration.start();
        int end = source.indexOf("\n    }", start);
        assertThat(end).as("could not find the end of %s", method).isGreaterThan(start);
        String body = source.substring(start, end);
        assertThat(body)
                .as("%s extracted as %d chars — the anchor matched something that is not the method",
                        method, body.length())
                .hasSizeGreaterThan(200)
                .contains("db.sql(");
        return body;
    }

    private static Set<String> matches(Pattern pattern, String text) {
        Set<String> found = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }
}
