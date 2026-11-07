package com.mbols2510.mangamanager.manifest

import java.io.File
import java.io.InputStream
import kotlin.system.exitProcess

/** TDD stub: accepts everything. Replaced by the real checker in the next commit. */
object MergedManifestChecker {

    fun check(input: InputStream, applicationId: String): List<String> = emptyList()

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2) { "usage: MergedManifestChecker <merged AndroidManifest.xml> <applicationId>" }
        val problems = File(args[0]).inputStream().use { check(it, args[1]) }
        if (problems.isNotEmpty()) {
            System.err.println("Merged release manifest check FAILED (${args[0]}):")
            problems.forEach { System.err.println("  - $it") }
            exitProcess(1)
        }
        println("Merged release manifest check OK: ${args[0]}")
    }
}
