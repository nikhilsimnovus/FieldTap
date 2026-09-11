package com.fieldtap.core.session

import java.io.File

/** Rebuilds the session in `args[0]` (started at `args[1]`) and prints what it found; SessionRebuildTest runs it with a small heap. */
object RebuildProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val derived = SessionRebuild.derive(File(args[0]), args[1].toLong())
        println("fresh=${derived.collection.freshSamples} plmn311480=${derived.plmns["311480"]}")
    }
}
