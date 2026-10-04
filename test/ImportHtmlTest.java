import lcforge.Importer;
import lcforge.Json;

import java.util.*;

/**
 * The extension's import path: statement HTML for expected outputs, LeetCode's own
 * exampleTestcaseList for inputs, and metaData for the signature.
 */
public class ImportHtmlTest {

    static int passed = 0, failed = 0;

    static final String HTML = """
        <p>Given an array of integers <code>nums</code>&nbsp;and an integer <code>target</code>,\
         return <em>indices of the two numbers</em>.</p>
        <p>&nbsp;</p>
        <p><strong class="example">Example 1:</strong></p>
        <pre><strong>Input:</strong> nums = [2,7,11,15], target = 9
        <strong>Output:</strong> [0,1]
        <strong>Explanation:</strong> Because nums[0] + nums[1] == 9, we return [0, 1].
        </pre>
        <p><strong class="example">Example 2:</strong></p>
        <pre><strong>Input:</strong> nums = [3,2,4], target = 6
        <strong>Output:</strong> [1,2]
        </pre>
        """;

    static final String META = """
        {
          "name": "twoSum",
          "params": [
            { "name": "nums",   "type": "integer[]" },
            { "name": "target", "type": "integer" }
          ],
          "return": { "type": "integer[]", "size": 2 }
        }
        """;

    public static void main(String[] args) {

        // --- expected outputs mined out of the statement markup ---
        List<Object> fromHtml = Importer.testcasesFrom(HTML);
        eq("html: case count", fromHtml.size(), 2);
        eq("html: input 1", Json.str(fromHtml.get(0), "input", ""), "[2,7,11,15]\n9");
        eq("html: expected 1", Json.str(fromHtml.get(0), "expected", ""), "[0,1]");
        eq("html: input 2", Json.str(fromHtml.get(1), "input", ""), "[3,2,4]\n6");
        eq("html: expected 2", Json.str(fromHtml.get(1), "expected", ""), "[1,2]");

        // --- authoritative inputs merged with those outputs ---
        List<Object> exampleInputs = List.of("[2,7,11,15]\n9", "[3,2,4]\n6", "[3,3]\n6");
        List<Object> merged = Importer.mergeCases(exampleInputs, fromHtml);
        eq("merge: uses every LeetCode input", merged.size(), 3);
        eq("merge: expected paired by content", Json.str(merged.get(1), "expected", ""), "[1,2]");
        // LeetCode listed three examples but the statement only yielded two outputs; the extra
        // case must still arrive, just with a blank expected field to fill in.
        eq("merge: unmatched input kept", Json.str(merged.get(2), "input", ""), "[3,3]\n6");
        eq("merge: unmatched expected blank", Json.str(merged.get(2), "expected", ""), "");

        // THE failure that index-pairing causes: LeetCode lists a case the statement skipped, so
        // every later case slides onto someone else's answer. Matching by input must prevent it.
        List<Object> shifted = Importer.mergeCases(
            List.of("[2,7,11,15]\n9", "[0]\n[0]", "[3,2,4]\n6"), fromHtml);
        eq("merge: gap does not shift case 1", Json.str(shifted.get(0), "expected", ""), "[0,1]");
        eq("merge: unknown case gets no answer", Json.str(shifted.get(1), "expected", ""), "");
        eq("merge: later case keeps its own answer", Json.str(shifted.get(2), "expected", ""), "[1,2]");

        // Whitespace differs freely between the two sources; it must not defeat matching.
        eq("merge: whitespace-insensitive",
           Json.str(Importer.mergeCases(List.of("[2, 7, 11, 15]\n9"), fromHtml).get(0), "expected", ""),
           "[0,1]");

        // --- the signature ---
        Map<String,Object> meta = Importer.parseMeta(META);
        eq("meta: entry method", meta.get("entry"), "twoSum");
        eq("meta: param names", meta.get("paramNames"), List.of("nums", "target"));
        eq("meta: param types", meta.get("paramTypes"), List.of("integer[]", "integer"));
        eq("meta: return type", meta.get("returnType"), "integer[]");
        eq("meta: empty input is harmless", Importer.parseMeta("").size(), 0);
        eq("meta: garbage is harmless", Importer.parseMeta("not json").size(), 0);

        System.out.println(failed == 0
            ? "Importer: all " + passed + " checks passed"
            : "Importer: " + passed + " passed, " + failed + " FAILED");
        if (failed > 0) System.exit(1);
    }

    static void eq(String name, Object got, Object want) {
        if (Objects.equals(got, want)) { passed++; }
        else {
            failed++;
            System.out.println("  FAIL " + name + "\n     got:  " + got + "\n     want: " + want);
        }
    }
}
