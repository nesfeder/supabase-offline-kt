# ``SupabaseOffline/SupabaseOfflineClient``

## Topics

### Creating a client

- ``init(supabaseURL:supabaseKey:config:)``
- ``Config``
- ``CacheSize``
- ``LogLevel``

### Reading and writing tables

- ``from(_:)``
- ``from(schema:table:)``

### Calling database functions

- ``rpc(_:params:count:schema:)-(_,Encodable,_,_)``
- ``rpc(_:params:count:schema:)-(_,[String:OfflineJSON],_,_)``
- ``rpc(_:head:get:count:schema:)``

### Files

- ``storage``

### The queue and sync

- ``pendingMutations(table:)``
- ``pendingMutationCount(table:)``
- ``pendingMutationCountSequence(table:)``
- ``discardPendingMutation(id:)``
- ``rejectedMutations()``
- ``rejectedMutationList()``
- ``retryRejectedMutation(id:)``
- ``dismissRejectedMutation(id:)``
- ``syncPendingMutations(table:)``

### Conflicts

- ``conflictedMutations()``
- ``conflictedMutationList()``
- ``resolveConflictedMutation(id:resolution:)``
- ``dismissConflictedMutation(id:)``

### Status and the network

- ``syncStatus``
- ``disableNetwork()``
- ``enableNetwork()``

### Realtime

- ``realtimeStatus``
- ``disableRealtime()``
- ``enableRealtime()``

### Local data

- ``clearLocalData()``
- ``applyServerRecord(table:record:primaryKeys:schema:)-(_,Encodable,_,_)``
- ``applyServerRecord(table:record:primaryKeys:schema:)-(_,[String:OfflineJSON],_,_)``
- ``applyServerDelete(table:oldRecord:primaryKeys:schema:)-(_,Encodable,_,_)``
- ``applyServerDelete(table:oldRecord:primaryKeys:schema:)-(_,[String:OfflineJSON],_,_)``

### Closing

- ``close()``
