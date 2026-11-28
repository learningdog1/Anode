package com.nodeseek.app.domain.valuation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale

internal data class MarketGoldenRow(
    val id: String,
    val synthetic: Boolean,
    val title: String,
    val bodySnippet: String,
    val expectedQuery: String,
    val expectedMerchant: String,
    val expectedModelTokens: List<String>,
    val intent: TradeListingIntent,
    val spreadCny: BigDecimal?,
    val askPriceCny: BigDecimal?,
    val renewalAmount: BigDecimal?,
    val renewalCurrency: String?,
    val renewalCycle: String?,
    val expiry: String?,
    val notes: Set<String>,
)

internal object MarketGoldenCorpus {
    const val RESOURCE_PATH = "/market/trade_titles_golden.tsv"

    private val EXPECTED_HEADER = listOf(
        "id",
        "synthetic",
        "title",
        "body_snippet",
        "expected_query",
        "expected_merchant",
        "expected_model_tokens",
        "intent",
        "spread_cny",
        "ask_price_cny",
        "renewal_amount",
        "renewal_currency",
        "renewal_cycle",
        "expiry",
        "notes",
    )

    fun load(resourcePath: String = RESOURCE_PATH): List<MarketGoldenRow> {
        val rawText = javaClass.getResourceAsStream(resourcePath)
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }
            ?: run {
                val fallback = File("src/test/resources/market/trade_titles_golden.tsv")
                require(fallback.isFile) { "Golden TSV not found on classpath ($resourcePath) or at ${fallback.absolutePath}" }
                fallback.readText(Charsets.UTF_8)
            }

        val lines = rawText.lineSequence()
            .filter { it.isNotBlank() }
            .toList()
        require(lines.isNotEmpty()) { "Golden TSV is empty" }

        val header = lines.first().removePrefix("\uFEFF").split('\t')
        require(header == EXPECTED_HEADER) {
            "Unexpected TSV header.\nExpected: $EXPECTED_HEADER\nActual:   $header"
        }

        return lines.drop(1).mapIndexed { idx, line ->
            val cols = line.split('\t')
            require(cols.size == EXPECTED_HEADER.size) {
                "Line ${idx + 2} has ${cols.size} columns (expected ${EXPECTED_HEADER.size}): $line"
            }
            val syntheticFlag = cols[1].trim()
            require(syntheticFlag == "0" || syntheticFlag == "1") {
                "Line ${idx + 2} invalid synthetic flag '$syntheticFlag'"
            }
            val intent = TradeListingIntent.valueOf(cols[7].trim())
            val modelTokens = cols[6].trim()
                .takeIf { it.isNotEmpty() }
                ?.split(' ')
                ?.filter { it.isNotBlank() }
                ?: emptyList()
            val notes = cols[14].trim()
                .takeIf { it.isNotEmpty() }
                ?.split(';')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toSet()
                ?: emptySet()

            MarketGoldenRow(
                id = cols[0].trim(),
                synthetic = syntheticFlag == "1",
                title = cols[2],
                bodySnippet = cols[3].replace("\\n", "\n"),
                expectedQuery = cols[4].trim(),
                expectedMerchant = cols[5].trim(),
                expectedModelTokens = modelTokens,
                intent = intent,
                spreadCny = cols[8].trim().takeIf { it.isNotEmpty() }?.let(::BigDecimal),
                askPriceCny = cols[9].trim().takeIf { it.isNotEmpty() }?.let(::BigDecimal),
                renewalAmount = cols[10].trim().takeIf { it.isNotEmpty() }?.let(::BigDecimal),
                renewalCurrency = cols[11].trim().takeIf { it.isNotEmpty() },
                renewalCycle = cols[12].trim().takeIf { it.isNotEmpty() },
                expiry = cols[13].trim().takeIf { it.isNotEmpty() },
                notes = notes,
            )
        }
    }
}

class MarketCorpusEvalTest {

    companion object {
        // Corpus size & hygiene thresholds (enforced immediately)
        const val MIN_CORPUS_ROWS = 200
        const val MIN_BODY_SNIPPET_ROWS = 60

        // Fixed reference date for deterministic expiry/residual evaluation
        val EVAL_NOW: LocalDate = LocalDate.of(2026, 11, 28)

        // Per-metric accuracy thresholds (enforcing all round targets)
        const val MIN_MERCHANT_ACCURACY = 0.95
        const val MIN_QUERY_TOKEN_JACCARD = 0.80
        const val MIN_MATCH_PRECISION = 0.95
        const val MIN_MATCH_RECALL = 0.90
        const val MIN_EXPLICIT_SPREAD_ACCURACY = 0.90
        const val MIN_ASK_PRICE_ACCURACY = 0.90
        const val MIN_RENEWAL_ACCURACY = 0.90
        const val MIN_EXPIRY_ACCURACY = 0.90

        private val VALID_CYCLES = setOf("month", "quarter", "semi_annual", "year", "2year", "3year")
        private val EXPIRY_PATTERN = Regex("""^(?:\d{4}-\d{2}-\d{2}|\d{4}-\d{2}|--\d{2}-\d{2}|--\d{2}|\d{1,4}d)$""")
        private val PII_EMAIL_PATTERN = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
        private val PII_IPV4_PATTERN = Regex("""\b\d{1,3}(?:\.\d{1,3}){3}\b""")
        private val PII_HANDLE_PATTERN = Regex("""t\.me/|@[A-Za-z0-9_]{3,}""")

        private val CANONICAL_MERCHANTS_LOWER: Map<String, String> = listOf(
            "DMIT", "BWG", "RACKNERD", "GREENCLOUD", "SPARTAN", "CLAW", "CLOUDCONE",
            "VIRMACH", "ORACLE", "NETCUP", "HETZNER", "ALICE", "YXVM", "V.PS", "AKILE",
            "WAP", "ZGO", "RFC", "MISAKA", "SALTYFISH", "KIRINO", "SHARON", "HALO",
            "AKKO", "CERA", "VOLC", "HOSTHATCH", "DEDIPATH", "BUYVM", "OVH", "CONTABO",
            "KURUN", "VMISS", "BAGE", "HOSTDARE", "HYTRON", "ISIF", "GIGSGC",
            "COLOCROSSING", "BYTEVIRT", "VULTR", "LINODE", "DIGITALOCEAN",
            "ALIYUN", "TENCENT", "AWS", "GCP", "AZURE",
        ).associateBy { it.lowercase(Locale.ROOT) }

        // Geographic / route / SKU synonyms (NOT merchant aliases: merchant must be canonicalized!)
        private val MODEL_SYNONYM_CANONICAL: Map<String, String> = buildMap {
            fun group(canon: String, vararg variants: String) {
                put(canon, canon)
                variants.forEach { put(it, canon) }
            }
            group("softbank", "软银", "sb")
            group("9929", "as9929")
            group("4837", "as4837")
            group("163", "as4134")
            group("gia", "giae", "gia-e")
            group("eb", "eyeball")
            group("tokyo", "tyo", "东京")
            group("osaka", "kix", "大阪")
            group("lax", "la", "losangeles", "美西", "洛杉矶")
            group("sjc", "sanjose", "圣何塞")
            group("sea", "seattle", "西雅图")
            group("dal", "dallas", "达拉斯")
            group("nyc", "newyork", "纽约")
            group("chi", "chicago", "芝加哥")
            group("mia", "miami", "迈阿密")
            group("hk", "hkg", "香港")
            group("sg", "sin", "sgp", "新加坡", "狮城", "坡县")
            group("jp", "jpn", "日本")
            group("us", "usa", "美国")
            group("de", "deu", "德国")
            group("fra", "frankfurt", "法兰克福")
            group("uk", "gbr", "英国")
            group("lon", "london", "伦敦")
            group("nl", "nld", "荷兰")
            group("ams", "amsterdam", "阿姆斯特丹")
            group("fr", "paris", "法国", "巴黎")
            group("kr", "kor", "韩国")
            group("seoul", "icn", "首尔")
            group("tw", "tpe", "台湾", "台北")
            group("lu", "lux", "luxembourg", "卢森堡")
            group("fi", "fin", "hel", "helsinki", "芬兰")
            group("ca", "can", "加拿大")
            group("au", "aus", "澳洲", "澳大利亚")
            group("dc1", "dc01")
            group("dc2", "dc02")
            group("dc3", "dc03")
            group("dc4", "dc04")
            group("dc5", "dc05")
            group("dc6", "dc06")
            group("dc7", "dc07")
            group("dc8", "dc08")
            group("dc9", "dc09")
            group("biggerbox", "biggerboxpro", "bigbox")
            group("megabox", "megaboxpro")
            group("plan", "theplan")
            group("bf", "blackfriday", "黑五")
            group("1111", "双十一", "双11")
            group("palmspring", "palmsprings")
        }

        private val BWG_PRO_IMPLIED = setOf("megabox", "biggerbox")
        private val COMPACT_CODE_JOIN = Regex("""(?i)\b(rs|vps|ax|ex|cx|cpx|cax|ccx)[-\s](\d{2,4})\b""")
        private val HYPHEN_DC_JOIN = Regex("""(?i)\bdc[-\s](\d{1,2})\b""")
    }

    @Test
    fun `golden TSV parses cleanly with valid schema, minimum coverage, and zero PII`() {
        val rows = MarketGoldenCorpus.load()
        assertTrue("Expected at least $MIN_CORPUS_ROWS rows, got ${rows.size}", rows.size >= MIN_CORPUS_ROWS)

        val withBody = rows.count { it.bodySnippet.isNotBlank() }
        assertTrue(
            "Expected at least $MIN_BODY_SNIPPET_ROWS rows with body_snippet, got $withBody",
            withBody >= MIN_BODY_SNIPPET_ROWS,
        )

        val uniqueIds = rows.map { it.id }.toSet()
        assertEquals("Duplicate IDs found in golden TSV", rows.size, uniqueIds.size)

        rows.forEach { row ->
            assertTrue("Row ${row.id} has blank title", row.title.isNotBlank())
            assertTrue(
                "Row ${row.id} body_snippet exceeds 600 chars (${row.bodySnippet.length})",
                row.bodySnippet.length <= 600,
            )
            val combined = "${row.title}\n${row.bodySnippet}"
            assertFalse("Row ${row.id} leaked email: $combined", PII_EMAIL_PATTERN.containsMatchIn(combined))
            assertFalse("Row ${row.id} leaked IPv4: $combined", PII_IPV4_PATTERN.containsMatchIn(combined))
            assertFalse("Row ${row.id} leaked contact handle: $combined", PII_HANDLE_PATTERN.containsMatchIn(combined))

            row.renewalCurrency?.let { currency ->
                assertTrue(
                    "Row ${row.id} invalid renewal_currency '$currency'",
                    ValuationCurrency.fromCode(currency) != null,
                )
            }
            row.renewalCycle?.let { cycle ->
                assertTrue("Row ${row.id} invalid renewal_cycle '$cycle'", cycle in VALID_CYCLES)
            }
            row.expiry?.let { exp ->
                assertTrue("Row ${row.id} invalid expiry '$exp'", EXPIRY_PATTERN.matches(exp))
            }
        }
    }

    @Test
    fun `report baseline accuracy of query extraction, matching, and spread or ask parsing`() {
        val rows = MarketGoldenCorpus.load()

        val fixtureRows = rows.count { !it.synthetic && it.notes.any { n -> n.startsWith("fixture:") } }
        val existingTestRows = rows.count { !it.synthetic && it.notes.none { n -> n.startsWith("fixture:") } }
        val syntheticRows = rows.count { it.synthetic }
        val bodyRows = rows.count { it.bodySnippet.isNotBlank() }

        // 1. Query extraction evaluation (strict canonical merchant + synonym-aware model-token Jaccard)
        data class ExtractionResult(
            val row: MarketGoldenRow,
            val actualQuery: String,
            val actualMerchant: String,
            val merchantMatch: Boolean,
            val jaccard: Double,
            val rawJaccard: Double,
        )

        val extractionResults = rows.map { row ->
            val actualQuery = TradeMarketPremiumAnalyzer.extractSuggestedQuery(
                title = row.title,
                fallbackText = row.bodySnippet.ifBlank { null },
            )
            val actualMerchant = detectStrictCanonicalMerchant(actualQuery)
            val merchantMatch = actualMerchant.equals(row.expectedMerchant, ignoreCase = true)
            val canonicalJaccard = computeTokenJaccard(
                actualQuery = actualQuery,
                expectedQuery = row.expectedQuery,
                useSynonyms = true,
            )
            val rawJaccard = computeTokenJaccard(
                actualQuery = actualQuery,
                expectedQuery = row.expectedQuery,
                useSynonyms = false,
            )
            ExtractionResult(
                row = row,
                actualQuery = actualQuery,
                actualMerchant = actualMerchant,
                merchantMatch = merchantMatch,
                jaccard = canonicalJaccard,
                rawJaccard = rawJaccard,
            )
        }

        val merchantLabeled = extractionResults.filter { it.row.expectedMerchant.isNotEmpty() }
        val merchantHits = merchantLabeled.count { it.merchantMatch }
        val merchantAccuracy = if (merchantLabeled.isEmpty()) 0.0 else merchantHits.toDouble() / merchantLabeled.size

        val noMerchantRows = extractionResults.filter { it.row.expectedMerchant.isEmpty() }
        val merchantFalsePositives = noMerchantRows.count { it.actualMerchant.isNotEmpty() }

        val queryLabeled = extractionResults.filter { it.row.expectedQuery.isNotEmpty() }
        val meanJaccard = if (queryLabeled.isEmpty()) 0.0 else queryLabeled.map { it.jaccard }.average()
        val meanRawJaccard = if (queryLabeled.isEmpty()) 0.0 else queryLabeled.map { it.rawJaccard }.average()
        val exactQueryMatches = queryLabeled.count { it.jaccard >= 0.9999 }

        val emptyExpectedRows = extractionResults.filter { it.row.expectedQuery.isEmpty() }
        val emptyExactMatches = emptyExpectedRows.count { it.actualQuery.isBlank() }

        // 2. matchesQuery precision & recall evaluation (title-identifiable non-bundle rows)
        val titleMatchPool = rows.filter { row ->
            "body_only_model" !in row.notes &&
                "body_enriches_model" !in row.notes &&
                "multi_bundle" !in row.notes
        }

        data class QueryCluster(
            val canonicalKey: String,
            val representativeQuery: String,
            val merchant: String,
            val canonicalModelTokens: Set<String>,
            val memberCount: Int,
        )

        val clustersByKey = LinkedHashMap<String, MutableList<MarketGoldenRow>>()
        for (row in titleMatchPool) {
            if (row.expectedQuery.isBlank() || row.expectedMerchant.isBlank()) continue
            val canonTokens = canonicalTokenSet(row.expectedQuery, useSynonyms = true)
            val key = canonTokens.sorted().joinToString(" ")
            clustersByKey.getOrPut(key) { mutableListOf() }.add(row)
        }

        val evalClusters = clustersByKey.entries
            .filter { it.value.size >= 2 }
            .map { (key, members) ->
                val rep = members.first()
                val canonAll = canonicalTokenSet(rep.expectedQuery, useSynonyms = true)
                val merchantLower = rep.expectedMerchant.lowercase(Locale.ROOT)
                QueryCluster(
                    canonicalKey = key,
                    representativeQuery = rep.expectedQuery,
                    merchant = rep.expectedMerchant,
                    canonicalModelTokens = canonAll - merchantLower,
                    memberCount = members.size,
                )
            }

        var matchTp = 0
        var matchFp = 0
        var matchFn = 0
        val sampleFps = mutableListOf<String>()
        val sampleFns = mutableListOf<String>()

        for (cluster in evalClusters) {
            for (cand in titleMatchPool) {
                val candCanonAll = if (cand.expectedQuery.isBlank()) emptySet() else canonicalTokenSet(cand.expectedQuery, useSynonyms = true)
                val candModelTokens = candCanonAll - cand.expectedMerchant.lowercase(Locale.ROOT)
                val expectedMatch = cand.expectedMerchant.equals(cluster.merchant, ignoreCase = true) &&
                    cluster.canonicalModelTokens.all { it in candModelTokens }
                val actualMatch = TradeMarketPremiumAnalyzer.matchesQuery(cand.title, cluster.representativeQuery)

                when {
                    expectedMatch && actualMatch -> matchTp++
                    !expectedMatch && actualMatch -> {
                        matchFp++
                        if (sampleFps.size < 25) {
                            sampleFps += "  FP: query='${cluster.representativeQuery}' matched [#${cand.id}] '${cand.title}' (expected='${cand.expectedQuery}')"
                        }
                    }
                    expectedMatch && !actualMatch -> {
                        matchFn++
                        if (sampleFns.size < 25) {
                            sampleFns += "  FN: query='${cluster.representativeQuery}' missed  [#${cand.id}] '${cand.title}' (expected='${cand.expectedQuery}')"
                        }
                    }
                }
            }
        }
        val matchPrecision = if (matchTp + matchFp == 0) 0.0 else matchTp.toDouble() / (matchTp + matchFp)
        val matchRecall = if (matchTp + matchFn == 0) 0.0 else matchTp.toDouble() / (matchTp + matchFn)
        val matchF1 = if (matchPrecision + matchRecall == 0.0) 0.0 else 2 * matchPrecision * matchRecall / (matchPrecision + matchRecall)

        // 3a. Legacy baseline: TradeMarketPremiumAnalyzer.extractSpread(title) only
        val spreadLabeled = rows.filter { it.spreadCny != null }
        val titleSpreadLabeled = spreadLabeled.filter { "spread_in_body" !in it.notes }
        val legacySpreadTitleCorrect = titleSpreadLabeled.count { row ->
            val parsed = TradeMarketPremiumAnalyzer.extractSpread(row.title)
            parsed != null && parsed.deltaCny.compareTo(row.spreadCny) == 0
        }
        val priceableRows = rows.filter { it.spreadCny != null || it.askPriceCny != null }
        val legacyPriceableYield = priceableRows.count { TradeMarketPremiumAnalyzer.extractSpread(it.title) != null }

        // 3b. TradeListingPriceParser evaluation (title + body_snippet)
        val parsedDetailsByRow = rows.associateWith { row ->
            TradeListingPriceParser.parse(
                title = row.title,
                bodySnippet = row.bodySnippet.ifBlank { null },
                now = EVAL_NOW,
            )
        }
        val inferredByRow = rows.associateWith { row ->
            TradeListingPriceParser.inferSpread(
                title = row.title,
                bodySnippet = row.bodySnippet.ifBlank { null },
                now = EVAL_NOW,
            )
        }

        val parserSpreadHits = spreadLabeled.count { row ->
            val parsed = parsedDetailsByRow.getValue(row).explicitSpread
            parsed != null && parsed.deltaCny.compareTo(row.spreadCny) == 0
        }
        val explicitSpreadAccuracy = if (spreadLabeled.isEmpty()) 0.0 else parserSpreadHits.toDouble() / spreadLabeled.size
        val parserSpreadFps = rows.filter { it.spreadCny == null && parsedDetailsByRow.getValue(it).explicitSpread != null }

        val askLabeled = rows.filter { it.askPriceCny != null }
        val askHits = askLabeled.count { row ->
            val parsed = parsedDetailsByRow.getValue(row).askPrice
            parsed != null && parsed.amountCny.compareTo(row.askPriceCny) == 0
        }
        val askPriceAccuracy = if (askLabeled.isEmpty()) 0.0 else askHits.toDouble() / askLabeled.size
        val askFalsePositives = rows.filter { it.askPriceCny == null && parsedDetailsByRow.getValue(it).askPrice != null }

        val renewalLabeled = rows.filter { it.renewalAmount != null && it.renewalCurrency != null }
        val renewalHits = renewalLabeled.count { row ->
            val parsed = parsedDetailsByRow.getValue(row).renewal ?: return@count false
            val amtOk = parsed.amount.compareTo(row.renewalAmount) == 0
            val curOk = parsed.currency.code.equals(row.renewalCurrency, ignoreCase = true)
            val cycleOk = row.renewalCycle == null || mapCyclePreset(parsed.cycle) == row.renewalCycle
            amtOk && curOk && cycleOk
        }
        val renewalAccuracy = if (renewalLabeled.isEmpty()) 0.0 else renewalHits.toDouble() / renewalLabeled.size

        val expiryLabeled = rows.filter { it.expiry != null }
        val expiryHits = expiryLabeled.count { row ->
            val parsed = parsedDetailsByRow.getValue(row).expiry ?: return@count false
            matchesExpectedExpiry(parsed, row.expiry!!, EVAL_NOW)
        }
        val expiryAccuracy = if (expiryLabeled.isEmpty()) 0.0 else expiryHits.toDouble() / expiryLabeled.size

        val askNoExplicitSpread = rows.filter { it.askPriceCny != null && it.spreadCny == null }
        val derivableSpreadRows = askNoExplicitSpread.filter {
            it.renewalAmount != null && it.renewalCurrency != null && it.renewalCycle != null && it.expiry != null
        }
        val derivedSpreadProduced = derivableSpreadRows.count { row ->
            val inf = inferredByRow.getValue(row)
            inf != null && inf.hasSpread && inf.isDerived
        }
        val newPriceableYield = priceableRows.count { inferredByRow.getValue(it) != null }

        // Print comprehensive report
        val report = buildString {
            appendLine("========================================================================")
            appendLine("  MarketCorpusEvalTest Report (${rows.size} golden rows)")
            appendLine("========================================================================")
            appendLine(
                "Source mix: live=0 | fixture=$fixtureRows | existing_test=$existingTestRows | synthetic=$syntheticRows | with_body=$bodyRows",
            )
            appendLine()
            appendLine("[1] Query Extraction (TradeMarketPremiumAnalyzer.extractSuggestedQuery)")
            appendLine(
                String.format(
                    Locale.US,
                    "  Merchant accuracy (strict canon, n=%d): %d/%d (%.1f%%)  [target >= 95.0%%]",
                    merchantLabeled.size,
                    merchantHits,
                    merchantLabeled.size,
                    merchantAccuracy * 100.0,
                ),
            )
            appendLine(
                String.format(
                    Locale.US,
                    "  Merchant false positives (empty=%d):     %d/%d (%.1f%%)",
                    noMerchantRows.size,
                    merchantFalsePositives,
                    noMerchantRows.size,
                    if (noMerchantRows.isEmpty()) 0.0 else merchantFalsePositives * 100.0 / noMerchantRows.size,
                ),
            )
            appendLine(
                String.format(
                    Locale.US,
                    "  Token-set Jaccard mean (canon):         %.3f  (raw: %.3f)  [target >= 0.800]",
                    meanJaccard,
                    meanRawJaccard,
                ),
            )
            appendLine(
                String.format(
                    Locale.US,
                    "  Exact canonical query match:            %d/%d (%.1f%%)",
                    exactQueryMatches,
                    queryLabeled.size,
                    if (queryLabeled.isEmpty()) 0.0 else exactQueryMatches * 100.0 / queryLabeled.size,
                ),
            )
            appendLine(
                String.format(
                    Locale.US,
                    "  Empty-expected specificity:             %d/%d (%.1f%%)",
                    emptyExactMatches,
                    emptyExpectedRows.size,
                    if (emptyExpectedRows.isEmpty()) 0.0 else emptyExactMatches * 100.0 / emptyExpectedRows.size,
                ),
            )

            appendLine()
            appendLine("  Per-tag extraction breakdown:")
            appendLine("  %-22s %5s %10s %10s".format("tag", "rows", "merch_acc", "jaccard"))
            val bucketTags = listOf(
                "concise",
                "cn_slang",
                "glued_cjk",
                "noisy_ascii",
                "multiword_vendor",
                "dotted_sku",
                "hyphen_sku",
                "fullwidth",
                "markdown_table",
                "body_only_model",
                "body_enriches_model",
                "buy_request",
                "sold",
                "account",
                "non_vps",
                "multi_bundle",
                "vague_unidentifiable",
            )
            for (tag in bucketTags) {
                val subset = extractionResults.filter { tag in it.row.notes }
                if (subset.isEmpty()) continue
                val subMerch = subset.filter { it.row.expectedMerchant.isNotEmpty() }
                val subMerchAcc = if (subMerch.isEmpty()) "-" else String.format(
                    Locale.US,
                    "%.1f%%",
                    subMerch.count { it.merchantMatch } * 100.0 / subMerch.size,
                )
                val subQuery = subset.filter { it.row.expectedQuery.isNotEmpty() }
                val subJac = if (subQuery.isEmpty()) "-" else String.format(
                    Locale.US,
                    "%.3f",
                    subQuery.map { it.jaccard }.average(),
                )
                appendLine("  %-22s %5d %10s %10s".format(tag, subset.size, subMerchAcc, subJac))
            }

            val extractionFailures = extractionResults
                .filter { !it.merchantMatch || it.jaccard < 0.9999 }
                .sortedBy { it.jaccard }
            appendLine()
            appendLine("  Extraction mismatches (${extractionFailures.size} total, showing up to 45 worst):")
            appendLine("  %-4s | %-34s | %-24s | %-24s | %s".format("ID", "Title", "Expected", "Actual", "Jac"))
            for (f in extractionFailures.take(45)) {
                appendLine(
                    "  %-4s | %-34s | %-24s | %-24s | %.2f".format(
                        Locale.US,
                        f.row.id,
                        f.row.title.take(32),
                        f.row.expectedQuery.ifEmpty { "<empty>" }.take(24),
                        f.actualQuery.ifEmpty { "<empty>" }.take(24),
                        f.jaccard,
                    ),
                )
            }

            appendLine()
            appendLine("[2] Query Matching (TradeMarketPremiumAnalyzer.matchesQuery)")
            appendLine("  Evaluated clusters (>=2 title-identifiable rows): ${evalClusters.size}, pool=${titleMatchPool.size} rows")
            appendLine(
                String.format(
                    Locale.US,
                    "  Precision: %.1f%% (TP=%d, FP=%d)  [target >= 95.0%%]",
                    matchPrecision * 100.0,
                    matchTp,
                    matchFp,
                ),
            )
            appendLine(
                String.format(
                    Locale.US,
                    "  Recall:    %.1f%% (TP=%d, FN=%d)  [target >= 90.0%%]",
                    matchRecall * 100.0,
                    matchTp,
                    matchFn,
                ),
            )
            appendLine(String.format(Locale.US, "  F1:        %.3f", matchF1))
            if (sampleFps.isNotEmpty()) {
                appendLine("  Sample False Positives:")
                sampleFps.forEach { appendLine(it) }
            }
            if (sampleFns.isNotEmpty()) {
                appendLine("  Sample False Negatives:")
                sampleFns.forEach { appendLine(it) }
            }

            appendLine()
            appendLine("[3] Pricing & Spread Parsing")
            appendLine(
                String.format(
                    Locale.US,
                    "  Legacy extractSpread(title) yield on all priceable: %d/%d (%.1f%%) [title explicit=%d/%d]",
                    legacyPriceableYield,
                    priceableRows.size,
                    if (priceableRows.isEmpty()) 0.0 else legacyPriceableYield * 100.0 / priceableRows.size,
                    legacySpreadTitleCorrect,
                    titleSpreadLabeled.size,
                ),
            )
            appendLine(
                String.format(
                    Locale.US,
                    "  TradeListingPriceParser explicit spread (n=%d):     %d/%d (%.1f%%), FP=%d  [target >= 90.0%%]",
                    spreadLabeled.size,
                    parserSpreadHits,
                    spreadLabeled.size,
                    explicitSpreadAccuracy * 100.0,
                    parserSpreadFps.size,
                ),
            )
            appendLine(
                String.format(
                    Locale.US,
                    "  TradeListingPriceParser ask_price_cny (n=%d):       %d/%d (%.1f%%), FP=%d  [target >= 90.0%%]",
                    askLabeled.size,
                    askHits,
                    askLabeled.size,
                    askPriceAccuracy * 100.0,
                    askFalsePositives.size,
                ),
            )
            appendLine(
                String.format(
                    Locale.US,
                    "  TradeListingPriceParser renewal (n=%d):             %d/%d (%.1f%%)  [target >= 90.0%%]",
                    renewalLabeled.size,
                    renewalHits,
                    renewalLabeled.size,
                    renewalAccuracy * 100.0,
                ),
            )
            appendLine(
                String.format(
                    Locale.US,
                    "  TradeListingPriceParser expiry (n=%d):              %d/%d (%.1f%%)  [target >= 90.0%%]",
                    expiryLabeled.size,
                    expiryHits,
                    expiryLabeled.size,
                    expiryAccuracy * 100.0,
                ),
            )
            appendLine(
                String.format(
                    Locale.US,
                    "  Derived spread yield (ask+renewal+expiry, n=%d):    %d/%d (%.1f%%)",
                    derivableSpreadRows.size,
                    derivedSpreadProduced,
                    derivableSpreadRows.size,
                    if (derivableSpreadRows.isEmpty()) 0.0 else derivedSpreadProduced * 100.0 / derivableSpreadRows.size,
                ),
            )
            appendLine(
                String.format(
                    Locale.US,
                    "  New inferSpread total yield on priceable (n=%d):    %d/%d (%.1f%%)",
                    priceableRows.size,
                    newPriceableYield,
                    priceableRows.size,
                    if (priceableRows.isEmpty()) 0.0 else newPriceableYield * 100.0 / priceableRows.size,
                ),
            )

            val askMisses = askLabeled.filter { row ->
                val parsed = parsedDetailsByRow.getValue(row).askPrice
                parsed == null || parsed.amountCny.compareTo(row.askPriceCny) != 0
            }
            if (askMisses.isNotEmpty()) {
                appendLine("  Ask price misses (${askMisses.size}, showing up to 15):")
                askMisses.take(15).forEach { row ->
                    val actual = parsedDetailsByRow.getValue(row).askPrice
                    appendLine("    Ask Miss [#${row.id}] '${row.title}' expected=${row.askPriceCny} actual=${actual?.amountCny} (${actual?.matchedExpression})")
                }
            }
            if (askFalsePositives.isNotEmpty()) {
                appendLine("  Ask price FPs (${askFalsePositives.size}, showing up to 15):")
                askFalsePositives.take(15).forEach { row ->
                    val actual = parsedDetailsByRow.getValue(row).askPrice
                    appendLine("    Ask FP   [#${row.id}] '${row.title}' actual=${actual?.amountCny} (${actual?.matchedExpression})")
                }
            }
            val renewalMisses = renewalLabeled.filter { row ->
                val parsed = parsedDetailsByRow.getValue(row).renewal
                parsed == null ||
                    parsed.amount.compareTo(row.renewalAmount) != 0 ||
                    !parsed.currency.code.equals(row.renewalCurrency, ignoreCase = true) ||
                    (row.renewalCycle != null && mapCyclePreset(parsed.cycle) != row.renewalCycle)
            }
            if (renewalMisses.isNotEmpty()) {
                appendLine("  Renewal misses (${renewalMisses.size}, showing up to 15):")
                renewalMisses.take(15).forEach { row ->
                    val actual = parsedDetailsByRow.getValue(row).renewal
                    appendLine("    Ren Miss [#${row.id}] '${row.title}' expected=${row.renewalAmount} ${row.renewalCurrency}/${row.renewalCycle} actual=${actual?.amount} ${actual?.currency?.code}/${mapCyclePreset(actual?.cycle)}")
                }
            }
            val expiryMisses = expiryLabeled.filter { row ->
                val parsed = parsedDetailsByRow.getValue(row).expiry
                parsed == null || !matchesExpectedExpiry(parsed, row.expiry!!, EVAL_NOW)
            }
            if (expiryMisses.isNotEmpty()) {
                appendLine("  Expiry misses (${expiryMisses.size}, showing up to 15):")
                expiryMisses.take(15).forEach { row ->
                    val actual = parsedDetailsByRow.getValue(row).expiry
                    appendLine("    Exp Miss [#${row.id}] '${row.title}' expected=${row.expiry} actual=${actual?.expiryDate} (days=${actual?.remainingDays})")
                }
            }
            appendLine("========================================================================")
        }
        println(report)

        // Lenient threshold assertions (raised once @extractor and @pricing hit targets)
        assertTrue(
            "Merchant accuracy $merchantAccuracy below MIN_MERCHANT_ACCURACY $MIN_MERCHANT_ACCURACY",
            merchantAccuracy >= MIN_MERCHANT_ACCURACY,
        )
        assertTrue(
            "Query token Jaccard $meanJaccard below MIN_QUERY_TOKEN_JACCARD $MIN_QUERY_TOKEN_JACCARD",
            meanJaccard >= MIN_QUERY_TOKEN_JACCARD,
        )
        assertTrue(
            "matchesQuery precision $matchPrecision below MIN_MATCH_PRECISION $MIN_MATCH_PRECISION",
            matchPrecision >= MIN_MATCH_PRECISION,
        )
        assertTrue(
            "matchesQuery recall $matchRecall below MIN_MATCH_RECALL $MIN_MATCH_RECALL",
            matchRecall >= MIN_MATCH_RECALL,
        )
        assertTrue(
            "Explicit spread accuracy $explicitSpreadAccuracy below MIN_EXPLICIT_SPREAD_ACCURACY $MIN_EXPLICIT_SPREAD_ACCURACY",
            explicitSpreadAccuracy >= MIN_EXPLICIT_SPREAD_ACCURACY,
        )
        assertTrue(
            "Ask price accuracy $askPriceAccuracy below MIN_ASK_PRICE_ACCURACY $MIN_ASK_PRICE_ACCURACY",
            askPriceAccuracy >= MIN_ASK_PRICE_ACCURACY,
        )
        assertTrue(
            "Renewal accuracy $renewalAccuracy below MIN_RENEWAL_ACCURACY $MIN_RENEWAL_ACCURACY",
            renewalAccuracy >= MIN_RENEWAL_ACCURACY,
        )
        assertTrue(
            "Expiry accuracy $expiryAccuracy below MIN_EXPIRY_ACCURACY $MIN_EXPIRY_ACCURACY",
            expiryAccuracy >= MIN_EXPIRY_ACCURACY,
        )
    }

    private fun detectStrictCanonicalMerchant(query: String): String {
        if (query.isBlank()) return ""
        val firstToken = splitEvalTokens(query).firstOrNull() ?: return ""
        return CANONICAL_MERCHANTS_LOWER[firstToken] ?: ""
    }

    private fun computeTokenJaccard(
        actualQuery: String,
        expectedQuery: String,
        useSynonyms: Boolean,
    ): Double {
        val a = canonicalTokenSet(actualQuery, useSynonyms)
        val b = canonicalTokenSet(expectedQuery, useSynonyms)
        if (a.isEmpty() && b.isEmpty()) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val inter = a.intersect(b).size
        val union = a.union(b).size
        return inter.toDouble() / union.toDouble()
    }

    private fun canonicalTokenSet(query: String, useSynonyms: Boolean): Set<String> {
        if (query.isBlank()) return emptySet()
        val rawTokens = splitEvalTokens(query)
        val mapped = rawTokens.map { tok ->
            if (!useSynonyms) {
                tok
            } else {
                MODEL_SYNONYM_CANONICAL[tok] ?: tok
            }
        }.toMutableSet()

        if (useSynonyms && "bwg" in mapped && mapped.any { it in BWG_PRO_IMPLIED }) {
            mapped.remove("pro")
        }
        return mapped
    }

    private fun mapCyclePreset(preset: BillingCyclePreset?): String? = when (preset) {
        BillingCyclePreset.MONTHLY -> "month"
        BillingCyclePreset.QUARTERLY -> "quarter"
        BillingCyclePreset.SEMI_ANNUAL -> "semi_annual"
        BillingCyclePreset.ANNUAL -> "year"
        BillingCyclePreset.BIENNIAL -> "2year"
        BillingCyclePreset.TRIENNIAL -> "3year"
        else -> null
    }

    private fun matchesExpectedExpiry(
        parsed: ParsedExpiryInfo,
        expected: String,
        now: LocalDate,
    ): Boolean = when {
        expected.endsWith("d") -> {
            val expectedDays = expected.dropLast(1).toIntOrNull()
            expectedDays != null && parsed.remainingDays == expectedDays
        }
        expected.startsWith("--") && expected.length == 7 -> {
            val m = expected.substring(2, 4).toIntOrNull()
            val d = expected.substring(5, 7).toIntOrNull()
            m != null && d != null && parsed.expiryDate.monthValue == m && parsed.expiryDate.dayOfMonth == d
        }
        expected.startsWith("--") && expected.length == 4 -> {
            val m = expected.substring(2, 4).toIntOrNull()
            m != null && parsed.expiryDate.monthValue == m
        }
        expected.length == 7 && expected[4] == '-' -> {
            val y = expected.substring(0, 4).toIntOrNull()
            val m = expected.substring(5, 7).toIntOrNull()
            y != null && m != null && parsed.expiryDate.year == y && parsed.expiryDate.monthValue == m
        }
        else -> {
            val expectedDate = runCatching { LocalDate.parse(expected) }.getOrNull()
            expectedDate != null && parsed.expiryDate == expectedDate && parsed.remainingDays >= 0 && now <= expectedDate
        }
    }

    private fun splitEvalTokens(raw: String): List<String> {
        val normalized = TradeMarketPremiumAnalyzer.normalizeWidthAndCase(raw)
            .replace(HYPHEN_DC_JOIN) { m -> "dc${m.groupValues[1]}" }
            .replace(COMPACT_CODE_JOIN) { m -> "${m.groupValues[1]}${m.groupValues[2]}" }
        val tokens = ArrayList<String>()
        var start = -1
        var prevAscii = false
        for (i in normalized.indices) {
            val ch = normalized[i]
            val keepDot = ch == '.' && start >= 0 && prevAscii && isProtectedDot(normalized, start, i)
            val isChar = ch.isLetterOrDigit() || keepDot
            if (!isChar) {
                if (start >= 0) {
                    tokens += normalized.substring(start, i)
                    start = -1
                }
            } else {
                val isAscii = ch in 'a'..'z' || ch in '0'..'9' || keepDot
                if (start < 0) {
                    start = i
                    prevAscii = isAscii
                } else if (isAscii != prevAscii) {
                    tokens += normalized.substring(start, i)
                    start = i
                    prevAscii = isAscii
                }
            }
        }
        if (start >= 0) {
            tokens += normalized.substring(start)
        }
        return tokens
    }

    private fun isProtectedDot(text: String, tokenStart: Int, dotIndex: Int): Boolean {
        if (dotIndex <= tokenStart || dotIndex + 1 >= text.length) return false
        if (dotIndex - tokenStart == 1 &&
            text[tokenStart] == 'v' &&
            dotIndex + 2 < text.length &&
            text[dotIndex + 1] == 'p' &&
            text[dotIndex + 2] == 's' &&
            (dotIndex + 3 >= text.length || !(text[dotIndex + 3] in 'a'..'z' || text[dotIndex + 3] in '0'..'9'))
        ) {
            return true
        }
        if (text[dotIndex - 1] in '0'..'9' && text[dotIndex + 1] in '0'..'9') {
            for (k in tokenStart until dotIndex) {
                if (text[k] in 'a'..'z') return true
            }
        }
        return false
    }
}
