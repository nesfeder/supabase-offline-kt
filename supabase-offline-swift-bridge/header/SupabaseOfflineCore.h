#import <Foundation/NSArray.h>
#import <Foundation/NSDictionary.h>
#import <Foundation/NSError.h>
#import <Foundation/NSObject.h>
#import <Foundation/NSSet.h>
#import <Foundation/NSString.h>
#import <Foundation/NSValue.h>

@class NSData, SOCBridgeAccessTokenCallback, SOCBridgeCachedFile, SOCBridgeCancellable, SOCBridgeClient, SOCBridgeClientCompanion, SOCBridgeClientOptions, SOCBridgeClientOptionsCompanion, SOCBridgeConflict, SOCBridgeConflictAnswer, SOCBridgeConflictDecision, SOCBridgeConflictedMutation, SOCBridgeError, SOCBridgeErrorKind, SOCBridgeFakeRealtime, SOCBridgeHttpRequest, SOCBridgeHttpResponder, SOCBridgeLogLevel, SOCBridgeMutationOperation, SOCBridgeMutationResult, SOCBridgeNetworkStatus, SOCBridgeOpenResult, SOCBridgePendingMutation, SOCBridgeQueryResult, SOCBridgeQuerySnapshot, SOCBridgeRealtimeFilter, SOCBridgeRealtimeStatus, SOCBridgeRealtimeStatusKind, SOCBridgeRealtimeTable, SOCBridgeRefreshKind, SOCBridgeRefreshState, SOCBridgeRejectedMutation, SOCBridgeServerError, SOCBridgeStorage, SOCBridgeStringResult, SOCBridgeSyncReport, SOCBridgeSyncStatus, SOCBridgeTesting, SOCKotlinArray<T>, SOCKotlinEnum<E>, SOCKotlinEnumCompanion;

@protocol SOCBridgeAccessTokenProvider, SOCBridgeClock, SOCBridgeConflictResolver, SOCBridgeFakeServer, SOCBridgeLogSink, SOCKotlinComparable, SOCKotlinIterator;

NS_ASSUME_NONNULL_BEGIN
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wunknown-warning-option"
#pragma clang diagnostic ignored "-Wincompatible-property-type"
#pragma clang diagnostic ignored "-Wnullability"

#pragma push_macro("_Nullable_result")
#if !__has_feature(nullability_nullable_result)
#undef _Nullable_result
#define _Nullable_result _Nullable
#endif

__attribute__((swift_name("KotlinBase")))
@interface SOCBase : NSObject
- (instancetype)init __attribute__((unavailable));
+ (instancetype)new __attribute__((unavailable));
+ (void)initialize __attribute__((objc_requires_super));
@end

@interface SOCBase (SOCBaseCopying) <NSCopying>
@end

__attribute__((swift_name("KotlinMutableSet")))
@interface SOCMutableSet<ObjectType> : NSMutableSet<ObjectType>
@end

__attribute__((swift_name("KotlinMutableDictionary")))
@interface SOCMutableDictionary<KeyType, ObjectType> : NSMutableDictionary<KeyType, ObjectType>
@end

@interface NSError (NSErrorSOCKotlinException)
@property (readonly) id _Nullable kotlinException;
@end

__attribute__((swift_name("KotlinNumber")))
@interface SOCNumber : NSNumber
- (instancetype)initWithChar:(char)value __attribute__((unavailable));
- (instancetype)initWithUnsignedChar:(unsigned char)value __attribute__((unavailable));
- (instancetype)initWithShort:(short)value __attribute__((unavailable));
- (instancetype)initWithUnsignedShort:(unsigned short)value __attribute__((unavailable));
- (instancetype)initWithInt:(int)value __attribute__((unavailable));
- (instancetype)initWithUnsignedInt:(unsigned int)value __attribute__((unavailable));
- (instancetype)initWithLong:(long)value __attribute__((unavailable));
- (instancetype)initWithUnsignedLong:(unsigned long)value __attribute__((unavailable));
- (instancetype)initWithLongLong:(long long)value __attribute__((unavailable));
- (instancetype)initWithUnsignedLongLong:(unsigned long long)value __attribute__((unavailable));
- (instancetype)initWithFloat:(float)value __attribute__((unavailable));
- (instancetype)initWithDouble:(double)value __attribute__((unavailable));
- (instancetype)initWithBool:(BOOL)value __attribute__((unavailable));
- (instancetype)initWithInteger:(NSInteger)value __attribute__((unavailable));
- (instancetype)initWithUnsignedInteger:(NSUInteger)value __attribute__((unavailable));
+ (instancetype)numberWithChar:(char)value __attribute__((unavailable));
+ (instancetype)numberWithUnsignedChar:(unsigned char)value __attribute__((unavailable));
+ (instancetype)numberWithShort:(short)value __attribute__((unavailable));
+ (instancetype)numberWithUnsignedShort:(unsigned short)value __attribute__((unavailable));
+ (instancetype)numberWithInt:(int)value __attribute__((unavailable));
+ (instancetype)numberWithUnsignedInt:(unsigned int)value __attribute__((unavailable));
+ (instancetype)numberWithLong:(long)value __attribute__((unavailable));
+ (instancetype)numberWithUnsignedLong:(unsigned long)value __attribute__((unavailable));
+ (instancetype)numberWithLongLong:(long long)value __attribute__((unavailable));
+ (instancetype)numberWithUnsignedLongLong:(unsigned long long)value __attribute__((unavailable));
+ (instancetype)numberWithFloat:(float)value __attribute__((unavailable));
+ (instancetype)numberWithDouble:(double)value __attribute__((unavailable));
+ (instancetype)numberWithBool:(BOOL)value __attribute__((unavailable));
+ (instancetype)numberWithInteger:(NSInteger)value __attribute__((unavailable));
+ (instancetype)numberWithUnsignedInteger:(NSUInteger)value __attribute__((unavailable));
@end

__attribute__((swift_name("KotlinByte")))
@interface SOCByte : SOCNumber
- (instancetype)initWithChar:(char)value;
+ (instancetype)numberWithChar:(char)value;
@end

__attribute__((swift_name("KotlinUByte")))
@interface SOCUByte : SOCNumber
- (instancetype)initWithUnsignedChar:(unsigned char)value;
+ (instancetype)numberWithUnsignedChar:(unsigned char)value;
@end

__attribute__((swift_name("KotlinShort")))
@interface SOCShort : SOCNumber
- (instancetype)initWithShort:(short)value;
+ (instancetype)numberWithShort:(short)value;
@end

__attribute__((swift_name("KotlinUShort")))
@interface SOCUShort : SOCNumber
- (instancetype)initWithUnsignedShort:(unsigned short)value;
+ (instancetype)numberWithUnsignedShort:(unsigned short)value;
@end

__attribute__((swift_name("KotlinInt")))
@interface SOCInt : SOCNumber
- (instancetype)initWithInt:(int)value;
+ (instancetype)numberWithInt:(int)value;
@end

__attribute__((swift_name("KotlinUInt")))
@interface SOCUInt : SOCNumber
- (instancetype)initWithUnsignedInt:(unsigned int)value;
+ (instancetype)numberWithUnsignedInt:(unsigned int)value;
@end

__attribute__((swift_name("KotlinLong")))
@interface SOCLong : SOCNumber
- (instancetype)initWithLongLong:(long long)value;
+ (instancetype)numberWithLongLong:(long long)value;
@end

__attribute__((swift_name("KotlinULong")))
@interface SOCULong : SOCNumber
- (instancetype)initWithUnsignedLongLong:(unsigned long long)value;
+ (instancetype)numberWithUnsignedLongLong:(unsigned long long)value;
@end

__attribute__((swift_name("KotlinFloat")))
@interface SOCFloat : SOCNumber
- (instancetype)initWithFloat:(float)value;
+ (instancetype)numberWithFloat:(float)value;
@end

__attribute__((swift_name("KotlinDouble")))
@interface SOCDouble : SOCNumber
- (instancetype)initWithDouble:(double)value;
+ (instancetype)numberWithDouble:(double)value;
@end

__attribute__((swift_name("KotlinBoolean")))
@interface SOCBoolean : SOCNumber
- (instancetype)initWithBool:(BOOL)value;
+ (instancetype)numberWithBool:(BOOL)value;
@end


/** The one-time answer to a [BridgeAccessTokenProvider.fetchAccessToken] call. */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("BridgeAccessTokenCallback")))
@interface SOCBridgeAccessTokenCallback : SOCBase

/**
 * Answers with a failure: the request fails with [BridgeErrorKind.ACCESS_TOKEN] and [message].
 */
- (void)failMessage:(NSString *)message __attribute__((swift_name("fail(message:)")));

/** Answers with [token], or with `null` to send the project key alone. */
- (void)succeedToken:(NSString * _Nullable)token __attribute__((swift_name("succeed(token:)")));
@end


/** Supplies the access token each request carries: the app's own session, behind a callback. */
__attribute__((swift_name("BridgeAccessTokenProvider")))
@protocol SOCBridgeAccessTokenProvider
@required

/**
 * Fetches the current access token and hands it to [callback], once, on any thread: a token,
 * `null` for the key alone, or a failure. The request waits until then.
 */
- (void)fetchAccessTokenCallback:(SOCBridgeAccessTokenCallback *)callback __attribute__((swift_name("fetchAccessToken(callback:)")));
@end


/**
 * A Storage object held in the offline file cache: read it or open it, but never move or write it.
 *
 * @property path the cached copy's absolute path on this device.
 * @property fetchedAtEpochMillis when it was downloaded, in milliseconds since 1970.
 * @property sizeBytes its size on disk.
 */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("BridgeCachedFile")))
@interface SOCBridgeCachedFile : SOCBase
@property (readonly) int64_t fetchedAtEpochMillis __attribute__((swift_name("fetchedAtEpochMillis")));
@property (readonly) NSString *path __attribute__((swift_name("path")));
@property (readonly) int64_t sizeBytes __attribute__((swift_name("sizeBytes")));
@end


/**
 * The handle every asynchronous bridge call returns: cancelling it cancels the Kotlin work behind
 * the call, whose completion then fires with [BridgeErrorKind.CANCELLED] unless its answer was
 * already decided.
 */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("BridgeCancellable")))
@interface SOCBridgeCancellable : SOCBase

/** Cancels the call; safe to call more than once, from any thread, and after it finished. */
- (void)cancel __attribute__((swift_name("cancel()")));
@end


/**
 * The offline client, as Swift sees it: every operation of the library, each taking a completion
 * block and returning a [BridgeCancellable].
 *
 * Reads and writes are plain JSON descriptions the bridge applies onto supabase-kt's own builders
 * (the format is in the contract note beside this module); bodies and records cross as JSON text. A
 * completion fires exactly once, on the bridge's background threads and never inside the call that
 * takes it; it may run before that call returns. A live read's values arrive in order and never
 * block it. Nothing here throws: a failure arrives as a [BridgeError].
 *
 * Build one with [open]; [close] it when done, which frees its database for another client.
 */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("BridgeClient")))
@interface SOCBridgeClient : SOCBase
@property (class, readonly, getter=companion) SOCBridgeClientCompanion *companion __attribute__((swift_name("companion")));

/**
 * Removes a row the server reports deleted from every cached query of [table].
 *
 * @param oldRecord the deleted row's values, at least its key, a JSON object as text.
 */
- (SOCBridgeCancellable *)applyServerDeleteTable:(NSString *)table oldRecord:(NSString *)oldRecord primaryKeys:(NSArray<NSString *> * _Nullable)primaryKeys schema:(NSString * _Nullable)schema completion:(void (^)(SOCBridgeError * _Nullable))completion __attribute__((swift_name("applyServerDelete(table:oldRecord:primaryKeys:schema:completion:)")));

/**
 * Applies a row the server reports, such as a Realtime record, to every cached query of
 * [table].
 *
 * @param record the row, a JSON object as text.
 * @param primaryKeys the row's key columns, or `null` for the table's configured key.
 * @param schema the table's schema, or `null` for the client's default.
 */
- (SOCBridgeCancellable *)applyServerRecordTable:(NSString *)table record:(NSString *)record primaryKeys:(NSArray<NSString *> * _Nullable)primaryKeys schema:(NSString * _Nullable)schema completion:(void (^)(SOCBridgeError * _Nullable))completion __attribute__((swift_name("applyServerRecord(table:record:primaryKeys:schema:completion:)")));

/**
 * Deletes everything the library keeps on this device: cached rows, downloaded files, and every
 * queued write, which is never sent. The client stays usable.
 */
- (SOCBridgeCancellable *)clearLocalDataCompletion:(void (^)(SOCBridgeError * _Nullable))completion __attribute__((swift_name("clearLocalData(completion:)")));

/**
 * Closes the client and frees its database for another client; nothing is deleted. Calls not
 * yet sent go out first; every call still running afterwards ends with
 * [BridgeErrorKind.CLIENT_CLOSED], and every new call fails with it. Live reads end with
 * `null`. The close itself cannot be cancelled: its handle's [BridgeCancellable.cancel] does
 * nothing. Safe to call more than once; later calls answer once the first is done.
 */
- (SOCBridgeCancellable *)closeCompletion:(void (^)(SOCBridgeError * _Nullable))completion __attribute__((swift_name("close(completion:)")));

/** The current user's writes parked on the conflicted list, oldest first. */
- (SOCBridgeCancellable *)conflictedMutationsCompletion:(void (^)(NSArray<SOCBridgeConflictedMutation *> * _Nullable, SOCBridgeError * _Nullable))completion __attribute__((swift_name("conflictedMutations(completion:)")));

/**
 * Where the channel for [BridgeClientOptions.realtimeTables] stands now; off without named
 * tables and after [close]. A failure to read it is reported as a stalled status carrying the
 * failure.
 */
- (SOCBridgeRealtimeStatus *)currentRealtimeStatus __attribute__((swift_name("currentRealtimeStatus()")));

/**
 * The sync status now, without waiting. Reading it cannot fail in the library; should it fail
 * here, the failure arrives as the returned status's [BridgeSyncStatus.lastSyncError].
 */
- (SOCBridgeSyncStatus *)currentSyncStatus __attribute__((swift_name("currentSyncStatus()")));

/** Turns the library's network access off, as airplane mode does, until [enableNetwork]. */
- (SOCBridgeCancellable *)disableNetworkCompletion:(void (^)(SOCBridgeError * _Nullable))completion __attribute__((swift_name("disableNetwork(completion:)")));

/**
 * Leaves the Realtime channel and joins none until [enableRealtime]; the status reads off once
 * this completes. Not kept across clients.
 */
- (SOCBridgeCancellable *)disableRealtimeCompletion:(void (^)(SOCBridgeError * _Nullable))completion __attribute__((swift_name("disableRealtime(completion:)")));

/** Removes one of the current user's queued writes without sending it: destructive. */
- (SOCBridgeCancellable *)discardPendingMutationId:(int64_t)id completion:(void (^)(SOCBridgeError * _Nullable))completion __attribute__((swift_name("discardPendingMutation(id:completion:)")));

/** Removes an entry from the conflicted list for good, without sending it. */
- (SOCBridgeCancellable *)dismissConflictedMutationId:(int64_t)id completion:(void (^)(SOCBridgeError * _Nullable))completion __attribute__((swift_name("dismissConflictedMutation(id:completion:)")));

/** Removes an entry from the rejected list for good. */
- (SOCBridgeCancellable *)dismissRejectedMutationId:(int64_t)id completion:(void (^)(SOCBridgeError * _Nullable))completion __attribute__((swift_name("dismissRejectedMutation(id:completion:)")));

/** Turns the library's network access back on. */
- (SOCBridgeCancellable *)enableNetworkCompletion:(void (^)(SOCBridgeError * _Nullable))completion __attribute__((swift_name("enableNetwork(completion:)")));

/** Joins the channel again after [disableRealtime], as soon as the network allows. */
- (SOCBridgeCancellable *)enableRealtimeCompletion:(void (^)(SOCBridgeError * _Nullable))completion __attribute__((swift_name("enableRealtime(completion:)")));

/**
 * Writes rows, as [mutation] describes the write.
 *
 * @param mutation the write's description, as JSON text.
 * @param body the rows of an insert or upsert (an array of objects, or one object) or the
 *   columns of an update (an object), as JSON text; `null` for a delete.
 * @param completion the outcome (applied, with the server's response, or queued), or the
 *   failure.
 */
- (SOCBridgeCancellable *)mutateMutation:(NSString *)mutation body:(NSString * _Nullable)body completion:(void (^)(SOCBridgeMutationResult * _Nullable, SOCBridgeError * _Nullable))completion __attribute__((swift_name("mutate(mutation:body:completion:)")));

/**
 * Reads rows as they change: [onSnapshot] gets every emission in order, then [onCompletion]
 * fires once, with `null` when the read ends (the client closed) or with the failure.
 *
 * @param query the read's description, as JSON text; its strategy is a live one.
 */
- (SOCBridgeCancellable *)observeQuery:(NSString *)query onSnapshot:(void (^)(SOCBridgeQuerySnapshot *))onSnapshot onCompletion:(void (^)(SOCBridgeError * _Nullable))onCompletion __attribute__((swift_name("observe(query:onSnapshot:onCompletion:)")));

/** [conflictedMutations] as the list changes; [onCompletion] fires once, when it ends. */
- (SOCBridgeCancellable *)observeConflictedMutationsOnMutations:(void (^)(NSArray<SOCBridgeConflictedMutation *> *))onMutations onCompletion:(void (^)(SOCBridgeError * _Nullable))onCompletion __attribute__((swift_name("observeConflictedMutations(onMutations:onCompletion:)")));

/** [pendingMutationCount] as it changes; a value only when it differs from the last. */
- (SOCBridgeCancellable *)observePendingMutationCountTable:(NSString * _Nullable)table onCount:(void (^)(SOCInt *))onCount onCompletion:(void (^)(SOCBridgeError * _Nullable))onCompletion __attribute__((swift_name("observePendingMutationCount(table:onCount:onCompletion:)")));

/** [pendingMutations] as the queue changes; [onCompletion] fires once, when it ends. */
- (SOCBridgeCancellable *)observePendingMutationsTable:(NSString * _Nullable)table onMutations:(void (^)(NSArray<SOCBridgePendingMutation *> *))onMutations onCompletion:(void (^)(SOCBridgeError * _Nullable))onCompletion __attribute__((swift_name("observePendingMutations(table:onMutations:onCompletion:)")));

/**
 * The channel's status as it changes, starting with the current one; [onCompletion] fires once,
 * with `null`, when the client closes.
 */
- (SOCBridgeCancellable *)observeRealtimeStatusOnStatus:(void (^)(SOCBridgeRealtimeStatus *))onStatus onCompletion:(void (^)(SOCBridgeError * _Nullable))onCompletion __attribute__((swift_name("observeRealtimeStatus(onStatus:onCompletion:)")));

/** [rejectedMutations] as the list changes; [onCompletion] fires once, when it ends. */
- (SOCBridgeCancellable *)observeRejectedMutationsOnMutations:(void (^)(NSArray<SOCBridgeRejectedMutation *> *))onMutations onCompletion:(void (^)(SOCBridgeError * _Nullable))onCompletion __attribute__((swift_name("observeRejectedMutations(onMutations:onCompletion:)")));

/**
 * The sync status as it changes, starting with the current one; [onCompletion] fires once, with
 * `null`, when the client closes.
 */
- (SOCBridgeCancellable *)observeSyncStatusOnStatus:(void (^)(SOCBridgeSyncStatus *))onStatus onCompletion:(void (^)(SOCBridgeError * _Nullable))onCompletion __attribute__((swift_name("observeSyncStatus(onStatus:onCompletion:)")));

/** How many of the current user's writes wait, of [table] or of every table. */
- (SOCBridgeCancellable *)pendingMutationCountTable:(NSString * _Nullable)table completion:(void (^)(SOCInt * _Nullable, SOCBridgeError * _Nullable))completion __attribute__((swift_name("pendingMutationCount(table:completion:)")));

/**
 * The current user's queued writes, of [table] or of every table, in the order they will be
 * sent.
 */
- (SOCBridgeCancellable *)pendingMutationsTable:(NSString * _Nullable)table completion:(void (^)(NSArray<SOCBridgePendingMutation *> * _Nullable, SOCBridgeError * _Nullable))completion __attribute__((swift_name("pendingMutations(table:completion:)")));

/** The current user's writes the server refused for good on replay, oldest first. */
- (SOCBridgeCancellable *)rejectedMutationsCompletion:(void (^)(NSArray<SOCBridgeRejectedMutation *> * _Nullable, SOCBridgeError * _Nullable))completion __attribute__((swift_name("rejectedMutations(completion:)")));

/**
 * Settles an entry of the conflicted list: KEEP_LOCAL and APPLY queue it again under a new id,
 * KEEP_SERVER drops it. A decision the library refuses fails with
 * [BridgeErrorKind.INVALID_ARGUMENT]; APPLY with [columnsJson] that is not a JSON object fails
 * with [BridgeErrorKind.INVALID_REQUEST].
 *
 * @param columnsJson the columns of APPLY, a JSON object as text; `null` for the others.
 */
- (SOCBridgeCancellable *)resolveConflictedMutationId:(int64_t)id decision:(SOCBridgeConflictDecision *)decision columnsJson:(NSString * _Nullable)columnsJson completion:(void (^)(SOCBridgeError * _Nullable))completion __attribute__((swift_name("resolveConflictedMutation(id:decision:columnsJson:completion:)")));

/**
 * Queues a rejected write again, at the end of the queue under a new id; an entry that cannot
 * be retried fails with [BridgeErrorKind.INVALID_ARGUMENT].
 */
- (SOCBridgeCancellable *)retryRejectedMutationId:(int64_t)id completion:(void (^)(SOCBridgeError * _Nullable))completion __attribute__((swift_name("retryRejectedMutation(id:completion:)")));

/**
 * Calls a database function on the server. Network only: never cached, never queued.
 *
 * @param call the call's description (function, method, filters), as JSON text.
 * @param arguments the function's arguments, a JSON object as text, or `null` for none. A call
 *   by GET or HEAD takes none: it fails with [BridgeErrorKind.INVALID_REQUEST].
 */
- (SOCBridgeCancellable *)rpcCall:(NSString *)call arguments:(NSString * _Nullable)arguments completion:(void (^)(SOCBridgeQueryResult * _Nullable, SOCBridgeError * _Nullable))completion __attribute__((swift_name("rpc(call:arguments:completion:)")));

/**
 * Reads rows once, as [query] describes them (table, columns, filters, modifiers and the read
 * strategy).
 *
 * @param query the read's description, as JSON text.
 * @param completion the rows and where they came from, or the failure.
 */
- (SOCBridgeCancellable *)selectQuery:(NSString *)query completion:(void (^)(SOCBridgeQueryResult * _Nullable, SOCBridgeError * _Nullable))completion __attribute__((swift_name("select(query:completion:)")));

/**
 * Reports whether the device is online, from the app's own monitor; only read when the client
 * was opened with [BridgeClientOptions.reportsNetworkAvailability]. A hint, never a gate.
 */
- (void)setNetworkAvailableAvailable:(BOOL)available __attribute__((swift_name("setNetworkAvailable(available:)")));

/** Sends the current user's queued writes now, of [table] or of every table. */
- (SOCBridgeCancellable *)syncPendingMutationsTable:(NSString * _Nullable)table completion:(void (^)(SOCBridgeSyncReport * _Nullable, SOCBridgeError * _Nullable))completion __attribute__((swift_name("syncPendingMutations(table:completion:)")));

/** Storage buckets, with downloads kept on the device. */
@property (readonly) SOCBridgeStorage *storage __attribute__((swift_name("storage")));
@end


/** Opens clients. */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("BridgeClient.Companion")))
@interface SOCBridgeClientCompanion : SOCBase
+ (instancetype)alloc __attribute__((unavailable));

/** Opens clients. */
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)companion __attribute__((swift_name("init()")));
@property (class, readonly, getter=shared) SOCBridgeClientCompanion *shared __attribute__((swift_name("shared")));

/**
 * Opens a client over the network, with its database and file cache in
 * [BridgeClientOptions.dataDirectory]. It claims the database on the calling thread; call
 * it off the main thread when that matters.
 *
 * @return the client, or the failure: another client holds the database, or the options
 *   were refused.
 */
- (SOCBridgeOpenResult *)openOptions:(SOCBridgeClientOptions *)options __attribute__((swift_name("open(options:)")));
@end


/**
 * How a [BridgeClient] is set up: the project, where its data lives, and the app's callbacks.
 *
 * Every setting but the three in the constructor has a default; the client reads them once, when
 * [BridgeClient.open] builds it.
 *
 * @property supabaseUrl the project's URL, such as `https://xyz.supabase.co`.
 * @property supabaseKey the project's publishable (anon) key.
 * @property dataDirectory the directory for the database and the Storage file cache, created when
 *   missing. Always the caller's choice: the bridge never falls back to a platform default.
 */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("BridgeClientOptions")))
@interface SOCBridgeClientOptions : SOCBase
- (instancetype)initWithSupabaseUrl:(NSString *)supabaseUrl supabaseKey:(NSString *)supabaseKey dataDirectory:(NSString *)dataDirectory __attribute__((swift_name("init(supabaseUrl:supabaseKey:dataDirectory:)"))) __attribute__((objc_designated_initializer));
@property (class, readonly, getter=companion) SOCBridgeClientOptionsCompanion *companion __attribute__((swift_name("companion")));

/** Supplies the user's access token per request, or `null` to send the key alone. */
@property id<SOCBridgeAccessTokenProvider> _Nullable accessTokenProvider __attribute__((swift_name("accessTokenProvider")));

/** Whether queued writes are sent by themselves when the network comes back; `true`. */
@property BOOL autoSync __attribute__((swift_name("autoSync")));

/**
 * The most the cached query results may hold, in bytes; [CACHE_SIZE_UNLIMITED] for no bound.
 */
@property int64_t cacheSizeBytes __attribute__((swift_name("cacheSizeBytes")));

/**
 * Settles the conflicts such a replay finds, or `null` for the library's defaults: the local
 * change wins a moved row, and an UPDATE of a row that is gone is dropped.
 */
@property id<SOCBridgeConflictResolver> _Nullable conflictResolver __attribute__((swift_name("conflictResolver")));
@property (readonly) NSString *dataDirectory __attribute__((swift_name("dataDirectory")));

/** The most the Storage file cache may hold, in bytes; [CACHE_SIZE_UNLIMITED] for no bound. */
@property int64_t fileCacheSizeBytes __attribute__((swift_name("fileCacheSizeBytes")));

/** The least severe level that reaches [logSink]; [BridgeLogLevel.WARNING] by default. */
@property SOCBridgeLogLevel *logLevel __attribute__((swift_name("logLevel")));

/** Receives every log message of the library and of supabase-kt, or `null` to drop them. */
@property id<SOCBridgeLogSink> _Nullable logSink __attribute__((swift_name("logSink")));

/** Each table's primary-key columns, by table name; a table not named here has the key `id`. */
@property NSDictionary<NSString *, NSArray<NSString *> *> *primaryKeys __attribute__((swift_name("primaryKeys")));

/**
 * The tables the client keeps current through Realtime: it joins one channel for them while the
 * network is not known down and the subscription is on, applies each change, reads them again
 * on every join, and refreshes the channel's token before it expires through
 * [accessTokenProvider]. None by default, and then no channel is opened.
 */
@property NSArray<SOCBridgeRealtimeTable *> *realtimeTables __attribute__((swift_name("realtimeTables")));

/**
 * Whether the app reports connectivity through [BridgeClient.setNetworkAvailable] in place of
 * the library's own monitor; `false` by default. Until the first report it reads as unknown.
 */
@property BOOL reportsNetworkAvailability __attribute__((swift_name("reportsNetworkAvailability")));

/** The PostgREST schema a description without one reads and writes; `public` by default. */
@property NSString *schema __attribute__((swift_name("schema")));
@property (readonly) NSString *supabaseKey __attribute__((swift_name("supabaseKey")));
@property (readonly) NSString *supabaseUrl __attribute__((swift_name("supabaseUrl")));

/**
 * Each table's version column, by table name, keyed as [primaryKeys] is: a sync pass replays a
 * queued UPDATE or DELETE of one cached row only while the server's row still has the version
 * the device saw. None by default.
 */
@property NSDictionary<NSString *, NSString *> *versionColumns __attribute__((swift_name("versionColumns")));
@end


/** The defaults and the size that turns a cache's bound off. */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("BridgeClientOptions.Companion")))
@interface SOCBridgeClientOptionsCompanion : SOCBase
+ (instancetype)alloc __attribute__((unavailable));

/** The defaults and the size that turns a cache's bound off. */
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)companion __attribute__((swift_name("init()")));
@property (class, readonly, getter=shared) SOCBridgeClientOptionsCompanion *shared __attribute__((swift_name("shared")));

/** The size that turns eviction off, for either cache. */
@property (readonly) int64_t CACHE_SIZE_UNLIMITED __attribute__((swift_name("CACHE_SIZE_UNLIMITED")));

/** The default of [cacheSizeBytes]: 100 MiB, as the library's. */
@property (readonly) int64_t DEFAULT_CACHE_SIZE_BYTES __attribute__((swift_name("DEFAULT_CACHE_SIZE_BYTES")));

/** The default of [fileCacheSizeBytes]: 256 MiB, as the library's. */
@property (readonly) int64_t DEFAULT_FILE_CACHE_SIZE_BYTES __attribute__((swift_name("DEFAULT_FILE_CACHE_SIZE_BYTES")));
@end


/** The time an in-memory client sees, for a test that moves it. */
__attribute__((swift_name("BridgeClock")))
@protocol SOCBridgeClock
@required

/** The current time, in milliseconds since 1970. */
- (int64_t)nowEpochMillis __attribute__((swift_name("nowEpochMillis()")));
@end


/**
 * A conflict a replay found: a queued UPDATE or DELETE of one row whose version on the server is no
 * longer the one the device saw. Rows cross as JSON text.
 *
 * @property mutationId the write's id in the queue.
 * @property table the table the write targets.
 * @property operation [BridgeMutationOperation.UPDATE] or [BridgeMutationOperation.DELETE].
 * @property rowKeyJson the row's key values as the write's filter pins them, as text, a JSON
 *   object.
 * @property baseJson the columns the write sets and the version column, as the device last saw the
 *   row, a JSON object.
 * @property localJson the columns the write sets, a JSON object; `null` for a DELETE.
 * @property serverJson the server's row now, in part, a JSON object; `null` when it is gone.
 * @property changedOnServer the columns the write sets whose server value differs from the base.
 * @property conflictingColumns of [changedOnServer], those the write sets to another value.
 */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("BridgeConflict")))
@interface SOCBridgeConflict : SOCBase
@property (readonly) NSString *baseJson __attribute__((swift_name("baseJson")));
@property (readonly) NSArray<NSString *> *changedOnServer __attribute__((swift_name("changedOnServer")));
@property (readonly) NSArray<NSString *> *conflictingColumns __attribute__((swift_name("conflictingColumns")));
@property (readonly) NSString * _Nullable localJson __attribute__((swift_name("localJson")));
@property (readonly) int64_t mutationId __attribute__((swift_name("mutationId")));
@property (readonly) SOCBridgeMutationOperation *operation __attribute__((swift_name("operation")));
@property (readonly) NSString *rowKeyJson __attribute__((swift_name("rowKeyJson")));
@property (readonly) NSString * _Nullable serverJson __attribute__((swift_name("serverJson")));
@property (readonly) NSString *table __attribute__((swift_name("table")));
@end


/** The one-time answer to a [BridgeConflictResolver.resolve] call; later answers are ignored. */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("BridgeConflictAnswer")))
@interface SOCBridgeConflictAnswer : SOCBase

/**
 * Sends [columnsJson], a JSON object of at least one column, as the UPDATE's body instead; any
 * other text holds the write.
 */
- (void)applyColumnsJson:(NSString *)columnsJson __attribute__((swift_name("apply(columnsJson:)")));

/** Answers that the app's resolver failed, which holds the write; [message] is not logged. */
- (void)failMessage:(NSString *)message __attribute__((swift_name("fail(message:)")));

/** Parks the write on the conflicted list. */
- (void)hold __attribute__((swift_name("hold()")));

/** Sends the write again, preconditioned on the server's row as it is now. */
- (void)keepLocal __attribute__((swift_name("keepLocal()")));

/** Drops the write without sending it; the server's row stands. */
- (void)keepServer __attribute__((swift_name("keepServer()")));
@end

__attribute__((swift_name("KotlinComparable")))
@protocol SOCKotlinComparable
@required
- (int32_t)compareToOther:(id _Nullable)other __attribute__((swift_name("compareTo(other:)")));
@end

__attribute__((swift_name("KotlinEnum")))
@interface SOCKotlinEnum<E> : SOCBase <SOCKotlinComparable>
- (instancetype)initWithName:(NSString *)name ordinal:(int32_t)ordinal __attribute__((swift_name("init(name:ordinal:)"))) __attribute__((objc_designated_initializer));
@property (class, readonly, getter=companion) SOCKotlinEnumCompanion *companion __attribute__((swift_name("companion")));
- (int32_t)compareToOther:(E)other __attribute__((swift_name("compareTo(other:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) NSString *name __attribute__((swift_name("name")));
@property (readonly) int32_t ordinal __attribute__((swift_name("ordinal")));
@end


/** How [BridgeClient.resolveConflictedMutation] settles an entry of the conflicted list. */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("BridgeConflictDecision")))
@interface SOCBridgeConflictDecision : SOCKotlinEnum<SOCBridgeConflictDecision *>
+ (instancetype)alloc __attribute__((unavailable));

/** How [BridgeClient.resolveConflictedMutation] settles an entry of the conflicted list. */
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (instancetype)initWithName:(NSString *)name ordinal:(int32_t)ordinal __attribute__((swift_name("init(name:ordinal:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
@property (class, readonly) SOCBridgeConflictDecision *keepLocal __attribute__((swift_name("keepLocal")));
@property (class, readonly) SOCBridgeConflictDecision *keepServer __attribute__((swift_name("keepServer")));
@property (class, readonly) SOCBridgeConflictDecision *apply __attribute__((swift_name("apply")));
@property (class, readonly) SOCBridgeConflictDecision *hold __attribute__((swift_name("hold")));
+ (SOCKotlinArray<SOCBridgeConflictDecision *> *)values __attribute__((swift_name("values()")));
@property (class, readonly) NSArray<SOCBridgeConflictDecision *> *entries __attribute__((swift_name("entries")));
@end


/**
 * Settles the conflicts a sync pass finds when it replays a guarded write
 * ([BridgeClientOptions.versionColumns]): the app's own resolver, behind a callback.
 */
__attribute__((swift_name("BridgeConflictResolver")))
@protocol SOCBridgeConflictResolver
@required

/**
 * Decides [conflict] and hands the decision to [answer], once, on any thread. The pass waits
 * for it; no answer within 30 seconds, or [BridgeConflictAnswer.fail], holds the write.
 */
- (void)resolveConflict:(SOCBridgeConflict *)conflict answer:(SOCBridgeConflictAnswer *)answer __attribute__((swift_name("resolve(conflict:answer:)")));
@end


/**
 * A queued write parked on the conflicted list, kept until the app resolves or dismisses it.
 *
 * @property id the write's id, as it had in the queue.
 * @property table the table it targeted.
 * @property operation [BridgeMutationOperation.UPDATE] or [BridgeMutationOperation.DELETE].
 * @property createdAtEpochMillis when it was queued, in milliseconds since 1970.
 * @property rowKeysJson the row's key values as the write's filter pins them, as text, a JSON array
 *   of objects.
 * @property bodyJson the body it sends, as JSON text; `null` for a DELETE or one too large to keep.
 * @property baseJson the columns it sets and the version column as the device saw them, a JSON
 *   object.
 * @property serverJson the server's row when the replay read it, in part, a JSON object; `null`
 *   when it was gone or too large to keep.
 * @property serverVersion the server row's version then, as text; `null` when the row was gone.
 * @property changedOnServer the columns it sets whose server value differed from the base.
 * @property conflictingColumns of [changedOnServer], those it sets to another value.
 * @property message why it was parked, in words.
 * @property conflictedAtEpochMillis when it was parked, in milliseconds since 1970.
 */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("BridgeConflictedMutation")))
@interface SOCBridgeConflictedMutation : SOCBase
@property (readonly) NSString *baseJson __attribute__((swift_name("baseJson")));
@property (readonly) NSString * _Nullable bodyJson __attribute__((swift_name("bodyJson")));
@property (readonly) NSArray<NSString *> *changedOnServer __attribute__((swift_name("changedOnServer")));
@property (readonly) int64_t conflictedAtEpochMillis __attribute__((swift_name("conflictedAtEpochMillis")));
@property (readonly) NSArray<NSString *> *conflictingColumns __attribute__((swift_name("conflictingColumns")));
@property (readonly) int64_t createdAtEpochMillis __attribute__((swift_name("createdAtEpochMillis")));
@property (readonly) int64_t id __attribute__((swift_name("id")));
@property (readonly) NSString *message __attribute__((swift_name("message")));
@property (readonly) SOCBridgeMutationOperation *operation __attribute__((swift_name("operation")));
@property (readonly) NSString *rowKeysJson __attribute__((swift_name("rowKeysJson")));
@property (readonly) NSString * _Nullable serverJson __attribute__((swift_name("serverJson")));
@property (readonly) NSString * _Nullable serverVersion __attribute__((swift_name("serverVersion")));
@property (readonly) NSString *table __attribute__((swift_name("table")));
@end


/**
 * Every failure of a bridge call, as a value: nothing the bridge exports ever throws.
 *
 * @property kind what failed; decides what the other fields hold.
 * @property message what happened, in words. It never quotes a URL, a header, a row or a token.
 * @property httpStatus the server's HTTP status, for [BridgeErrorKind.SERVER],
 *   [BridgeErrorKind.HTTP] and a [BridgeErrorKind.SYNC_FAILED] the server caused; otherwise `null`.
 * @property code the Postgres SQLSTATE or PostgREST code (such as `23505` or `PGRST204`), or
 *   Storage's error code (such as `not_found`), when the server sent one.
 * @property details the server's details, when it sent any.
 * @property hint the server's hint, when it sent one.
 * @property isConnectivityFailure whether the network, not the server, failed: `true` for
 *   [BridgeErrorKind.CONNECTIVITY], [BridgeErrorKind.NETWORK_DISABLED],
 *   [BridgeErrorKind.MUTATION_NOT_QUEUEABLE] and a [BridgeErrorKind.SYNC_FAILED] the network
 *   caused.
 * @property debugType the name of the failure's type, for diagnostics only; never matched on.
 */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("BridgeError")))
@interface SOCBridgeError : SOCBase

/** The kind and message, for a log line; no other field. */
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) NSString * _Nullable code __attribute__((swift_name("code")));
@property (readonly) NSString *debugType __attribute__((swift_name("debugType")));
@property (readonly) NSString * _Nullable details __attribute__((swift_name("details")));
@property (readonly) NSString * _Nullable hint __attribute__((swift_name("hint")));
@property (readonly) SOCInt * _Nullable httpStatus __attribute__((swift_name("httpStatus")));
@property (readonly) BOOL isConnectivityFailure __attribute__((swift_name("isConnectivityFailure")));
@property (readonly) SOCBridgeErrorKind *kind __attribute__((swift_name("kind")));
@property (readonly) NSString *message __attribute__((swift_name("message")));
@end


/** What a [BridgeError] reports. A later build may add a kind; match the ones you handle. */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("BridgeErrorKind")))
@interface SOCBridgeErrorKind : SOCKotlinEnum<SOCBridgeErrorKind *>
+ (instancetype)alloc __attribute__((unavailable));

/** What a [BridgeError] reports. A later build may add a kind; match the ones you handle. */
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (instancetype)initWithName:(NSString *)name ordinal:(int32_t)ordinal __attribute__((swift_name("init(name:ordinal:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
@property (class, readonly) SOCBridgeErrorKind *cancelled __attribute__((swift_name("cancelled")));
@property (class, readonly) SOCBridgeErrorKind *clientClosed __attribute__((swift_name("clientClosed")));
@property (class, readonly) SOCBridgeErrorKind *connectivity __attribute__((swift_name("connectivity")));
@property (class, readonly) SOCBridgeErrorKind *server __attribute__((swift_name("server")));
@property (class, readonly) SOCBridgeErrorKind *http __attribute__((swift_name("http")));
@property (class, readonly) SOCBridgeErrorKind *cacheMiss __attribute__((swift_name("cacheMiss")));
@property (class, readonly) SOCBridgeErrorKind *networkDisabled __attribute__((swift_name("networkDisabled")));
@property (class, readonly) SOCBridgeErrorKind *mutationNotQueueable __attribute__((swift_name("mutationNotQueueable")));
@property (class, readonly) SOCBridgeErrorKind *syncFailed __attribute__((swift_name("syncFailed")));
@property (class, readonly) SOCBridgeErrorKind *accessToken __attribute__((swift_name("accessToken")));
@property (class, readonly) SOCBridgeErrorKind *invalidRequest __attribute__((swift_name("invalidRequest")));
@property (class, readonly) SOCBridgeErrorKind *unsupported __attribute__((swift_name("unsupported")));
@property (class, readonly) SOCBridgeErrorKind *invalidArgument __attribute__((swift_name("invalidArgument")));
@property (class, readonly) SOCBridgeErrorKind *decoding __attribute__((swift_name("decoding")));
@property (class, readonly) SOCBridgeErrorKind *unexpected __attribute__((swift_name("unexpected")));
+ (SOCKotlinArray<SOCBridgeErrorKind *> *)values __attribute__((swift_name("values()")));
@property (class, readonly) NSArray<SOCBridgeErrorKind *> *entries __attribute__((swift_name("entries")));
@end


/**
 * The server's row changes an app's test reports to an in-memory client, in place of a Realtime
 * channel; hand one to [BridgeTesting.openInMemory]. The client joins a channel here as it would
 * Realtime's, and the fake answers each join at once. Records cross as JSON text.
 */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("BridgeFakeRealtime")))
@interface SOCBridgeFakeRealtime : SOCBase

/**
 * The server's row changes an app's test reports to an in-memory client, in place of a Realtime
 * channel; hand one to [BridgeTesting.openInMemory]. The client joins a channel here as it would
 * Realtime's, and the fake answers each join at once. Records cross as JSON text.
 */
- (instancetype)init __attribute__((swift_name("init()"))) __attribute__((objc_designated_initializer));

/**
 * The server's row changes an app's test reports to an in-memory client, in place of a Realtime
 * channel; hand one to [BridgeTesting.openInMemory]. The client joins a channel here as it would
 * Realtime's, and the fake answers each join at once. Records cross as JSON text.
 */
+ (instancetype)new __attribute__((availability(swift, unavailable, message="use object initializers instead")));

/** Ends the joined channel as a lost socket: the client joins again after a short wait. */
- (void)dropChannel __attribute__((swift_name("dropChannel()")));

/**
 * Ends the joined channel as a server that refuses the join or closes the channel: the client
 * reports the channel stalled, with [message], and joins again after a short wait.
 */
- (void)failChannelMessage:(NSString *)message __attribute__((swift_name("failChannel(message:)")));

/**
 * Reports a row of [table] deleted on the server.
 *
 * @param oldRecord the deleted row's values, a JSON object carrying at least its key.
 * @param schema the table's schema, or `null` for the client's default schema.
 * @return `null`, or the failure when [oldRecord] is not a JSON object.
 */
- (SOCBridgeError * _Nullable)reportDeleteTable:(NSString *)table oldRecord:(NSString *)oldRecord schema:(NSString * _Nullable)schema __attribute__((swift_name("reportDelete(table:oldRecord:schema:)")));

/**
 * Reports a row of [table] inserted on the server.
 *
 * @param record the whole row, a JSON object as text.
 * @param schema the table's schema, or `null` for the client's default schema.
 * @return `null`, or the failure when [record] is not a JSON object.
 */
- (SOCBridgeError * _Nullable)reportInsertTable:(NSString *)table record:(NSString *)record schema:(NSString * _Nullable)schema __attribute__((swift_name("reportInsert(table:record:schema:)")));

/**
 * Reports a row of [table] updated on the server.
 *
 * @param record the whole row after the update, a JSON object as text.
 * @param schema the table's schema, or `null` for the client's default schema.
 * @return `null`, or the failure when [record] is not a JSON object.
 */
- (SOCBridgeError * _Nullable)reportUpdateTable:(NSString *)table record:(NSString *)record schema:(NSString * _Nullable)schema __attribute__((swift_name("reportUpdate(table:record:schema:)")));

/** How many reports found no joined channel following their table and were dropped. */
@property (readonly) int32_t droppedReportCount __attribute__((swift_name("droppedReportCount")));

/**
 * The tables the client follows now, keyed as the library keys them: the name alone in the
 * default schema, `schema.table` in another; empty while no channel is joined.
 */
@property (readonly) NSSet<NSString *> *subscribedTables __attribute__((swift_name("subscribedTables")));
@end


/** A server an app scripts: it answers each request of an in-memory client. */
__attribute__((swift_name("BridgeFakeServer")))
@protocol SOCBridgeFakeServer
@required

/** Answers [request], once, through [responder], on any thread and at any later time. */
- (void)respondRequest:(SOCBridgeHttpRequest *)request responder:(SOCBridgeHttpResponder *)responder __attribute__((swift_name("respond(request:responder:)")));
@end


/**
 * One request an in-memory client sent.
 *
 * @property method the HTTP method, such as `GET`.
 * @property url the full URL, query included.
 * @property headers the request's headers, a header sent more than once joined with commas.
 * @property body the request's body; empty when it has none.
 */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("BridgeHttpRequest")))
@interface SOCBridgeHttpRequest : SOCBase
@property (readonly) NSData *body __attribute__((swift_name("body")));
@property (readonly) NSDictionary<NSString *, NSString *> *headers __attribute__((swift_name("headers")));
@property (readonly) NSString *method __attribute__((swift_name("method")));
@property (readonly) NSString *url __attribute__((swift_name("url")));
@end


/**
 * The one-time answer to a [BridgeHttpRequest]: a response, or a failed connection. A second
 * answer, or one after the request was abandoned, is dropped.
 */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("BridgeHttpResponder")))
@interface SOCBridgeHttpResponder : SOCBase

/** Answers as a request that never reached the server: the client reads it as offline. */
- (void)failWithoutConnectionMessage:(NSString *)message __attribute__((swift_name("failWithoutConnection(message:)")));

/**
 * Answers with an HTTP response. A body over [Int.MAX_VALUE] bytes fails the request instead,
 * as a transport failure naming the size.
 */
- (void)respondStatus:(int32_t)status headers:(NSDictionary<NSString *, NSString *> *)headers body:(NSData *)body __attribute__((swift_name("respond(status:headers:body:)")));
@end


/** How severe a log message is, least to most; [NONE] as a setting turns logging off. */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("BridgeLogLevel")))
@interface SOCBridgeLogLevel : SOCKotlinEnum<SOCBridgeLogLevel *>
+ (instancetype)alloc __attribute__((unavailable));

/** How severe a log message is, least to most; [NONE] as a setting turns logging off. */
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (instancetype)initWithName:(NSString *)name ordinal:(int32_t)ordinal __attribute__((swift_name("init(name:ordinal:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
@property (class, readonly) SOCBridgeLogLevel *debug __attribute__((swift_name("debug")));
@property (class, readonly) SOCBridgeLogLevel *info __attribute__((swift_name("info")));
@property (class, readonly) SOCBridgeLogLevel *warning __attribute__((swift_name("warning")));
@property (class, readonly) SOCBridgeLogLevel *error __attribute__((swift_name("error")));
@property (class, readonly) SOCBridgeLogLevel *none __attribute__((swift_name("none")));
+ (SOCKotlinArray<SOCBridgeLogLevel *> *)values __attribute__((swift_name("values()")));
@property (class, readonly) NSArray<SOCBridgeLogLevel *> *entries __attribute__((swift_name("entries")));
@end


/** Receives log messages; called on any thread, and never with a row, filter value or token. */
__attribute__((swift_name("BridgeLogSink")))
@protocol SOCBridgeLogSink
@required

/**
 * One message, tagged with the component that wrote it. At [BridgeLogLevel.INFO] and above the
 * message names a failure by its type only.
 */
- (void)logLevel:(SOCBridgeLogLevel *)level tag:(NSString *)tag message:(NSString *)message __attribute__((swift_name("log(level:tag:message:)")));
@end


/** What a write does. */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("BridgeMutationOperation")))
@interface SOCBridgeMutationOperation : SOCKotlinEnum<SOCBridgeMutationOperation *>
+ (instancetype)alloc __attribute__((unavailable));

/** What a write does. */
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (instancetype)initWithName:(NSString *)name ordinal:(int32_t)ordinal __attribute__((swift_name("init(name:ordinal:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
@property (class, readonly) SOCBridgeMutationOperation *insert __attribute__((swift_name("insert")));
@property (class, readonly) SOCBridgeMutationOperation *upsert __attribute__((swift_name("upsert")));
@property (class, readonly) SOCBridgeMutationOperation *update __attribute__((swift_name("update")));
@property (class, readonly) SOCBridgeMutationOperation *delete_ __attribute__((swift_name("delete_")));
+ (SOCKotlinArray<SOCBridgeMutationOperation *> *)values __attribute__((swift_name("values()")));
@property (class, readonly) NSArray<SOCBridgeMutationOperation *> *entries __attribute__((swift_name("entries")));
@end


/**
 * What became of a write ([BridgeClient.mutate]): the server applied it, or it waits in the
 * mutation queue.
 *
 * @property isQueued whether the write waits in the mutation queue.
 * @property applied the server's response when it applied the write; `null` when queued.
 * @property queued the queued write when it waits; `null` when applied.
 */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("BridgeMutationResult")))
@interface SOCBridgeMutationResult : SOCBase
@property (readonly) SOCBridgeQueryResult * _Nullable applied __attribute__((swift_name("applied")));
@property (readonly) BOOL isQueued __attribute__((swift_name("isQueued")));
@property (readonly) SOCBridgePendingMutation * _Nullable queued __attribute__((swift_name("queued")));
@end


/** Whether the library believes it can reach the network; a hint, never a gate. */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("BridgeNetworkStatus")))
@interface SOCBridgeNetworkStatus : SOCKotlinEnum<SOCBridgeNetworkStatus *>
+ (instancetype)alloc __attribute__((unavailable));

/** Whether the library believes it can reach the network; a hint, never a gate. */
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (instancetype)initWithName:(NSString *)name ordinal:(int32_t)ordinal __attribute__((swift_name("init(name:ordinal:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
@property (class, readonly) SOCBridgeNetworkStatus *unknown __attribute__((swift_name("unknown")));
@property (class, readonly) SOCBridgeNetworkStatus *online __attribute__((swift_name("online")));
@property (class, readonly) SOCBridgeNetworkStatus *offline __attribute__((swift_name("offline")));
@property (class, readonly) SOCBridgeNetworkStatus *disabled __attribute__((swift_name("disabled")));
+ (SOCKotlinArray<SOCBridgeNetworkStatus *> *)values __attribute__((swift_name("values()")));
@property (class, readonly) NSArray<SOCBridgeNetworkStatus *> *entries __attribute__((swift_name("entries")));
@end


/**
 * A client opened by [BridgeClient.open] or [BridgeTesting.openInMemory]: the client or the
 * failure, never both.
 */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("BridgeOpenResult")))
@interface SOCBridgeOpenResult : SOCBase
@property (readonly) SOCBridgeClient * _Nullable client __attribute__((swift_name("client")));
@property (readonly) SOCBridgeError * _Nullable error __attribute__((swift_name("error")));
@end


/**
 * One write waiting in the mutation queue.
 *
 * @property id the write's id in the queue, which a later rejection keeps.
 * @property table the table it targets.
 * @property operation what it does.
 * @property createdAtEpochMillis when it was queued, in milliseconds since 1970.
 * @property rowKeysJson the primary-key values of the rows it touches, a JSON array of objects.
 * @property bodyJson the body it sends, as JSON text; `null` for a delete.
 * @property baseVersion the row's version a replay is preconditioned on, as text; `null` when the
 *   write is not guarded.
 */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("BridgePendingMutation")))
@interface SOCBridgePendingMutation : SOCBase
@property (readonly) NSString * _Nullable baseVersion __attribute__((swift_name("baseVersion")));
@property (readonly) NSString * _Nullable bodyJson __attribute__((swift_name("bodyJson")));
@property (readonly) int64_t createdAtEpochMillis __attribute__((swift_name("createdAtEpochMillis")));
@property (readonly) int64_t id __attribute__((swift_name("id")));
@property (readonly) SOCBridgeMutationOperation *operation __attribute__((swift_name("operation")));
@property (readonly) NSString *rowKeysJson __attribute__((swift_name("rowKeysJson")));
@property (readonly) NSString *table __attribute__((swift_name("table")));
@end


/**
 * The answer to a one-shot read or a function call: the response body and where it came from.
 *
 * @property data the response body as the server sent it, or the cached rows with the user's queued
 *   writes applied: JSON text for a JSON read, the text itself for CSV.
 * @property count the total a `count` asked for, from the response's `Content-Range`; `null` when
 *   none was asked for or sent.
 * @property isFromCache whether the rows are the device's copy rather than the server's answer.
 * @property fetchedAtEpochMillis when the rows were fetched from the server, by the device's clock,
 *   in milliseconds since 1970; `null` for an answer the library did not stamp.
 * @property hasPendingMutations whether a queued write changed one of the rows.
 */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("BridgeQueryResult")))
@interface SOCBridgeQueryResult : SOCBase
@property (readonly) SOCLong * _Nullable count __attribute__((swift_name("count")));
@property (readonly) NSString *data __attribute__((swift_name("data")));
@property (readonly) SOCLong * _Nullable fetchedAtEpochMillis __attribute__((swift_name("fetchedAtEpochMillis")));
@property (readonly) BOOL hasPendingMutations __attribute__((swift_name("hasPendingMutations")));
@property (readonly) BOOL isFromCache __attribute__((swift_name("isFromCache")));
@end


/**
 * One emission of a live read ([BridgeClient.observe]): the rows as they stand, and how the refresh
 * behind them stands.
 *
 * @property data the rows as JSON text with the user's queued writes applied, or `null` while
 *   nothing is known for the query; `[]` when the server returned no rows.
 * @property isFromCache whether the rows are the device's copy rather than this read's answer.
 * @property fetchedAtEpochMillis when the rows were fetched, in milliseconds since 1970, or `null`.
 * @property hasPendingMutations whether a queued write changed one of the rows shown.
 * @property refresh how the refresh behind the read stands.
 */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("BridgeQuerySnapshot")))
@interface SOCBridgeQuerySnapshot : SOCBase
@property (readonly) NSString * _Nullable data __attribute__((swift_name("data")));
@property (readonly) SOCLong * _Nullable fetchedAtEpochMillis __attribute__((swift_name("fetchedAtEpochMillis")));
@property (readonly) BOOL hasPendingMutations __attribute__((swift_name("hasPendingMutations")));
@property (readonly) BOOL isFromCache __attribute__((swift_name("isFromCache")));
@property (readonly) SOCBridgeRefreshState *refresh __attribute__((swift_name("refresh")));
@end


/**
 * One condition Realtime evaluates on the server: `eq`, `neq`, `gt`, `gte`, `lt`, `lte` or `in`,
 * with one value, or a list of values for `in`. Any other operator refuses the open.
 *
 * @property column the column compared.
 * @property operatorName the operator's name, as a request writes it.
 * @property value the value compared with, as text; `null` when [values] is given.
 * @property values the values of `in`; `null` when [value] is given.
 */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("BridgeRealtimeFilter")))
@interface SOCBridgeRealtimeFilter : SOCBase
- (instancetype)initWithColumn:(NSString *)column operatorName:(NSString *)operatorName value:(NSString * _Nullable)value values:(NSArray<NSString *> * _Nullable)values __attribute__((swift_name("init(column:operatorName:value:values:)"))) __attribute__((objc_designated_initializer));
@property (readonly) NSString *column __attribute__((swift_name("column")));
@property (readonly) NSString *operatorName __attribute__((swift_name("operatorName")));
@property (readonly) NSString * _Nullable value __attribute__((swift_name("value")));
@property (readonly) NSArray<NSString *> * _Nullable values __attribute__((swift_name("values")));
@end


/**
 * Where the client stands with the channel that keeps its Realtime tables current.
 *
 * @property kind the state.
 * @property cause what ended the channel, for [BridgeRealtimeStatusKind.STALLED]; `null` otherwise.
 */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("BridgeRealtimeStatus")))
@interface SOCBridgeRealtimeStatus : SOCBase
@property (readonly) SOCBridgeError * _Nullable cause __attribute__((swift_name("cause")));
@property (readonly) SOCBridgeRealtimeStatusKind *kind __attribute__((swift_name("kind")));
@end


/** The states of the channel that keeps the client's Realtime tables current. */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("BridgeRealtimeStatusKind")))
@interface SOCBridgeRealtimeStatusKind : SOCKotlinEnum<SOCBridgeRealtimeStatusKind *>
+ (instancetype)alloc __attribute__((unavailable));

/** The states of the channel that keeps the client's Realtime tables current. */
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (instancetype)initWithName:(NSString *)name ordinal:(int32_t)ordinal __attribute__((swift_name("init(name:ordinal:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
@property (class, readonly) SOCBridgeRealtimeStatusKind *off __attribute__((swift_name("off")));
@property (class, readonly) SOCBridgeRealtimeStatusKind *paused __attribute__((swift_name("paused")));
@property (class, readonly) SOCBridgeRealtimeStatusKind *subscribing __attribute__((swift_name("subscribing")));
@property (class, readonly) SOCBridgeRealtimeStatusKind *subscribed __attribute__((swift_name("subscribed")));
@property (class, readonly) SOCBridgeRealtimeStatusKind *stalled __attribute__((swift_name("stalled")));
+ (SOCKotlinArray<SOCBridgeRealtimeStatusKind *> *)values __attribute__((swift_name("values()")));
@property (class, readonly) NSArray<SOCBridgeRealtimeStatusKind *> *entries __attribute__((swift_name("entries")));
@end


/**
 * A table the client keeps current through Realtime, as the app names it in its configuration.
 *
 * @property table the table's name.
 * @property schema its schema, or `null` for the client's default schema.
 * @property filter one condition the server evaluates before sending a change, or `null` for every
 *   row the user may read.
 */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("BridgeRealtimeTable")))
@interface SOCBridgeRealtimeTable : SOCBase
- (instancetype)initWithTable:(NSString *)table schema:(NSString * _Nullable)schema filter:(SOCBridgeRealtimeFilter * _Nullable)filter __attribute__((swift_name("init(table:schema:filter:)"))) __attribute__((objc_designated_initializer));
@property (readonly) SOCBridgeRealtimeFilter * _Nullable filter __attribute__((swift_name("filter")));
@property (readonly) NSString * _Nullable schema __attribute__((swift_name("schema")));
@property (readonly) NSString *table __attribute__((swift_name("table")));
@end


/** The states of a live read's refresh, as the library names them. */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("BridgeRefreshKind")))
@interface SOCBridgeRefreshKind : SOCKotlinEnum<SOCBridgeRefreshKind *>
+ (instancetype)alloc __attribute__((unavailable));

/** The states of a live read's refresh, as the library names them. */
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (instancetype)initWithName:(NSString *)name ordinal:(int32_t)ordinal __attribute__((swift_name("init(name:ordinal:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
@property (class, readonly) SOCBridgeRefreshKind *idle __attribute__((swift_name("idle")));
@property (class, readonly) SOCBridgeRefreshKind *refreshing __attribute__((swift_name("refreshing")));
@property (class, readonly) SOCBridgeRefreshKind *paused __attribute__((swift_name("paused")));
@property (class, readonly) SOCBridgeRefreshKind *stalled __attribute__((swift_name("stalled")));
+ (SOCKotlinArray<SOCBridgeRefreshKind *> *)values __attribute__((swift_name("values()")));
@property (class, readonly) NSArray<SOCBridgeRefreshKind *> *entries __attribute__((swift_name("entries")));
@end


/**
 * How the refresh behind a live read stands.
 *
 * @property kind the state.
 * @property cause for [BridgeRefreshKind.STALLED], the failure that stalled the refresh.
 */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("BridgeRefreshState")))
@interface SOCBridgeRefreshState : SOCBase
@property (readonly) SOCBridgeError * _Nullable cause __attribute__((swift_name("cause")));
@property (readonly) SOCBridgeRefreshKind *kind __attribute__((swift_name("kind")));
@end


/**
 * A queued write the server refused for good on replay, kept until it is dismissed or retried.
 *
 * @property id the write's id, as it had in the queue.
 * @property table the table it targeted.
 * @property operation what it did.
 * @property createdAtEpochMillis when it was queued, in milliseconds since 1970.
 * @property rowKeysJson the primary-key values of the rows it touches, a JSON array of objects.
 * @property bodyJson the body it sent, as JSON text; `null` for a delete or one too large to keep.
 * @property serverError what the server answered; `null` when the library refused it itself.
 * @property message why it was refused, in words.
 * @property rejectedAtEpochMillis when it was refused, in milliseconds since 1970.
 * @property isRetryable whether [BridgeClient.retryRejectedMutation] can queue it again.
 */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("BridgeRejectedMutation")))
@interface SOCBridgeRejectedMutation : SOCBase
@property (readonly) NSString * _Nullable bodyJson __attribute__((swift_name("bodyJson")));
@property (readonly) int64_t createdAtEpochMillis __attribute__((swift_name("createdAtEpochMillis")));
@property (readonly) int64_t id __attribute__((swift_name("id")));
@property (readonly) BOOL isRetryable __attribute__((swift_name("isRetryable")));
@property (readonly) NSString *message __attribute__((swift_name("message")));
@property (readonly) SOCBridgeMutationOperation *operation __attribute__((swift_name("operation")));
@property (readonly) int64_t rejectedAtEpochMillis __attribute__((swift_name("rejectedAtEpochMillis")));
@property (readonly) NSString *rowKeysJson __attribute__((swift_name("rowKeysJson")));
@property (readonly) SOCBridgeServerError * _Nullable serverError __attribute__((swift_name("serverError")));
@property (readonly) NSString *table __attribute__((swift_name("table")));
@end


/**
 * PostgREST's answer to a refused write, kept so it survives a restart.
 *
 * @property status the HTTP status.
 * @property code the SQLSTATE or PostgREST code, when sent.
 * @property message the server's message.
 * @property details the server's details, when sent.
 * @property hint the server's hint, when sent.
 */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("BridgeServerError")))
@interface SOCBridgeServerError : SOCBase
@property (readonly) NSString * _Nullable code __attribute__((swift_name("code")));
@property (readonly) NSString * _Nullable details __attribute__((swift_name("details")));
@property (readonly) NSString * _Nullable hint __attribute__((swift_name("hint")));
@property (readonly) NSString *message __attribute__((swift_name("message")));
@property (readonly) int32_t status __attribute__((swift_name("status")));
@end


/**
 * Storage buckets through the offline client: every object operation of the offline bucket, and its
 * file cache. Downloads are kept on the device and served from there offline; uploads, updates,
 * deletes, moves and copies are online only and drop the cached copies they make stale.
 *
 * Every call names its bucket. Options and transforms cross as JSON text, results that are not
 * bytes or files as JSON text too (the formats are in the contract note beside this module); bytes
 * cross as [NSData].
 */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("BridgeStorage")))
@interface SOCBridgeStorage : SOCBase

/** The authenticated URL of [path]; builds a URL and sends nothing. */
- (SOCBridgeStringResult *)authenticatedUrlBucket:(NSString *)bucket path:(NSString *)path __attribute__((swift_name("authenticatedUrl(bucket:path:)")));

/**
 * The cached copy of [path], or `null` with no error when none is cached; never touches the
 * network.
 */
- (SOCBridgeCancellable *)cachedFileBucket:(NSString *)bucket path:(NSString *)path completion:(void (^)(SOCBridgeCachedFile * _Nullable, SOCBridgeError * _Nullable))completion __attribute__((swift_name("cachedFile(bucket:path:completion:)")));

/** Copies the object at [from] to [to], in this bucket or [destinationBucket]. */
- (SOCBridgeCancellable *)doCopyObjectBucket:(NSString *)bucket from:(NSString *)from to:(NSString *)to destinationBucket:(NSString * _Nullable)destinationBucket completion:(void (^)(SOCBridgeError * _Nullable))completion __attribute__((swift_name("doCopyObject(bucket:from:to:destinationBucket:completion:)")));

/**
 * A URL that uploads to [path] without a session.
 *
 * @param completion `{"url", "path", "token"}`, or the failure.
 */
- (SOCBridgeCancellable *)createSignedUploadUrlBucket:(NSString *)bucket path:(NSString *)path upsert:(BOOL)upsert completion:(void (^)(NSString * _Nullable, SOCBridgeError * _Nullable))completion __attribute__((swift_name("createSignedUploadUrl(bucket:path:upsert:completion:)")));

/**
 * A URL that downloads [path] for [expiresInSeconds].
 *
 * @param imageTransform an image transform, `{"width", "height", "resize", "quality",
 *   "format"}`, or `null`.
 */
- (SOCBridgeCancellable *)createSignedUrlBucket:(NSString *)bucket path:(NSString *)path expiresInSeconds:(int64_t)expiresInSeconds imageTransform:(NSString * _Nullable)imageTransform completion:(void (^)(NSString * _Nullable, SOCBridgeError * _Nullable))completion __attribute__((swift_name("createSignedUrl(bucket:path:expiresInSeconds:imageTransform:completion:)")));

/**
 * URLs that download [paths] for [expiresInSeconds].
 *
 * @param completion a JSON array of `{"error", "signedURL", "path"}`, or the failure.
 */
- (SOCBridgeCancellable *)createSignedUrlsBucket:(NSString *)bucket paths:(NSArray<NSString *> *)paths expiresInSeconds:(int64_t)expiresInSeconds completion:(void (^)(NSString * _Nullable, SOCBridgeError * _Nullable))completion __attribute__((swift_name("createSignedUrls(bucket:paths:expiresInSeconds:completion:)")));

/** Deletes the objects at [paths]. */
- (SOCBridgeCancellable *)deleteBucket:(NSString *)bucket paths:(NSArray<NSString *> *)paths completion:(void (^)(SOCBridgeError * _Nullable))completion __attribute__((swift_name("delete(bucket:paths:completion:)")));

/**
 * Downloads the object at [path]: from the network first, cached on success, and from the cache
 * when the network fails.
 *
 * @param isPublic whether to download through the public URL rather than the authenticated one.
 * @param imageTransform an image transform as for [createSignedUrl], or `null`; a transformed
 *   download is cached apart from the original.
 */
- (SOCBridgeCancellable *)downloadBucket:(NSString *)bucket path:(NSString *)path isPublic:(BOOL)isPublic imageTransform:(NSString * _Nullable)imageTransform completion:(void (^)(NSData * _Nullable, SOCBridgeError * _Nullable))completion __attribute__((swift_name("download(bucket:path:isPublic:imageTransform:completion:)")));

/** The cached copy of [path], downloading it into the cache first when none is cached. */
- (SOCBridgeCancellable *)ensureCachedFileBucket:(NSString *)bucket path:(NSString *)path isPublic:(BOOL)isPublic completion:(void (^)(SOCBridgeCachedFile * _Nullable, SOCBridgeError * _Nullable))completion __attribute__((swift_name("ensureCachedFile(bucket:path:isPublic:completion:)")));

/** Whether an object exists at [path]. */
- (SOCBridgeCancellable *)existsBucket:(NSString *)bucket path:(NSString *)path completion:(void (^)(SOCBoolean * _Nullable, SOCBridgeError * _Nullable))completion __attribute__((swift_name("exists(bucket:path:completion:)")));

/** The object at [path]'s details, as supabase-kt's file object in JSON. */
- (SOCBridgeCancellable *)infoBucket:(NSString *)bucket path:(NSString *)path completion:(void (^)(NSString * _Nullable, SOCBridgeError * _Nullable))completion __attribute__((swift_name("info(bucket:path:completion:)")));

/**
 * The objects under [prefix].
 *
 * @param options `{"limit", "offset", "search", "sortBy": {"column", "order"}}`, each optional,
 *   or `null`.
 * @param completion a JSON array of supabase-kt's file objects, or the failure.
 */
- (SOCBridgeCancellable *)listBucket:(NSString *)bucket prefix:(NSString *)prefix options:(NSString * _Nullable)options completion:(void (^)(NSString * _Nullable, SOCBridgeError * _Nullable))completion __attribute__((swift_name("list(bucket:prefix:options:completion:)")));

/** Moves the object at [from] to [to], in this bucket or [destinationBucket]. */
- (SOCBridgeCancellable *)moveObjectBucket:(NSString *)bucket from:(NSString *)from to:(NSString *)to destinationBucket:(NSString * _Nullable)destinationBucket completion:(void (^)(SOCBridgeError * _Nullable))completion __attribute__((swift_name("moveObject(bucket:from:to:destinationBucket:completion:)")));

/** The public URL of [path]; builds a URL and sends nothing. */
- (SOCBridgeStringResult *)publicUrlBucket:(NSString *)bucket path:(NSString *)path __attribute__((swift_name("publicUrl(bucket:path:)")));

/** Replaces the object at [path] with [data]; [options] and the answer as for [upload]. */
- (SOCBridgeCancellable *)updateBucket:(NSString *)bucket path:(NSString *)path data:(NSData *)data options:(NSString * _Nullable)options completion:(void (^)(NSString * _Nullable, SOCBridgeError * _Nullable))completion __attribute__((swift_name("update(bucket:path:data:options:completion:)")));

/**
 * Uploads [data] to [path].
 *
 * @param options `{"contentType", "upsert", "metadata"}`, each optional, or `null`.
 * @param completion the upload response, `{"id", "path", "key"}`, or the failure.
 */
- (SOCBridgeCancellable *)uploadBucket:(NSString *)bucket path:(NSString *)path data:(NSData *)data options:(NSString * _Nullable)options completion:(void (^)(NSString * _Nullable, SOCBridgeError * _Nullable))completion __attribute__((swift_name("upload(bucket:path:data:options:completion:)")));

/**
 * Uploads [data] to [path] through a signed upload URL's [token]; [options] and the answer as
 * for [upload].
 */
- (SOCBridgeCancellable *)uploadToSignedUrlBucket:(NSString *)bucket path:(NSString *)path token:(NSString *)token data:(NSData *)data options:(NSString * _Nullable)options completion:(void (^)(NSString * _Nullable, SOCBridgeError * _Nullable))completion __attribute__((swift_name("uploadToSignedUrl(bucket:path:token:data:options:completion:)")));
@end


/** A synchronous answer: a value or an error, never both. */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("BridgeStringResult")))
@interface SOCBridgeStringResult : SOCBase
@property (readonly) SOCBridgeError * _Nullable error __attribute__((swift_name("error")));
@property (readonly) NSString * _Nullable value __attribute__((swift_name("value")));
@end


/**
 * What one call of [BridgeClient.syncPendingMutations] did with the current user's queued writes.
 *
 * @property syncedCount the writes this call sent and the server accepted.
 * @property rejected the writes refused for good during this call, now on the rejected list.
 * @property remainingCount the writes still queued when the call returned.
 * @property alreadyOnServerCount of [syncedCount], the inserts whose rows the server already held.
 * @property heldBehindOtherTablesCount of [remainingCount], the writes a call for one table held
 *   back behind an earlier write of the user to another table; `0` for a call over every table.
 * @property conflictsResolvedCount of [syncedCount], the guarded writes whose conflict was settled
 *   and that were sent.
 * @property conflictsKeptServerCount the guarded writes dropped unsent because the server's row
 *   stands.
 * @property conflicted the guarded writes parked during this call, now on the conflicted list.
 */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("BridgeSyncReport")))
@interface SOCBridgeSyncReport : SOCBase
@property (readonly) int32_t alreadyOnServerCount __attribute__((swift_name("alreadyOnServerCount")));
@property (readonly) NSArray<SOCBridgeConflictedMutation *> *conflicted __attribute__((swift_name("conflicted")));
@property (readonly) int32_t conflictsKeptServerCount __attribute__((swift_name("conflictsKeptServerCount")));
@property (readonly) int32_t conflictsResolvedCount __attribute__((swift_name("conflictsResolvedCount")));
@property (readonly) int32_t heldBehindOtherTablesCount __attribute__((swift_name("heldBehindOtherTablesCount")));
@property (readonly) NSArray<SOCBridgeRejectedMutation *> *rejected __attribute__((swift_name("rejected")));
@property (readonly) int32_t remainingCount __attribute__((swift_name("remainingCount")));
@property (readonly) int32_t syncedCount __attribute__((swift_name("syncedCount")));
@end


/**
 * Where the library stands with the current user's queued writes and the network: one value to draw
 * a sync banner from.
 *
 * @property network whether the library believes it can reach the network.
 * @property pendingMutationCount the current user's queued writes.
 * @property rejectedMutationCount the current user's entries on the rejected list.
 * @property isSyncing whether a sync pass is sending.
 * @property lastSyncedAtEpochMillis when a pass last left the queue empty, in milliseconds since
 *   1970; `null` until it happens.
 * @property lastSyncError why the last pass stopped with writes queued, a
 *   [BridgeErrorKind.SYNC_FAILED]; `null` once a later pass runs to the end.
 * @property conflictedMutationCount the current user's entries on the conflicted list.
 */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("BridgeSyncStatus")))
@interface SOCBridgeSyncStatus : SOCBase
@property (readonly) int32_t conflictedMutationCount __attribute__((swift_name("conflictedMutationCount")));
@property (readonly) BOOL isSyncing __attribute__((swift_name("isSyncing")));
@property (readonly) SOCBridgeError * _Nullable lastSyncError __attribute__((swift_name("lastSyncError")));
@property (readonly) SOCLong * _Nullable lastSyncedAtEpochMillis __attribute__((swift_name("lastSyncedAtEpochMillis")));
@property (readonly) SOCBridgeNetworkStatus *network __attribute__((swift_name("network")));
@property (readonly) int32_t pendingMutationCount __attribute__((swift_name("pendingMutationCount")));
@property (readonly) int32_t rejectedMutationCount __attribute__((swift_name("rejectedMutationCount")));
@end


/**
 * Clients for the tests and previews of an app: the real engine, with its local data in memory,
 * talking to a server the app scripts.
 */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("BridgeTesting")))
@interface SOCBridgeTesting : SOCBase
+ (instancetype)alloc __attribute__((unavailable));

/**
 * Clients for the tests and previews of an app: the real engine, with its local data in memory,
 * talking to a server the app scripts.
 */
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)bridgeTesting __attribute__((swift_name("init()")));
@property (class, readonly, getter=shared) SOCBridgeTesting *shared __attribute__((swift_name("shared")));

/**
 * Opens a client whose cache, mutation queue and file cache live in memory, and whose every
 * request is answered by [server]. Every option applies as in [BridgeClient.open] but
 * [BridgeClientOptions.dataDirectory], which nothing reads, and PostgREST's retries, which are
 * off so a failure the server scripts arrives at once.
 *
 * The client watches no connectivity monitor: the network reads as unknown (treated as online)
 * until the app reports it ([BridgeClientOptions.reportsNetworkAvailability]) or calls
 * [BridgeClient.disableNetwork].
 *
 * @param clock the time the client sees, or `null` for the device's.
 * @param realtime where the channel for [BridgeClientOptions.realtimeTables] is joined and the
 *   test reports changes, or `null` to follow nothing: with it, no Realtime plugin is needed.
 */
- (SOCBridgeOpenResult *)openInMemoryOptions:(SOCBridgeClientOptions *)options server:(id<SOCBridgeFakeServer>)server clock:(id<SOCBridgeClock> _Nullable)clock realtime:(SOCBridgeFakeRealtime * _Nullable)realtime __attribute__((swift_name("openInMemory(options:server:clock:realtime:)")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("KotlinEnumCompanion")))
@interface SOCKotlinEnumCompanion : SOCBase
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)companion __attribute__((swift_name("init()")));
@property (class, readonly, getter=shared) SOCKotlinEnumCompanion *shared __attribute__((swift_name("shared")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("KotlinArray")))
@interface SOCKotlinArray<T> : SOCBase
+ (instancetype)arrayWithSize:(int32_t)size init:(T _Nullable (^)(SOCInt *))init __attribute__((swift_name("init(size:init:)")));
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (T _Nullable)getIndex:(int32_t)index __attribute__((swift_name("get(index:)")));
- (id<SOCKotlinIterator>)iterator __attribute__((swift_name("iterator()")));
- (void)setIndex:(int32_t)index value:(T _Nullable)value __attribute__((swift_name("set(index:value:)")));
@property (readonly) int32_t size __attribute__((swift_name("size")));
@end

__attribute__((swift_name("KotlinIterator")))
@protocol SOCKotlinIterator
@required
- (BOOL)hasNext __attribute__((swift_name("hasNext()")));
- (id _Nullable)next __attribute__((swift_name("next()")));
@end

#pragma pop_macro("_Nullable_result")
#pragma clang diagnostic pop
NS_ASSUME_NONNULL_END
