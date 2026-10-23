# ``SupabaseOfflineTesting``

The offline client in memory, for tests and previews, and the values its fakes return.

## Overview

`SupabaseOfflineClient.inMemory(…)` runs the same cache, queue and sync as the client your app ships,
kept in memory, with every request answered by a ``FakeServer`` and the Realtime tables it names
followed through a ``FakeRealtime``. The factories build the values the client reports, for fakes
of your own.

## Topics

### The in-memory client

- ``SupabaseOffline/SupabaseOfflineClient``
- ``FakeServer``
- ``FakeRealtime``
- ``ManualClock``

### Values

- ``pendingMutation(id:table:operation:createdAt:rowKeys:body:)``
- ``pendingMutation(id:table:operation:createdAt:rowKeys:body:baseVersion:)``
- ``rejectedMutation(id:table:operation:createdAt:rowKeys:body:serverError:message:rejectedAt:isRetryable:)``
- ``serverError(status:code:message:details:hint:)``
- ``syncReport(syncedCount:rejected:remainingCount:alreadyOnServerCount:heldBehindOtherTablesCount:)``
- ``syncReport(syncedCount:rejected:remainingCount:alreadyOnServerCount:heldBehindOtherTablesCount:conflictsResolvedCount:conflictsKeptServerCount:conflicted:)``
- ``syncReport(syncedCount:rejected:remainingCount:alreadyOnServerCount:heldBehindOtherTablesCount:conflictsResolvedCount:conflictsKeptServerCount:conflicted:stopReason:)``
- ``syncStopReason(kind:error:)``
- ``syncStatus(network:pendingMutationCount:rejectedMutationCount:isSyncing:lastSyncedAt:lastSyncError:)``
- ``syncStatus(network:pendingMutationCount:rejectedMutationCount:isSyncing:lastSyncedAt:lastSyncError:conflictedMutationCount:)``
- ``mutationConflict(mutationId:table:operation:rowKey:base:local:server:changedOnServer:conflictingColumns:)``
- ``conflictedMutation(id:table:operation:createdAt:rowKeys:body:base:server:serverVersion:changedOnServer:conflictingColumns:message:conflictedAt:)``
- ``querySnapshot(result:isFromCache:fetchedAt:hasPendingMutations:refresh:)``
- ``offlineResponse(value:data:count:isFromCache:fetchedAt:hasPendingMutations:)``
- ``cachedFile(path:fetchedAt:sizeBytes:)``

### Errors

- ``cacheMissError(message:)``
- ``mutationNotQueueableError(message:)``
- ``networkDisabledError(message:)``
- ``syncFailedError(message:httpStatus:code:isConnectivityFailure:)``
- ``offlineError(code:message:serverError:isNetworkOrConnectivityFailure:)``
