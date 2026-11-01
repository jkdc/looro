package com.jkdc.app.data

/**
 * One SQLite row: column names in order with their values, plus a name lookup.
 * Values arrive typed by the backend (Android cursor field types / JDBC
 * result-set column metadata), so writes must stay schema-conformant for the
 * Kotlin types on read to stay predictable.
 */
class Row(val names: List<String>, val values: List<Any?>) {
    private val byName = names.zip(values).toMap()

    /** The value of the column with this name (null for SQL NULL or an unknown name). */
    operator fun get(name: String): Any? = byName[name]
}

/** SQLite conflict strategies; the name is the SQL keyword of INSERT OR &lt;name&gt;. */
enum class ConflictStrategy { ROLLBACK, ABORT, FAIL, IGNORE, REPLACE }

/**
 * The SQLite surface the learning store needs, thin enough to have one
 * implementation per platform: AndroidX SQLiteDatabase in the app
 * ([AndroidSqlDatabase]) and sqlite-jdbc in JVM tests ([JvmSqlDatabase], test
 * source set). DAOs written against this interface run on both, so the
 * learning migration is written once. Values are the SQLite types Kotlin can
 * carry: null, Long/Int, Double, Boolean (stored as 0/1), String, ByteArray.
 * Bind arguments of [query] and [update] must never be null — both backends
 * throw — because SQL NULL inside a comparison (`!= ?`, `NOT IN`) behaves
 * differently per backend; null checks are spelled `IS NULL`.
 */
interface SqlDatabase {
    /** Runs one statement (DDL, PRAGMA or update) that returns no rows. */
    fun exec(statement: String)

    /** Inserts one row into [table], resolving conflicts with [strategy];
     *  returns the new row id, or -1 when the strategy swallowed the insert. */
    fun insert(table: String, row: Map<String, Any?>, strategy: ConflictStrategy = ConflictStrategy.ABORT): Long

    /** Runs a parameterized query and returns its rows (empty when none match). */
    fun query(sql: String, args: List<Any?> = emptyList()): List<Row>

    /**
     * Runs a parameterized DML statement (UPDATE/DELETE and friends).
     * Returns the affected row count on the JVM backend; Android's execSQL
     * cannot report one, so [AndroidSqlDatabase] returns -1 — the count is a
     * JVM-test convenience, never cross-platform state.
     */
    fun update(sql: String, args: List<Any?> = emptyList()): Long

    /**
     * Runs [block] inside one transaction: BEGIN IMMEDIATE … COMMIT, or
     * ROLLBACK when [block] throws (the failure is rethrown either way).
     * Not re-entrant: a nested call throws. Keep transactions short and
     * single-threaded, like SQLiteDatabase itself.
     */
    fun <T> transaction(block: (SqlDatabase) -> T): T

    /**
     * Closes the underlying connection. One call site only: the database
     * backup/restore flow ([BackupManager] via the store's owner) closes the
     * live database BEFORE an imported backup file replaces it, then reopens
     * a fresh store — writing to a replaced file through the old connection
     * would land on the unlinked inode and vanish. Using a closed database
     * throws (SQLite "already closed" from both backends).
     */
    fun close()
}

/** Shared guard: a null bind argument would bind the literal "null" on Android
 *  but SQL NULL on JDBC, so `!= ?` / `NOT IN` results would diverge; both
 *  backends fail loudly instead and null checks are spelled `IS NULL`. */
internal fun requireNonNullArgs(args: List<Any?>) {
    val firstNull = args.indexOfFirst { it == null }
    if (firstNull >= 0) {
        throw IllegalArgumentException(
            "null bind argument at position ${firstNull + 1}: null checks must be spelled IS NULL")
    }
}
