package com.jkdc.app.data

/** One SQLite row: column names in order with their values, plus a name lookup. */
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
 */
interface SqlDatabase {
    /** Runs one statement (DDL, PRAGMA or update) that returns no rows. */
    fun exec(statement: String)

    /** Inserts one row into [table], resolving conflicts with [strategy];
     *  returns the new row id, or -1 when the strategy swallowed the insert. */
    fun insert(table: String, row: Map<String, Any?>, strategy: ConflictStrategy = ConflictStrategy.ABORT): Long

    /** Runs a parameterized query and returns its rows (empty when none match). */
    fun query(sql: String, args: List<Any?> = emptyList()): List<Row>
}
