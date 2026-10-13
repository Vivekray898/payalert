package com.vivekray898.payalert.parsers

import java.io.File

/**
 * Loads the *shipped* rules file so unit tests exercise the exact JSON that ends up in the
 * APK. Gradle runs unit tests with the module directory as the working directory, so the
 * asset is reachable at this relative path.
 */
object TestConfig {

    private const val RELATIVE_PATH = "src/main/assets/parser_config.json"

    val shipped: ParserConfig by lazy {
        val file = File(RELATIVE_PATH)
        check(file.exists()) {
            "Shipped parser config not found at ${file.absolutePath} (cwd=${File(".").absolutePath})"
        }
        ParserConfigLoader.load(file.readText())
    }
}
