@file:OptIn(SupabaseExperimental::class)

package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.jan.supabase.annotations.SupabaseExperimental
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.postgrest.query.filter.PostgrestFilterBuilder
import io.github.jan.supabase.postgrest.query.filter.TextSearchType
import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * One query of the cache-key fixture: a language-neutral description of a SELECT and the cache key
 * the engine stores its response under.
 *
 * @property name a unique name for the case.
 * @property schema the schema the query is made in, or `null` for the client's default schema.
 * @property table the table queried.
 * @property columns the select list, as passed to `Columns.raw`.
 * @property request the builder calls, in order; see [CacheKeyFixture.requestOf].
 * @property cacheKey the key the engine stores the response under.
 */
internal data class CacheKeyFixtureCase(
    val name: String,
    val schema: String?,
    val table: String,
    val columns: String,
    val request: JsonArray,
    val cacheKey: String,
)

/**
 * Reads `cache-key-fixture.json` and turns each case's call list into a supabase-kt request block.
 *
 * A call is a JSON array whose first element names it. Filters: `eq`, `neq`, `gt`, `gte`, `lt`,
 * `lte`, `like`, `ilike`, `match`, `imatch`, `is`, `isdistinct`, `in`, `cs`, `cd`, `ov` (a list),
 * `cs_range`, `cd_range`, `ov_range`, `sl`, `sr`, `nxl`, `nxr`, `adj` (a two-element range), `fts`,
 * `plfts`, `phfts`, `wfts` (a query and an optional configuration), `not` (column, operator, value),
 * and `or` / `and` (a list of calls and optional `negate` / `referencedTable`). The rest: `order`,
 * `limit`, `range`, `schema` (set on the request), `param` (a raw query parameter) and `header`. A JSON number is passed as its
 * literal text, which is what supabase-kt writes for it.
 */
internal object CacheKeyFixture {

    /** Where the fixture sits on the host test's classpath. */
    private const val RESOURCE = "/cache-key-fixture.json"

    /** Every case of the fixture, in file order. */
    fun cases(): List<CacheKeyFixtureCase> {
        val text = checkNotNull(CacheKeyFixture::class.java.getResourceAsStream(RESOURCE)) { "missing $RESOURCE" }
            .use { stream -> stream.readBytes().decodeToString() }
        return Json.parseToJsonElement(text).jsonObject.getValue("cases").jsonArray.map { element ->
            val case = element.jsonObject
            CacheKeyFixtureCase(
                name = case.getValue("name").jsonPrimitive.content,
                schema = (case["schema"] as? JsonPrimitive)?.content,
                table = case.getValue("table").jsonPrimitive.content,
                columns = case["columns"]?.jsonPrimitive?.content ?: Columns.ALL.value,
                request = case["request"]?.jsonArray ?: JsonArray(emptyList()),
                cacheKey = case.getValue("cacheKey").jsonPrimitive.content,
            )
        }
    }

    /** The select list of [case]. */
    fun columnsOf(case: CacheKeyFixtureCase): Columns =
        if (case.columns == Columns.ALL.value) Columns.ALL else Columns.raw(case.columns)

    /** The request block that makes [calls] on a SELECT builder, in order. */
    fun requestOf(calls: JsonArray): SelectRequestBuilder.() -> Unit = {
        calls.forEach { call -> applyCall(call.jsonArray) }
    }

    private fun SelectRequestBuilder.applyCall(call: JsonArray) {
        val name = call[0].jsonPrimitive.content
        val options = call.lastOrNull() as? JsonObject
        when (name) {
            "order" -> order(
                column = call[1].jsonPrimitive.content,
                order = if (call[2].jsonPrimitive.content == "desc") Order.DESCENDING else Order.ASCENDING,
                nullsFirst = options?.get("nullsFirst")?.jsonPrimitive?.booleanOrNull ?: false,
                referencedTable = options?.get("referencedTable")?.jsonPrimitive?.content,
            )

            "limit" -> limit(
                count = checkNotNull(call[1].jsonPrimitive.longOrNull),
                referencedTable = options?.get("referencedTable")?.jsonPrimitive?.content,
            )

            "range" -> range(
                from = checkNotNull(call[1].jsonPrimitive.longOrNull),
                to = checkNotNull(call[2].jsonPrimitive.longOrNull),
                referencedTable = options?.get("referencedTable")?.jsonPrimitive?.content,
            )

            "schema" -> schema = call[1].jsonPrimitive.content
            "param" -> params[call[1].jsonPrimitive.content] = call[2].jsonArray.map { value -> value.jsonPrimitive.content }
            "header" -> headers.append(call[1].jsonPrimitive.content, call[2].jsonPrimitive.content)
            else -> filter { applyFilter(call) }
        }
    }

    private fun PostgrestFilterBuilder.applyFilter(call: JsonArray) {
        val name = call[0].jsonPrimitive.content
        val options = call.lastOrNull() as? JsonObject
        when (name) {
            "or", "and" -> {
                val nested = call[1].jsonArray
                val negate = options?.get("negate")?.jsonPrimitive?.booleanOrNull ?: false
                val referencedTable = options?.get("referencedTable")?.jsonPrimitive?.content
                if (name == "or") {
                    or(negate = negate, referencedTable = referencedTable) { nested.forEach { inner -> applyFilter(inner.jsonArray) } }
                } else {
                    and(negate = negate, referencedTable = referencedTable) { nested.forEach { inner -> applyFilter(inner.jsonArray) } }
                }
            }

            "not" -> filterNot(call[1].jsonPrimitive.content, operatorOf(call[2].jsonPrimitive.content), valueOf(call[3]))
            "fts", "plfts", "phfts", "wfts" -> textSearch(
                column = call[1].jsonPrimitive.content,
                query = call[2].jsonPrimitive.content,
                textSearchType = TextSearchType.entries.first { type -> "${type.identifier}fts" == name },
                config = (call.getOrNull(3) as? JsonPrimitive)?.content,
            )

            "cs_range", "cd_range", "ov_range" -> filter(call[1].jsonPrimitive.content, operatorOf(name.removeSuffix("_range")), pairOf(call[2]))
            "sl", "sr", "nxl", "nxr", "adj" -> filter(call[1].jsonPrimitive.content, operatorOf(name), pairOf(call[2]))
            else -> filter(call[1].jsonPrimitive.content, operatorOf(name), valueOf(call[2]))
        }
    }

    private fun operatorOf(identifier: String): FilterOperator =
        FilterOperator.entries.first { operator -> operator.identifier == identifier }

    private fun pairOf(element: JsonElement): Pair<Any, Any> {
        val bounds = element.jsonArray.map { bound -> checkNotNull(valueOf(bound)) }
        return bounds[0] to bounds[1]
    }

    private fun valueOf(element: JsonElement): Any? = when (element) {
        JsonNull -> null
        is JsonArray -> element.map { item -> checkNotNull(valueOf(item)) }
        is JsonPrimitive -> if (element.isString) element.content else element.booleanOrNull ?: element.content
        is JsonObject -> error("a call argument is never an object")
    }
}
