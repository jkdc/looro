package com.jkdc.app.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

/**
 * [SqlDatabase] over Android's SQLiteDatabase, the production driver. Not
 * exercised by JVM unit tests (android.jar is stubbed there); [JvmSqlDatabase]
 * carries the same contract over sqlite-jdbc instead.
 */
class AndroidSqlDatabase(private val db: SQLiteDatabase) : SqlDatabase {

    private var inTransaction = false

    override fun exec(statement: String) = db.execSQL(statement)

    override fun insert(table: String, row: Map<String, Any?>, strategy: ConflictStrategy): Long {
        val values = ContentValues(row.size)
        for ((column, value) in row) when (value) {
            null -> values.putNull(column)
            is Boolean -> values.put(column, if (value) 1 else 0)
            is Int -> values.put(column, value)
            is Long -> values.put(column, value)
            is Double -> values.put(column, value)
            is String -> values.put(column, value)
            is ByteArray -> values.put(column, value)
            else -> error("Unsupported SQLite value for $column: ${value::class.java.name}")
        }
        return db.insertWithOnConflict(table, null, values, strategy.android)
    }

    // rawQuery binds only strings; SQLite's column affinity makes the text
    // form of a number compare equal to the number, which is all the store
    // needs (ids and key columns are TEXT anyway).
    override fun query(sql: String, args: List<Any?>): List<Row> {
        requireNonNullArgs(args)
        return db.rawQuery(sql, args.map(Any?::toString).toTypedArray()).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.row())
            }
        }
    }

    // execSQL runs any DML but reports no affected row count, so Android
    // always returns -1 here (JVM reports the count; see SqlDatabase.update).
    override fun update(sql: String, args: List<Any?>): Long {
        requireNonNullArgs(args)
        db.execSQL(sql, args.toTypedArray())
        return -1L
    }

    override fun <T> transaction(block: (SqlDatabase) -> T): T {
        check(!inTransaction) { "nested transactions are not supported" }
        inTransaction = true
        db.beginTransaction()
        try {
            val result = block(this)
            db.setTransactionSuccessful()
            return result
        } finally {
            inTransaction = false
            db.endTransaction() // rolls back unless setTransactionSuccessful ran
        }
    }

    private fun Cursor.row(): Row {
        val names = columnNames.toList()
        val values = (0 until columnCount).map { index ->
            when (getType(index)) {
                Cursor.FIELD_TYPE_NULL -> null
                Cursor.FIELD_TYPE_INTEGER -> getLong(index)
                Cursor.FIELD_TYPE_FLOAT -> getDouble(index)
                Cursor.FIELD_TYPE_BLOB -> getBlob(index)
                else -> getString(index)
            }
        }
        return Row(names, values)
    }

    private val ConflictStrategy.android: Int
        get() = when (this) {
            ConflictStrategy.ROLLBACK -> SQLiteDatabase.CONFLICT_ROLLBACK
            ConflictStrategy.ABORT -> SQLiteDatabase.CONFLICT_ABORT
            ConflictStrategy.FAIL -> SQLiteDatabase.CONFLICT_FAIL
            ConflictStrategy.IGNORE -> SQLiteDatabase.CONFLICT_IGNORE
            ConflictStrategy.REPLACE -> SQLiteDatabase.CONFLICT_REPLACE
        }
}
