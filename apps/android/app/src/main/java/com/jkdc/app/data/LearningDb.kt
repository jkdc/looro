package com.jkdc.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Opens the learner's learning database (schema v1, shared/db/schema.sql via
 * [SchemaData]). Android builds it through SQLiteOpenHelper; JVM tests build
 * the same statements through LearningDb.openJvm (test source set,
 * sqlite-jdbc). Foreign keys are enforced on every connection, as the schema
 * header requires, and onCreate leaves PRAGMA user_version at 1 both because
 * the schema ends with it and because the helper records [VERSION].
 */
object LearningDb {
    /** Bump only together with a migration; shared/db/schema.sql pins the shape. */
    const val VERSION = 1
    const val NAME = "learning.db"

    /** Opens the learning database, creating the schema on first use. */
    fun open(context: Context, name: String = NAME): SqlDatabase =
        AndroidSqlDatabase(Helper(context.applicationContext, name).writableDatabase)

    private class Helper(context: Context, name: String) :
        SQLiteOpenHelper(context, name, null, VERSION) {

        override fun onConfigure(db: SQLiteDatabase) = db.setForeignKeyConstraintsEnabled(true)

        override fun onCreate(db: SQLiteDatabase) {
            for (statement in SchemaData.LEARNING_SCHEMA_STATEMENTS) db.execSQL(statement)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
            error("Learning schema has no upgrade path yet (found $oldVersion, want $newVersion)")
    }
}
