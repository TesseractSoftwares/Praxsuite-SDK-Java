@file:Suppress("TooManyFunctions")

package com.tesseractsoftwares.praxsuite.kotlin

import com.tesseractsoftwares.praxsuite.Filters
import com.tesseractsoftwares.praxsuite.PraxAuth
import com.tesseractsoftwares.praxsuite.PraxData
import com.tesseractsoftwares.praxsuite.PraxEndpoints
import com.tesseractsoftwares.praxsuite.Praxsuite
import com.tesseractsoftwares.praxsuite.Query
import com.tesseractsoftwares.praxsuite.Responses
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration

/**
 * Coroutine and DSL extensions for the Praxsuite Java SDK.
 *
 * The Java SDK is already usable from Kotlin without any of this. What these add is the two things
 * Kotlin code actually wants: `suspend` functions that do not block the caller's thread, and a
 * `where { }` block that reads like a query instead of a list of static calls.
 *
 * ```kotlin
 * val page = prax.data.table("Orders")
 *     .select("ID", "Total")
 *     .where { gte("Total", 100); eq("Status", "paid") }
 *     .orderByDescending("Total")
 *     .limit(50)
 *     .fetchAsync()
 * ```
 *
 * **On what `suspend` means here.** The underlying calls are blocking - the Java SDK uses
 * `HttpClient.send`, not `sendAsync` - so these run on [Dispatchers.IO] via [withContext]. That is
 * genuinely useful: your coroutine is suspended rather than parked on a blocked thread, so a slow
 * gateway call does not occupy a dispatcher thread in a Ktor handler. It is not native async I/O,
 * and pretending otherwise would be the kind of wrapper that misleads. If you are making thousands
 * of concurrent calls rather than dozens, size your own dispatcher.
 */

// ── query DSL ───────────────────────────────────────────────────────────────

/**
 * Receiver for [where] and [having]. Every method mirrors a [Filters] function, so nothing here can
 * produce an operator the gateway does not implement.
 */
class FilterBuilder internal constructor() {
    internal val conditions = mutableListOf<Map<String, Any?>>()

    private fun add(c: Map<String, Any?>) { conditions += c }

    fun eq(field: String, value: Any?) = add(Filters.eq(field, value))
    fun neq(field: String, value: Any?) = add(Filters.neq(field, value))
    fun gt(field: String, value: Any?) = add(Filters.gt(field, value))
    fun gte(field: String, value: Any?) = add(Filters.gte(field, value))
    fun lt(field: String, value: Any?) = add(Filters.lt(field, value))
    fun lte(field: String, value: Any?) = add(Filters.lte(field, value))

    /** SQL LIKE, case-sensitive. You supply the wildcards. */
    fun like(field: String, pattern: String) = add(Filters.like(field, pattern))

    /** Case-insensitive LIKE. */
    fun ilike(field: String, pattern: String) = add(Filters.ilike(field, pattern))

    /** Substring match, no wildcards needed. */
    fun contains(field: String, text: String) = add(Filters.contains(field, text))

    /** Full-text search over the column. */
    fun textSearch(field: String, q: String) = add(Filters.textSearch(field, q))

    /** Compiles to `like 'value%'` - there is no startsWith operator server-side. */
    fun startsWith(field: String, value: String) = add(Filters.startsWith(field, value))

    /** Compiles to `like '%value'`. */
    fun endsWith(field: String, value: String) = add(Filters.endsWith(field, value))

    /** Compiles to `is null` - the gateway's `is` only tests for null. */
    fun isNull(field: String) = add(Filters.isNull(field))

    /** Compiles to `neq null`. */
    fun isNotNull(field: String) = add(Filters.isNotNull(field))

    /** At least one value is required: an empty IN matches nothing, silently. */
    fun inList(field: String, values: Collection<Any?>) = add(Filters.`in`(field, values))

    fun between(field: String, low: Any?, high: Any?) = add(Filters.between(field, low, high))

    /** Matches when any nested condition matches. */
    fun anyOf(block: FilterBuilder.() -> Unit) {
        val nested = FilterBuilder().apply(block)
        add(Filters.anyOf(nested.asJavaList()))
    }

    /** Matches when every nested condition matches. Only needed inside an [anyOf]. */
    fun allOf(block: FilterBuilder.() -> Unit) {
        val nested = FilterBuilder().apply(block)
        add(Filters.allOf(nested.asJavaList()))
    }

    @Suppress("UNCHECKED_CAST")
    internal fun asJavaList(): List<Map<String, Any>> = conditions as List<Map<String, Any>>
}

/**
 * Adds where conditions in a block.
 *
 * ```kotlin
 * .where { gte("Score", 100); eq("Season", 3) }
 * ```
 */
fun Query.where(block: FilterBuilder.() -> Unit): Query =
    where(FilterBuilder().apply(block).asJavaList())

/** Adds having conditions in a block. Applied after grouping. */
fun Query.having(block: FilterBuilder.() -> Unit): Query {
    FilterBuilder().apply(block).asJavaList().forEach { having(it) }
    return this
}

// ── suspend terminals ───────────────────────────────────────────────────────

/** Runs the query on [Dispatchers.IO] and returns one page. */
suspend fun Query.fetchAsync(): Responses.Page = withContext(Dispatchers.IO) { fetch() }

/** The first matching row, or null when nothing matched. An empty result is not an error. */
suspend fun Query.firstAsync(): Map<String, Any>? = withContext(Dispatchers.IO) { first() }

suspend fun Query.existsAsync(): Boolean = withContext(Dispatchers.IO) { exists() }

/** The number of matching rows, ignoring limit and offset. */
suspend fun Query.countAsync(): Long = withContext(Dispatchers.IO) { count() }

/**
 * Pages through every matching row.
 *
 * One dispatcher hop for the whole loop rather than one per page: the loop is sequential anyway, so
 * hopping per page would only add overhead.
 */
suspend fun Query.allAsync(pageSize: Int = 200, maxRows: Int? = null): List<Map<String, Any>> =
    withContext(Dispatchers.IO) { all(pageSize, maxRows) }

// ── suspend writes ──────────────────────────────────────────────────────────

suspend fun PraxData.insertAsync(table: String, values: Map<String, Any?>): Responses.MutationResult =
    withContext(Dispatchers.IO) { insert(table, values) }

suspend fun PraxData.insertManyAsync(
    table: String,
    rows: List<Map<String, Any?>>,
): Responses.MutationResult = withContext(Dispatchers.IO) { insertMany(table, rows) }

/**
 * Updates every row matching the conditions built in the block.
 *
 * The block is mandatory in spirit and enforced in fact: an empty one produces no conditions and the
 * Java SDK refuses an unscoped update, which is the behaviour we want to keep rather than route
 * around.
 */
suspend fun PraxData.updateAsync(
    table: String,
    values: Map<String, Any?>,
    where: FilterBuilder.() -> Unit,
): Responses.MutationResult = withContext(Dispatchers.IO) {
    update(table, values, FilterBuilder().apply(where).asJavaList())
}

suspend fun PraxData.updateByIdAsync(
    table: String,
    rowId: String,
    values: Map<String, Any?>,
): Responses.MutationResult = withContext(Dispatchers.IO) { updateById(table, rowId, values) }

/** Deletes every row matching the conditions built in the block. */
suspend fun PraxData.deleteAsync(
    table: String,
    where: FilterBuilder.() -> Unit,
): Responses.MutationResult = withContext(Dispatchers.IO) {
    delete(table, FilterBuilder().apply(where).asJavaList())
}

suspend fun PraxData.deleteByIdAsync(table: String, rowId: String): Responses.MutationResult =
    withContext(Dispatchers.IO) { deleteById(table, rowId) }

suspend fun PraxData.upsertAsync(
    table: String,
    values: Map<String, Any?>,
    rowId: String? = null,
): Responses.MutationResult = withContext(Dispatchers.IO) { upsert(table, values, rowId) }

// ── suspend auth ────────────────────────────────────────────────────────────

suspend fun PraxAuth.loginAsync(email: String, password: String): PraxAuth.Session =
    withContext(Dispatchers.IO) { login(email, password) }

suspend fun PraxAuth.registerAsync(
    email: String,
    password: String,
    extraFields: Map<String, Any?> = emptyMap(),
): PraxAuth.RegistrationResult = withContext(Dispatchers.IO) { register(email, password, extraFields) }

suspend fun PraxAuth.logoutAsync(): Unit = withContext(Dispatchers.IO) { logout() }

suspend fun PraxAuth.refreshAsync(): PraxAuth.Session = withContext(Dispatchers.IO) { refresh() }

suspend fun PraxAuth.forgotPasswordAsync(email: String): Unit =
    withContext(Dispatchers.IO) { forgotPassword(email) }

suspend fun PraxAuth.resetPasswordAsync(email: String, code: String, newPassword: String): Unit =
    withContext(Dispatchers.IO) { resetPassword(email, code, newPassword) }

suspend fun PraxAuth.configAsync(): Map<String, Any> = withContext(Dispatchers.IO) { config() }

// ── suspend endpoints ───────────────────────────────────────────────────────

/**
 * Calls an endpoint. POST only, and the response is the automation's own payload - both measured
 * behaviours rather than choices. See the Java SDK's `PraxEndpoints` for the detail.
 */
suspend fun PraxEndpoints.callAsync(
    endpointId: String,
    body: Any? = null,
    timeout: Duration = PraxEndpoints.DEFAULT_TIMEOUT,
): Map<String, Any> = withContext(Dispatchers.IO) { call(endpointId, body, timeout) }

// ── ergonomics ──────────────────────────────────────────────────────────────

/** `prax.data` instead of `prax.data()`. */
val Praxsuite.data: PraxData get() = data()

/** `prax.auth` instead of `prax.auth()`. */
val Praxsuite.auth: PraxAuth get() = auth()

/** `prax.endpoints` instead of `prax.endpoints()`. */
val Praxsuite.endpoints: PraxEndpoints get() = endpoints()

/** Iterating a page yields its rows. */
operator fun Responses.Page.iterator(): Iterator<Map<String, Any>> = rows().iterator()

/** `page[0]`. */
operator fun Responses.Page.get(index: Int): Map<String, Any> = rows()[index]

/**
 * Reads a column as a specific type, or null.
 *
 * A whole JSON number arrives as a `Long` and a fractional one as a `Double`, so ask for the type
 * you expect rather than casting blind.
 */
inline fun <reified T> Map<String, Any>.column(name: String): T? = this[name] as? T
