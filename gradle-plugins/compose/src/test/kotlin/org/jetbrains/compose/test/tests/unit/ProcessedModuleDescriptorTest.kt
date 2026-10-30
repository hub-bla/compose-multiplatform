/* Copyright 2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license. */
package org.jetbrains.compose.test.tests.unit

import java.lang.module.ModuleDescriptor
import java.util.EnumSet
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import org.jetbrains.compose.desktop.application.internal.moduleInfoSource

class ProcessedModuleDescriptorTest {
    @Test
    fun preservesReflectionAndDependencyContractsAfterShrinking() {
        val descriptor = ModuleDescriptor.newModule("sample.library")
            .requires(EnumSet.of(ModuleDescriptor.Requires.Modifier.STATIC, ModuleDescriptor.Requires.Modifier.TRANSITIVE), "sample.optional")
            .exports("sample.api")
            .exports("sample.removed")
            .opens("sample.metadata", setOf("kotlin.reflect"))
            .build()
        val source = moduleInfoSource(descriptor, setOf("sample.api.Api", "sample.metadata.Metadata"))
        assertContains(source, "requires static transitive sample.optional;")
        assertContains(source, "exports sample.api;")
        assertContains(source, "opens sample.metadata to kotlin.reflect;")
        assertFalse(source.contains("sample.removed"))
    }

    @Test
    fun retainsSurvivingServiceProvidersWithoutReferencingRemovedClasses() {
        val descriptor = ModuleDescriptor.newModule("sample.library")
            .uses("sample.api.Service")
            .provides("sample.api.Service", listOf("sample.internal.Kept", "sample.internal.Removed"))
            .build()
        val source = moduleInfoSource(descriptor, setOf("sample.api.Service", "sample.internal.Kept"))
        assertContains(source, "uses sample.api.Service;")
        assertContains(source, "provides sample.api.Service with sample.internal.Kept;")
        assertFalse(source.contains("sample.internal.Removed"))
    }
    @Test
    fun mapsObfuscatedPackagesAndProvidersIntoTheDescriptor() {
        val descriptor = ModuleDescriptor.newModule("sample.library")
            .exports("sample.api")
            .opens("sample.internal", setOf("sample.consumer"))
            .uses("sample.api.Service")
            .provides("sample.api.Service", listOf("sample.internal.Provider"))
            .build()
        val source = moduleInfoSource(descriptor, setOf("a.api.S", "a.impl.P"), mapOf(
            "sample.api.Service" to "a.api.S",
            "sample.internal.Provider" to "a.impl.P",
        ))
        assertContains(source, "exports a.api;")
        assertContains(source, "opens a.impl to sample.consumer;")
        assertContains(source, "uses a.api.S;")
        assertContains(source, "provides a.api.S with a.impl.P;")
        assertFalse(source.contains("exports sample.api;"))
    }

}
