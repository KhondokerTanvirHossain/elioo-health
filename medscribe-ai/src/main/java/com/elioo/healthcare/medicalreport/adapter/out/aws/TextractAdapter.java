package com.elioo.healthcare.medicalreport.adapter.out.aws;

import com.elioo.healthcare.aws.textract.api.TextractService;
import com.elioo.healthcare.aws.textract.model.ExtractedBlock;
import com.elioo.healthcare.aws.textract.model.OcrRequest;
import com.elioo.healthcare.medicalreport.application.port.out.OcrPort;
import com.elioo.healthcare.medicalreport.domain.TestResult;
import com.elioo.healthcare.medicalreport.domain.TestStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AWS Textract implementation of OcrPort using the Textract Service library.
 *
 * <p>This adapter demonstrates the power of the elioo-aws-textract library:</p>
 * <ul>
 *   <li>✅ <b>No manual AWS SDK calls</b> - library handles it</li>
 *   <li>✅ <b>Clean DTO mapping</b> - library DTOs to domain objects</li>
 *   <li>✅ <b>Simplified error handling</b> - library provides consistent errors</li>
 *   <li>✅ <b>Reusable across applications</b> - not tied to medscribe-ai</li>
 * </ul>
 *
 * <p>Architecture: Outbound Adapter (Driven Adapter) in Hexagonal Architecture</p>
 * <ul>
 *   <li>Implements the business-defined port interface ({@link OcrPort})</li>
 *   <li>Delegates to {@link TextractService} from library</li>
 *   <li>Maps between medscribe-ai domain objects and library DTOs</li>
 *   <li>Acts as Anti-Corruption Layer between domain and library</li>
 * </ul>
 *
 * @see OcrPort
 * @see TextractService
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ocr.provider", havingValue = "aws", matchIfMissing = false)
public class TextractAdapter implements OcrPort {

    private final TextractService textractService;

    private static final double MIN_CONFIDENCE_THRESHOLD = 0.80;

    @Override
    public Flux<TestResult> extractMedicalData(
            String imageBase64,
            String reportType,
            Map<String, Object> processingOptions
    ) {
        log.info("Starting OCR extraction for report type: {}", reportType);

        // Map to library DTO
        OcrRequest libraryRequest = OcrRequest.standard(imageBase64);

        // Call library (handles AWS SDK, invocation, parsing)
        return textractService.analyzeDocument(libraryRequest)
                .doOnSuccess(response -> log.info("Textract analysis completed. Blocks: {}", response.getBlockCount()))
                .doOnError(error -> log.error("Textract analysis failed", error))
                .flatMapMany(response -> extractTestResultsFromLibraryResponse(response.blocks(), reportType))
                .onErrorResume(error -> {
                    log.error("Error extracting medical data", error);
                    return Flux.error(new OcrException("Failed to extract medical data", error));
                });
    }

    @Override
    public Mono<String> extractRawText(String imageBase64, String language) {
        log.info("Extracting raw text in language: {}", language);

        // Call library service directly
        return textractService.detectText(imageBase64)
                .doOnSuccess(text -> log.info("Raw text extracted. Length: {}", text.length()))
                .onErrorResume(error -> {
                    log.error("Error extracting raw text", error);
                    return Mono.error(new OcrException("Failed to extract raw text", error));
                });
    }

    @Override
    public Mono<ImageQualityResult> validateImageQuality(String imageBase64) {
        log.info("Validating image quality");

        // Call library service directly
        return textractService.validateImageQuality(imageBase64)
                .map(libraryResult -> new ImageQualityResult(
                        libraryResult.isValid(),
                        libraryResult.qualityScore(),
                        libraryResult.message(),
                        libraryResult.metrics()
                ))
                .onErrorResume(error -> {
                    log.error("Error validating image quality", error);
                    return Mono.error(new OcrException("Failed to validate image quality", error));
                });
    }

    @Override
    public Mono<Double> getProcessingConfidence(String reportType) {
        // Confidence varies by report type based on Textract capabilities
        return Mono.just(switch (reportType) {
            case "BLOOD_TEST", "URINE_TEST" -> 0.90; // High confidence for tabular data
            case "RADIOLOGY" -> 0.75; // Lower confidence for image-heavy reports
            case "PATHOLOGY" -> 0.80; // Medium confidence
            case "PRESCRIPTION" -> 0.85; // Good confidence for forms
            default -> 0.70; // Default confidence
        });
    }

    // ========================================================================
    // Mapping Methods: library DTOs ↔ medscribe-ai domain
    // ========================================================================

    /**
     * Extract test results from library ExtractedBlock objects.
     *
     * <p>This method implements a multi-strategy approach:</p>
     * <ol>
     *   <li><b>Primary: Raw text analysis</b> - Works for all document types (prescriptions, notes, reports)</li>
     *   <li><b>Enhancement: Table parsing</b> - Adds structured data if tables exist</li>
     *   <li><b>Fallback: Form extraction</b> - For form-based documents</li>
     * </ol>
     *
     * <p>This ensures the adapter works regardless of document format.</p>
     */
    private Flux<TestResult> extractTestResultsFromLibraryResponse(List<ExtractedBlock> blocks, String reportType) {
        log.debug("Extracting test results from {} blocks for report type: {}", blocks.size(), reportType);

        List<TestResult> results = new ArrayList<>();

        // Step 1: PRIMARY - Extract raw text and parse it
        String rawText = extractRawTextFromBlocks(blocks);
        if (rawText != null && !rawText.isEmpty()) {
            log.debug("Raw text length: {} chars", rawText.length());
            List<TestResult> textBasedResults = parseTestResultsFromRawText(rawText, reportType);
            results.addAll(textBasedResults);
            log.info("Extracted {} test results from raw text analysis", textBasedResults.size());
        }

        // Step 2: ENHANCEMENT - If tables exist, parse them for additional structure
        // Table parsing can provide better confidence scores and structured data
        List<ExtractedBlock> tables = blocks.stream()
                .filter(ExtractedBlock::isTable)
                .toList();

        if (!tables.isEmpty()) {
            log.debug("Found {} tables, attempting table parsing for enhanced data", tables.size());
            for (ExtractedBlock table : tables) {
                List<TestResult> tableResults = parseTableBlock(table, blocks);
                // Merge with existing results or replace if table parsing is more accurate
                results = mergeResults(results, tableResults);
            }
            log.info("After table enhancement: {} total test results", results.size());
        }

        // Step 3: FALLBACK - Try form-based extraction if we still have no results
        if (results.isEmpty()) {
            log.debug("No results from text or tables, trying form-based extraction");
            results.addAll(parseFormBlocks(blocks));
        }

        log.info("Final extraction: {} test results", results.size());
        return Flux.fromIterable(results);
    }

    /**
     * Extract raw text from all blocks.
     * Combines text from LINE and WORD blocks to reconstruct the document text.
     */
    private String extractRawTextFromBlocks(List<ExtractedBlock> blocks) {
        StringBuilder text = new StringBuilder();

        blocks.stream()
                .filter(block -> block.isLine() || (block.isWord() && block.text() != null))
                .forEach(block -> {
                    if (block.text() != null && !block.text().isBlank()) {
                        text.append(block.text());
                        if (block.isLine()) {
                            text.append("\n"); // Preserve line breaks
                        } else {
                            text.append(" "); // Add space between words
                        }
                    }
                });

        return text.toString().trim();
    }

    /**
     * Parse test results from raw text using pattern matching and NLP.
     *
     * <p>Supports multiple document formats:</p>
     * <ul>
     *   <li><b>Lab Reports:</b> "Test Name: Value Unit (Range)"</li>
     *   <li><b>Prescriptions:</b> "Drug Name Dosage Route Frequency"</li>
     *   <li><b>Clinical Notes:</b> Free-form text with embedded values</li>
     * </ul>
     */
    private List<TestResult> parseTestResultsFromRawText(String rawText, String reportType) {
        List<TestResult> results = new ArrayList<>();

        // Split text into lines for processing
        String[] lines = rawText.split("\n");

        for (String line : lines) {
            if (line.trim().isEmpty()) {
                continue;
            }

            // Try multiple parsing strategies
            TestResult result = null;

            // Pattern 1: "Test Name: Value Unit (Range)" - Common in lab reports
            result = tryPatternLabReport(line);
            if (result != null) {
                results.add(result);
                continue;
            }

            // Pattern 2: "Test Name Value Unit Range" - Space-separated format
            result = tryPatternSpaceSeparated(line);
            if (result != null) {
                results.add(result);
                continue;
            }

            // Pattern 3: "Test Name: Value" - Simple format
            result = tryPatternSimple(line);
            if (result != null) {
                results.add(result);
            }
        }

        log.debug("Parsed {} test results from {} lines of text", results.size(), lines.length);
        return results;
    }

    /**
     * Try to parse lab report pattern: "Test Name: Value Unit (Range)"
     * Example: "Serum Creatinine: 135.0 µmol/L (Male: 59-104, Female: 45-84)"
     */
    private TestResult tryPatternLabReport(String line) {
        // Pattern with colon, value, optional unit, and optional range in parentheses
        Pattern pattern = Pattern.compile(
                "([\\w\\s\\-]+):\\s*([\\d.]+)\\s*([\\w/µ°]+)?\\s*(?:\\(([^)]+)\\))?",
                Pattern.CASE_INSENSITIVE
        );

        Matcher matcher = pattern.matcher(line);
        if (matcher.find()) {
            String testName = matcher.group(1).trim();
            String testValue = matcher.group(2).trim();
            String unit = matcher.group(3) != null ? matcher.group(3).trim() : "";
            String referenceRange = matcher.group(4) != null ? matcher.group(4).trim() : "";

            // Filter out non-medical test names
            if (isLikelyTestName(testName)) {
                TestStatus status = determineTestStatusFromValue(testValue, referenceRange);

                return TestResult.builder()
                        .testName(testName)
                        .testValue(testValue)
                        .unit(unit)
                        .referenceRange(referenceRange)
                        .status(status)
                        .confidence(0.85) // High confidence for this pattern
                        .build();
            }
        }

        return null;
    }

    /**
     * Try to parse space-separated pattern: "Test Name Value Unit Range"
     * Example: "Sodium 138.0 mmol/L 136-145"
     */
    private TestResult tryPatternSpaceSeparated(String line) {
        // Split by multiple spaces or tabs
        String[] parts = line.trim().split("\\s{2,}|\\t+");

        if (parts.length >= 2) {
            String testName = parts[0].trim();
            String testValue = extractNumericValue(parts[1].trim());

            if (testValue != null && isLikelyTestName(testName)) {
                String unit = parts.length >= 3 ? parts[2].trim() : "";
                String referenceRange = parts.length >= 4 ? parts[3].trim() : "";

                TestStatus status = determineTestStatusFromValue(testValue, referenceRange);

                return TestResult.builder()
                        .testName(testName)
                        .testValue(testValue)
                        .unit(unit)
                        .referenceRange(referenceRange)
                        .status(status)
                        .confidence(0.75) // Medium confidence
                        .build();
            }
        }

        return null;
    }

    /**
     * Try to parse simple pattern: "Test Name: Value"
     * Example: "Glucose: 95"
     */
    private TestResult tryPatternSimple(String line) {
        Pattern pattern = Pattern.compile(
                "([\\w\\s\\-]+):\\s*([\\d.]+)",
                Pattern.CASE_INSENSITIVE
        );

        Matcher matcher = pattern.matcher(line);
        if (matcher.find()) {
            String testName = matcher.group(1).trim();
            String testValue = matcher.group(2).trim();

            if (isLikelyTestName(testName)) {
                return TestResult.builder()
                        .testName(testName)
                        .testValue(testValue)
                        .unit("")
                        .referenceRange("")
                        .status(TestStatus.NORMAL) // No range to determine status
                        .confidence(0.60) // Lower confidence
                        .build();
            }
        }

        return null;
    }

    /**
     * Check if a string is likely a medical test name.
     * Filters out common non-test phrases.
     */
    private boolean isLikelyTestName(String name) {
        if (name == null || name.length() < 3) {
            return false;
        }

        String lower = name.toLowerCase();

        // Filter out common non-test terms
        String[] excludeTerms = {
                "date", "time", "patient", "doctor", "hospital", "page",
                "report", "laboratory", "phone", "address", "email"
        };

        for (String exclude : excludeTerms) {
            if (lower.equals(exclude) || lower.startsWith(exclude + " ")) {
                return false;
            }
        }

        // Medical test names usually contain letters and possibly numbers/hyphens
        return name.matches("^[A-Za-z][A-Za-z0-9\\s\\-]*$");
    }

    /**
     * Merge results from different extraction strategies.
     * Keeps the result with higher confidence if duplicates exist.
     */
    private List<TestResult> mergeResults(List<TestResult> existing, List<TestResult> newResults) {
        Map<String, TestResult> resultMap = new LinkedHashMap<>();

        // Add existing results
        for (TestResult result : existing) {
            resultMap.put(result.getTestName().toLowerCase(), result);
        }

        // Add or replace with new results if they have higher confidence
        for (TestResult newResult : newResults) {
            String key = newResult.getTestName().toLowerCase();
            TestResult existingResult = resultMap.get(key);

            if (existingResult == null || newResult.getConfidence() > existingResult.getConfidence()) {
                resultMap.put(key, newResult);
            }
        }

        return new ArrayList<>(resultMap.values());
    }

    /**
     * Parse table blocks to extract test results.
     * This method groups cells by row and parses each row as a complete test result.
     */
    private List<TestResult> parseTableBlock(ExtractedBlock tableBlock, List<ExtractedBlock> allBlocks) {
        List<TestResult> results = new ArrayList<>();

        if (tableBlock.relationships() == null) {
            log.warn("Table block has no relationships, skipping");
            return results;
        }

        // Build a map of all blocks for quick lookup
        Map<String, ExtractedBlock> blockMap = new HashMap<>();
        for (ExtractedBlock block : allBlocks) {
            blockMap.put(block.id(), block);
        }

        // Step 1: Collect all cells from the table
        List<ExtractedBlock> cells = new ArrayList<>();
        tableBlock.relationships().stream()
                .filter(rel -> rel.type() != null && rel.type().equalsIgnoreCase("CHILD"))
                .forEach(rel -> {
                    rel.ids().forEach(cellId -> {
                        ExtractedBlock cellBlock = blockMap.get(cellId);
                        if (cellBlock != null && cellBlock.isCell()) {
                            cells.add(cellBlock);
                        }
                    });
                });

        if (cells.isEmpty()) {
            log.warn("No cells found in table, skipping");
            return results;
        }

        log.debug("Found {} cells in table", cells.size());

        // Step 2: Group cells by row index
        Map<Integer, List<TableCell>> rowsMap = groupCellsByRow(cells, blockMap);

        if (rowsMap.isEmpty()) {
            log.warn("Could not group cells into rows, skipping");
            return results;
        }

        log.debug("Grouped cells into {} rows", rowsMap.size());

        // Step 3: Identify header row (usually row 0 or 1)
        List<String> headers = extractHeaderRow(rowsMap);
        int dataStartRow = headers.isEmpty() ? 1 : (rowsMap.containsKey(0) ? 1 : 2);

        log.debug("Headers: {}, Data starts at row: {}", headers, dataStartRow);

        // Step 4: Parse each data row as a test result
        for (int rowIndex = dataStartRow; rowIndex <= rowsMap.size(); rowIndex++) {
            List<TableCell> row = rowsMap.get(rowIndex);
            if (row == null || row.isEmpty()) {
                continue;
            }

            TestResult result = parseRowAsTestResult(row, headers);
            if (result != null) {
                results.add(result);
                log.debug("Parsed test result: {} = {}", result.getTestName(), result.getTestValue());
            }
        }

        log.info("Extracted {} test results from table", results.size());
        return results;
    }

    /**
     * Inner class to represent a table cell with position information.
     */
    private static class TableCell {
        final int rowIndex;
        final int columnIndex;
        final String text;
        final double confidence;

        TableCell(int rowIndex, int columnIndex, String text, double confidence) {
            this.rowIndex = rowIndex;
            this.columnIndex = columnIndex;
            this.text = text != null ? text.trim() : "";
            this.confidence = confidence;
        }
    }

    /**
     * Group cells by row index for structured parsing.
     */
    private Map<Integer, List<TableCell>> groupCellsByRow(List<ExtractedBlock> cells, Map<String, ExtractedBlock> blockMap) {
        Map<Integer, List<TableCell>> rowsMap = new TreeMap<>();

        for (ExtractedBlock cell : cells) {
            Integer rowIndex = cell.rowIndex();
            Integer columnIndex = cell.columnIndex();

            if (rowIndex == null || columnIndex == null) {
                log.warn("Cell missing row/column index, skipping");
                continue;
            }

            String cellText = extractCellText(cell, blockMap);
            double confidence = cell.confidence() != null ? cell.confidence() : 0.0;

            TableCell tableCell = new TableCell(rowIndex, columnIndex, cellText, confidence);

            rowsMap.computeIfAbsent(rowIndex, k -> new ArrayList<>()).add(tableCell);
        }

        // Sort cells within each row by column index
        rowsMap.values().forEach(row -> row.sort(Comparator.comparingInt(cell -> cell.columnIndex)));

        return rowsMap;
    }

    /**
     * Extract header row from the table.
     */
    private List<String> extractHeaderRow(Map<Integer, List<TableCell>> rowsMap) {
        List<String> headers = new ArrayList<>();

        // Try row 0 first (most common for headers)
        List<TableCell> headerRow = rowsMap.get(0);
        if (headerRow == null) {
            headerRow = rowsMap.get(1); // Try row 1 as fallback
        }

        if (headerRow != null) {
            for (TableCell cell : headerRow) {
                headers.add(cell.text.toLowerCase());
            }
        }

        return headers;
    }

    /**
     * Parse a table row as a test result.
     * Handles different table formats by looking for recognizable columns.
     */
    private TestResult parseRowAsTestResult(List<TableCell> row, List<String> headers) {
        if (row == null || row.isEmpty()) {
            return null;
        }

        // Skip empty rows
        boolean hasContent = row.stream().anyMatch(cell -> !cell.text.isEmpty());
        if (!hasContent) {
            return null;
        }

        String testName = null;
        String testValue = null;
        String unit = null;
        String referenceRange = null;
        double confidence = 0.0;

        // Strategy 1: Use headers to identify columns
        if (!headers.isEmpty() && headers.size() == row.size()) {
            for (int i = 0; i < row.size(); i++) {
                String header = i < headers.size() ? headers.get(i) : "";
                TableCell cell = row.get(i);

                if (header.contains("test") || header.contains("name") || header.contains("parameter")) {
                    testName = cell.text;
                } else if (header.contains("value") || header.contains("result")) {
                    testValue = cell.text;
                } else if (header.contains("unit")) {
                    unit = cell.text;
                } else if (header.contains("range") || header.contains("reference") || header.contains("normal")) {
                    referenceRange = cell.text;
                }

                confidence = Math.max(confidence, cell.confidence);
            }
        }

        // Strategy 2: Positional parsing (common medical report format)
        // Format: [Test Name] [Value] [Unit] [Reference Range]
        if (testName == null && row.size() >= 2) {
            testName = row.get(0).text;
            testValue = row.get(1).text;

            if (row.size() >= 3) {
                // Check if 3rd column is unit (short text, often with / symbol)
                String col3 = row.get(2).text;
                if (col3.length() <= 10 || col3.contains("/")) {
                    unit = col3;
                    if (row.size() >= 4) {
                        referenceRange = row.get(3).text;
                    }
                } else {
                    referenceRange = col3;
                }
            }

            confidence = row.stream().mapToDouble(cell -> cell.confidence).average().orElse(0.0);
        }

        // Validate we have at least test name and value
        if (testName == null || testName.isEmpty() || testValue == null || testValue.isEmpty()) {
            return null;
        }

        // Clean up test value (extract numeric part if mixed with unit)
        testValue = extractNumericValue(testValue);
        if (testValue == null) {
            return null; // Not a valid numeric test result
        }

        // Determine test status
        TestStatus status = determineTestStatusFromValue(testValue, referenceRange);

        return TestResult.builder()
                .testName(testName)
                .testValue(testValue)
                .unit(unit != null ? unit : "")
                .referenceRange(referenceRange != null ? referenceRange : "")
                .status(status)
                .confidence(confidence)
                .build();
    }

    /**
     * Extract numeric value from a string that might contain units.
     */
    private String extractNumericValue(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }

        // Try to extract number (with optional decimal point and negative sign)
        Pattern numericPattern = Pattern.compile("(-?\\d+\\.?\\d*)");
        Matcher matcher = numericPattern.matcher(value);

        if (matcher.find()) {
            return matcher.group(1);
        }

        return null;
    }

    /**
     * Determine test status from value and reference range.
     */
    private TestStatus determineTestStatusFromValue(String valueStr, String referenceRange) {
        if (referenceRange == null || referenceRange.isEmpty()) {
            return TestStatus.NORMAL; // No range to compare
        }

        try {
            double value = Double.parseDouble(valueStr);
            return determineTestStatus(value, referenceRange);
        } catch (NumberFormatException e) {
            return TestStatus.NORMAL; // Can't parse value
        }
    }

    /**
     * Extract text from a cell block.
     */
    private String extractCellText(ExtractedBlock cellBlock, Map<String, ExtractedBlock> blockMap) {
        if (cellBlock.relationships() == null) {
            return cellBlock.text() != null ? cellBlock.text() : "";
        }

        StringBuilder text = new StringBuilder();
        cellBlock.relationships().stream()
                .filter(rel -> rel.type() != null && rel.type().equalsIgnoreCase("CHILD"))
                .forEach(rel -> {
                    rel.ids().forEach(wordId -> {
                        ExtractedBlock wordBlock = blockMap.get(wordId);
                        if (wordBlock != null && wordBlock.text() != null) {
                            text.append(wordBlock.text()).append(" ");
                        }
                    });
                });

        return text.toString().trim();
    }

    /**
     * Parse form blocks to extract test results.
     */
    private List<TestResult> parseFormBlocks(List<ExtractedBlock> blocks) {
        List<TestResult> results = new ArrayList<>();

        Map<String, String> keyValuePairs = new HashMap<>();

        // Extract KEY_VALUE_SET blocks
        List<ExtractedBlock> keyValueBlocks = blocks.stream()
                .filter(ExtractedBlock::isKeyValueSet)
                .toList();

        // Build key-value pairs
        Map<String, ExtractedBlock> blockMap = new HashMap<>();
        for (ExtractedBlock block : blocks) {
            blockMap.put(block.id(), block);
        }

        for (ExtractedBlock kvBlock : keyValueBlocks) {
            // Check if this is a KEY entity (would need entity types in ExtractedBlock)
            // For now, simplified approach
            String key = extractTextFromBlock(kvBlock, blockMap);
            String value = extractValueForKey(kvBlock, blockMap);
            if (key != null && value != null) {
                keyValuePairs.put(key, value);
            }
        }

        // Convert key-value pairs to test results
        keyValuePairs.forEach((key, value) -> {
            TestResult result = parseTestResultFromKeyValue(key, value);
            if (result != null) {
                results.add(result);
            }
        });

        return results;
    }

    /**
     * Extract text from a block and its children.
     */
    private String extractTextFromBlock(ExtractedBlock block, Map<String, ExtractedBlock> blockMap) {
        if (block.relationships() == null || block.relationships().isEmpty()) {
            // Return the block's own text, or empty string if null
            return block.text() != null ? block.text() : "";
        }

        StringBuilder text = new StringBuilder();

        // If block has its own text, start with that
        if (block.text() != null && !block.text().isBlank()) {
            text.append(block.text()).append(" ");
        }

        // Then add child text
        block.relationships().stream()
                .filter(rel -> rel.type() != null && rel.type().equalsIgnoreCase("CHILD"))
                .forEach(rel -> {
                    rel.ids().forEach(childId -> {
                        ExtractedBlock childBlock = blockMap.get(childId);
                        if (childBlock != null && childBlock.text() != null) {
                            text.append(childBlock.text()).append(" ");
                        }
                    });
                });

        String result = text.toString().trim();
        return result.isEmpty() ? null : result;
    }

    /**
     * Extract value for a key block.
     */
    private String extractValueForKey(ExtractedBlock keyBlock, Map<String, ExtractedBlock> blockMap) {
        if (keyBlock.relationships() == null) {
            return null;
        }

        return keyBlock.relationships().stream()
                .filter(rel -> rel.type() != null && rel.type().equalsIgnoreCase("VALUE"))
                .flatMap(rel -> rel.ids().stream())
                .map(blockMap::get)
                .filter(Objects::nonNull)
                .map(valueBlock -> extractTextFromBlock(valueBlock, blockMap))
                .filter(Objects::nonNull)  // Filter out null text results
                .findFirst()
                .orElse(null);
    }

    /**
     * Parse a test result from text using pattern matching.
     */
    private TestResult parseTestResultFromText(String text) {
        // Pattern: Test Name: Value Unit (Reference Range)
        // Example: "Serum Creatinine: 135.0 µmol/L (Male: 59-104, Female: 45-84)"

        Pattern pattern = Pattern.compile(
                "([\\w\\s]+):\\s*([\\d.]+)\\s*([\\w/µ]+)\\s*\\(([^)]+)\\)",
                Pattern.CASE_INSENSITIVE
        );

        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            String testName = matcher.group(1).trim();
            String testValue = matcher.group(2).trim();
            String unit = matcher.group(3).trim();
            String referenceRange = matcher.group(4).trim();

            TestStatus status = determineTestStatus(
                    Double.parseDouble(testValue),
                    referenceRange
            );

            return TestResult.builder()
                    .testName(testName)
                    .testValue(testValue)
                    .unit(unit)
                    .referenceRange(referenceRange)
                    .status(status)
                    .confidence(0.90)
                    .build();
        }

        return null;
    }

    /**
     * Parse a test result from key-value pair.
     */
    private TestResult parseTestResultFromKeyValue(String key, String value) {
        // Simplified parsing - can be enhanced based on actual data patterns
        if (key.toLowerCase().contains("test") || key.toLowerCase().contains("level")) {
            return TestResult.builder()
                    .testName(key)
                    .testValue(value)
                    .unit("") // Extract from value if present
                    .referenceRange("") // May not be available in form data
                    .status(TestStatus.NORMAL)
                    .confidence(0.75)
                    .build();
        }
        return null;
    }

    /**
     * Determine test status based on value and reference range.
     */
    private TestStatus determineTestStatus(double value, String referenceRange) {
        // Parse reference range and compare
        // Simplified logic - can be enhanced with proper range parsing

        Pattern rangePattern = Pattern.compile("([\\d.]+)-([\\d.]+)");
        Matcher matcher = rangePattern.matcher(referenceRange);

        if (matcher.find()) {
            double minRange = Double.parseDouble(matcher.group(1));
            double maxRange = Double.parseDouble(matcher.group(2));

            if (value < minRange || value > maxRange) {
                // Determine if critical based on how far outside range
                double deviation = Math.max(
                        Math.abs(value - minRange) / minRange,
                        Math.abs(value - maxRange) / maxRange
                );

                if (deviation > 0.5) { // 50% deviation = critical
                    return TestStatus.CRITICAL;
                }
                return TestStatus.ABNORMAL;
            }
        }

        return TestStatus.NORMAL;
    }

    /**
     * Custom exception for OCR operations.
     */
    public static class OcrException extends RuntimeException {
        public OcrException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
