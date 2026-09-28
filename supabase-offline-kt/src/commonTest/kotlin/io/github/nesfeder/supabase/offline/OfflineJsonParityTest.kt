@file:OptIn(ExperimentalTime::class)

package io.github.nesfeder.supabase.offline

import io.github.nesfeder.supabase.offline.internal.OfflineJson
import io.github.nesfeder.supabase.offline.internal.db.OfflineStore
import io.github.nesfeder.supabase.offline.testdoubles.FakePostgrestQueryBuilderWrapper
import io.github.nesfeder.supabase.offline.testdoubles.postgrestResultOf
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import kotlin.time.ExperimentalTime

/**
 * Locks the offline mutation encode path onto the SDK's shared [OfflineJson] configuration.
 *
 * `insertWith` must serialize caller values with `ignoreUnknownKeys = true` /
 * `encodeDefaults = false` — the shared [OfflineJson] config — so offline-encoded rows match
 * every other cache / overlay / filter round-trip in the SDK. The produced body is pinned to a
 * hardcoded JSON literal, so the test fails both if the builder regresses to a
 * privately-configured `Json` (e.g. one that flips `encodeDefaults`) and if [OfflineJson]'s own
 * config ever drifts. Comparing against `OfflineJson.encodeToJsonElement(...)` alone would move
 * both sides of the assertion together and mask the latter.
 */
class OfflineJsonParityTest {

    @Serializable
    private data class ParityDto(
        val id: String,
        // A default-valued field makes the hardcoded-literal assertion sensitive to
        // `encodeDefaults`: the shared config leaves it false and omits `note`, so a flip to
        // `encodeDefaults = true` (in the builder or in OfflineJson) would emit it and break the
        // expectation.
        val note: String = "default-note",
    )

    @Test
    fun insertWith_encodes_body_with_the_shared_offline_json() = runTest {
        // Given: an offline builder over a recording delegate, and a DTO whose default-valued
        // field only survives encoding under a non-default serializer configuration
        val postgrest = testPostgrest()
        val wrapper = FakePostgrestQueryBuilderWrapper(
            table = "parity_table",
            postgrest = postgrest,
            onInsert = { postgrestResultOf("[]", postgrest) },
        )
        val builder = OfflinePostgrestQueryBuilderImpl(
            delegate = wrapper,
            store = buildStore(),
        )
        val dto = ParityDto(id = "row-1")

        // When: the value is inserted online-only, so the encoded body is handed straight to the
        // delegate with no cache overlay in the way
        builder.insertWith(
            serializer = ParityDto.serializer(),
            value = dto,
            mutationStrategy = MutationStrategy.OnlineOnly,
        )

        // Then: the builder handed the delegate a compact one-element array with the
        // default-valued `note` omitted.
        val recordedBody = wrapper.recordedInsertBodies.single()
        // Pinned to a hardcoded literal so a config drift in OfflineJson itself is caught here,
        // rather than moving both sides of the comparison together and hiding the regression.
        assertEquals("""[{"id":"row-1"}]""", recordedBody.toString())
        // And the builder routed the value through the shared instance — no wrapping or transform
        // of its own — instead of a privately-configured Json.
        assertEquals(
            buildJsonArray { add(OfflineJson.encodeToJsonElement(ParityDto.serializer(), dto)) },
            recordedBody,
        )
    }

    /**
     * Minimal real [Postgrest] instance for [FakePostgrestQueryBuilderWrapper] and
     * [postgrestResultOf]; the builder reads its config for property conversion and stamps it on
     * results. Backed by a [MockEngine] that is never actually hit, since the fake wrapper
     * intercepts every verb.
     */
    private fun testPostgrest(): Postgrest = newTestSupabaseClient().postgrest

    private fun buildStore(): OfflineStore = newTestOfflineStore()
}
