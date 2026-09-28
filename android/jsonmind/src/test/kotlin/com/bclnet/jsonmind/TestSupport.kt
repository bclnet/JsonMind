package com.bclnet.jsonmind

import com.bclnet.jsonui.parseJson
import java.io.File

object Examples {
    /** The repository's examples directory, found from the module directory Gradle runs tests in. */
    val directory: File = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "examples") }.first { File(it, "minds/snoopy.json").exists() }

    fun mind(name: String): Mind = Mind.of(parseJson(File(directory, "minds/$name.json").readText()))
}
