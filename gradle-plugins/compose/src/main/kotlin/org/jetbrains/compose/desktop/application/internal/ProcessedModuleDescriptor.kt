/* Copyright 2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license. */
package org.jetbrains.compose.desktop.application.internal

import java.lang.module.ModuleDescriptor

/** Retains module contracts while dropping packages and providers removed by ProGuard. */
internal fun moduleInfoSource(original: ModuleDescriptor, classes: Set<String>, renamedClasses: Map<String, String> = emptyMap()): String {
    val packages = classes.map { it.substringBeforeLast('.', "") }.toSet()
    fun mapped(name: String) = renamedClasses[name] ?: name
    fun sourceName(name: String) = mapped(name).replace('$', '.')
    fun mappedPackages(pkg: String): Set<String> = (
        renamedClasses.filterKeys { it.substringBeforeLast('.') == pkg }.values
            .filter { it in classes }.map { it.substringBeforeLast('.') } + listOf(pkg).filter { it in packages }
    ).toSortedSet()
    fun available(name: String) = mapped(name) in classes || name.substringBeforeLast('.') !in original.packages()
    return buildString {
        appendLine("${if (original.isOpen) "open " else ""}module ${original.name()} {")
        for (requires in original.requires().sortedBy { it.name() }) {
            append("    requires ")
            if (ModuleDescriptor.Requires.Modifier.STATIC in requires.modifiers()) append("static ")
            if (ModuleDescriptor.Requires.Modifier.TRANSITIVE in requires.modifiers()) append("transitive ")
            appendLine("${requires.name()};")
        }
        for (export in original.exports().sortedBy { it.source() }) {
            for (pkg in mappedPackages(export.source())) {
                append("    exports $pkg")
                if (export.isQualified) append(" to ${export.targets().sorted().joinToString()}")
                appendLine(";")
            }
        }
        for (open in original.opens().sortedBy { it.source() }) {
            for (pkg in mappedPackages(open.source())) {
                append("    opens $pkg")
                if (open.isQualified) append(" to ${open.targets().sorted().joinToString()}")
                appendLine(";")
            }
        }
        for (use in original.uses().sorted().filter(::available)) appendLine("    uses ${sourceName(use)};")
        for (provide in original.provides().sortedBy { it.service() }) {
            val providers = provide.providers().filter { mapped(it) in classes }
            if (providers.isNotEmpty() && available(provide.service())) {
                appendLine("    provides ${sourceName(provide.service())} with ${providers.joinToString { sourceName(it) }};")
            }
        }
        appendLine("}")
    }
}
