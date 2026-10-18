package com.elioo.baymax.web.adapter.in.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The line reader behind the duplicate-key and every-part-loaded checks, on every shape Properties accepts. */
class BundleFilesTest {

    /** Properties accepts '=', ':' or plain whitespace between key and value; a duplicate in any of them is a duplicate. */
    @Test
    void aKeyWrittenWithAnySeparatorIsAKey() {
        List<String> lines = List.of("# owner: shell", "sheet.close=বন্ধ", "sheet.open : খুলুন", "sheet.close Close",
                "sheet.back\tফিরে যান", "sheet.empty", "long.one=first \\", "  continued.not.a.key=second", "! a comment");
        assertThat(BundleFiles.duplicatesWithin(lines)).containsExactly("sheet.close");
        assertThat(BundleFiles.keys(lines)).containsOnlyKeys("sheet.close", "sheet.open", "sheet.back", "sheet.empty", "long.one");
    }

    /** Distinct keys sharing a prefix are not one key written twice (the dangerous direction for a widened pattern). */
    @Test
    void keysThatShareAPrefixAreNotDuplicates() {
        assertThat(BundleFiles.duplicatesWithin(List.of("a.b=x", "a.b.c=y", "a.bc z", "a.b2:w"))).isEmpty();
    }

    @Test
    void everyPartNameIsAPartFileAndNothingElseIs() {
        assertThat(List.of("ui_bn.properties", "ui-shell_en.properties", "ui-family-tree_bn.properties", "ui-step2_en.properties"))
                .allMatch(n -> BundleFiles.PART_FILE.matcher(n).matches());
        assertThat(List.of("messages_bn.properties", "ui_fr.properties", "ui-shell.properties", "uix_bn.properties", "ui-_bn.properties"))
                .noneMatch(n -> BundleFiles.PART_FILE.matcher(n).matches());
    }
}
