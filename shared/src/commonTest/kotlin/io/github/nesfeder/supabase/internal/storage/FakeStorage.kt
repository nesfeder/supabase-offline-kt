package io.github.nesfeder.supabase.internal.storage

import io.github.nesfeder.supabase.internal.FakeSupabaseSerializer
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.SupabaseSerializer
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.storage.Bucket
import io.github.jan.supabase.storage.BucketApi
import io.github.jan.supabase.storage.BucketBuilder
import io.github.jan.supabase.storage.Storage
import io.ktor.client.statement.HttpResponse

class FakeStorage(
    val buckets: MutableMap<String, BucketApi> = mutableMapOf()
) : Storage {

    override val config: Storage.Config = Storage.Config()
    override val supabaseClient: SupabaseClient
        get() = error("supabaseClient not used in FakeStorage")
    override val apiVersion: Int = 1
    override val pluginKey: String = Storage.key

    override fun from(bucketId: String): BucketApi {
        return buckets.getOrPut(bucketId) { FakeBucketApi(bucketId) }
    }

    override suspend fun createBucket(id: String, builder: BucketBuilder.() -> Unit) {
        error("createBucket not implemented in FakeStorage")
    }

    override suspend fun updateBucket(id: String, builder: BucketBuilder.() -> Unit) {
        error("updateBucket not implemented in FakeStorage")
    }

    override suspend fun retrieveBuckets(): List<Bucket> {
        error("retrieveBuckets not implemented in FakeStorage")
    }

    override suspend fun retrieveBucketById(bucketId: String): Bucket {
        error("retrieveBucketById not implemented in FakeStorage")
    }

    override suspend fun emptyBucket(bucketId: String) {
        error("emptyBucket not implemented in FakeStorage")
    }

    override suspend fun deleteBucket(bucketId: String) {
        error("deleteBucket not implemented in FakeStorage")
    }

    override fun get(bucketId: String): BucketApi = from(bucketId)

    override suspend fun parseErrorResponse(response: HttpResponse): RestException {
        return RestException("stubbed", "desc", response)
    }

    override val serializer: SupabaseSerializer
        get() = FakeSupabaseSerializer()
}
