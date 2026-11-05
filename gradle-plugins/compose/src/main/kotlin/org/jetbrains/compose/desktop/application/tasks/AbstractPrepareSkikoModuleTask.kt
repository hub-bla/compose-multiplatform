/* Copyright 2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license. */
package org.jetbrains.compose.desktop.application.tasks

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.work.DisableCachingByDefault
import org.jetbrains.compose.desktop.application.internal.mergeSkikoJars
import org.jetbrains.compose.desktop.application.internal.moduleInfoSource
import org.jetbrains.compose.desktop.application.internal.files.mangledName
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import org.jetbrains.compose.desktop.application.internal.shouldKeepSkikoEntry
import org.jetbrains.compose.desktop.application.internal.isSkikoNativeEntry
import org.jetbrains.compose.internal.utils.currentTarget
import org.jetbrains.compose.desktop.tasks.AbstractComposeDesktopTask
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.module.ModuleFinder
import java.lang.module.ModuleDescriptor
import java.util.jar.JarFile

/** Builds an explicit Skiko module and stages its explicit, external module dependencies. */
@DisableCachingByDefault(because = "Module analysis and compilation depend on the selected JDK")
abstract class AbstractPrepareSkikoModuleTask : AbstractComposeDesktopTask() {
    @get:Classpath abstract val skikoJars: ConfigurableFileCollection
    @get:InputFile @get:Optional @get:PathSensitive(PathSensitivity.NONE)
    val proguardMapping: RegularFileProperty = objects.fileProperty()
    @get:Classpath val processedJars: ConfigurableFileCollection = objects.fileCollection()
    @get:Classpath abstract val runtimeJars: ConfigurableFileCollection
    @get:Input abstract val javaHome: Property<String>
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    val jdkRelease: File get() = File(javaHome.get(), "release")
    @get:OutputDirectory abstract val destinationDir: DirectoryProperty
    @get:OutputDirectory abstract val nativeLibrariesDir: DirectoryProperty
    @get:Input val target: String get() = currentTarget.id

    @TaskAction fun prepare() {
        if (skikoJars.isEmpty) {
            // Plain JVM applications need neither module options nor a different JDK.
            for (directory in listOf(destinationDir, nativeLibrariesDir)) {
                fileOperations.delete { it.delete(directory) }
                directory.get().asFile.mkdirs()
            }
            return
        }
        val release = java.util.Properties().apply { jdkRelease.inputStream().use { load(it) } }
        val majorVersion = release.getProperty("JAVA_VERSION").trim('"').substringBefore('.').toInt()
        check(majorVersion >= 17) { "Modular Skiko requires JDK 17 or newer" }
        val renamedClasses = proguardMapping.orNull?.asFile?.useLines { lines ->
            lines.filter { !it.startsWith(" ") && it.contains(" -> ") && it.endsWith(":") }
                .associate { it.substringBefore(" -> ") to it.substringAfter(" -> ").removeSuffix(":") }
        }.orEmpty()
        val output = destinationDir.get().asFile
        fileOperations.delete { it.delete(output) }
        output.mkdirs()
        val work = temporaryDir.apply { deleteRecursively(); mkdirs() }
        val merged = work.resolve("org.jetbrains.skiko.jar")
        fun processed(file: File): File = if (processedJars.isEmpty) file else
            processedJars.files.singleOrNull { it.name == file.mangledName() }
                ?: error("Missing ProGuard output for $file")
        mergeSkikoJars(skikoJars.files.map(::processed), merged, ::shouldKeepSkikoEntry)
        val natives = nativeLibrariesDir.get().asFile
        fileOperations.delete { it.delete(natives) }
        natives.mkdirs()
        JarFile(merged).use { jar ->
            for (entry in jar.entries().asSequence().filter { isSkikoNativeEntry(it.name) }) {
                jar.getInputStream(entry).use { input ->
                    natives.resolve(entry.name.substringAfterLast('/')).outputStream().use { input.copyTo(it) }
                }
            }
        }
        val candidates = (runtimeJars.files - skikoJars.files).filter { file ->
            file.extension == "jar" && JarFile(file).use { jar -> jar.entries().asSequence().any {
                it.name == "module-info.class" || it.name.matches(Regex("META-INF/versions/\\d+/module-info.class"))
            } }
        }
        val modules = candidates.groupBy { ModuleFinder.of(it.toPath()).findAll().single().descriptor().name() }
            .mapValues { (name, files) ->
                check(files.size == 1) { "More than one resolved JAR declares module '$name': $files" }
                files.single()
            }
        // Discover the original module dependency closure before staging processed dependencies.
        val dependencyInput = if (processedJars.isEmpty) merged else
            work.resolve("original/org.jetbrains.skiko.jar").apply {
                parentFile.mkdirs()
                mergeSkikoJars(skikoJars.files, this, ::shouldKeepSkikoEntry)
            }
        val generated = work.resolve("descriptors")
        tool("jdeps", listOf("--multi-release", "base", "--module-path", candidates.joinToString(File.pathSeparator),
            "--generate-module-info", generated.path, dependencyInput.path))
        val source = generated.walkTopDown().singleOrNull { it.name == "module-info.java" }
            ?: error("jdeps did not generate a single Skiko module descriptor")
        val classes = work.resolve("classes").apply { mkdirs() }
        // javac checks this output directory for previously compiled classes belonging to the module.
        fileOperations.copy {
            it.from(archiveOperations.zipTree(merged))
            it.include("**/*.class")
            it.into(classes)
        }
        // Gradle may run on an older JDK than the application's toolchain.
        // The descriptor itself only needs JPMS's original class-file format.
        tool("javac", listOf("--release", "9", "--module-path", candidates.joinToString(File.pathSeparator),
            "-d", classes.path, source.path))
        val modularJar = output.resolve(merged.name)
        merged.copyTo(modularJar)
        tool("jar", listOf("--update", "--file", modularJar.path, "-C", classes.path, "module-info.class"))
        val systemModules = tool("java", listOf("--list-modules")).lineSequence()
            .map { it.substringBefore('@').trim() }.filter { it.isNotEmpty() }.toSet()
        val visited = mutableSetOf<String>()
        fun stageDependencies(descriptor: ModuleDescriptor) {
            for (requires in descriptor.requires()) {
                val name = requires.name()
                if (name in systemModules || !visited.add(name)) continue
                val dependency = modules[name]
                if (dependency == null && ModuleDescriptor.Requires.Modifier.STATIC in requires.modifiers()) continue
                check(dependency != null) { "Skiko module requires '$name', but no explicit module was resolved" }
                val originalDescriptor = ModuleFinder.of(dependency.toPath()).findAll().single().descriptor()
                stageDependencies(originalDescriptor)
                val input = processed(dependency)
                val staged = output.resolve(input.name)
                if (processedJars.isEmpty) dependency.copyTo(staged) else {
                    val moduleWork = work.resolve(name).apply { mkdirs() }
                    val moduleClasses = moduleWork.resolve("classes").apply { mkdirs() }
                    val classNames = mutableSetOf<String>()
                    ZipOutputStream(staged.outputStream()).use { zip ->
                        JarFile(input).use { jar ->
                            for (entry in jar.entries().asSequence().filterNot {
                                it.isDirectory || it.name == "module-info.class" ||
                                    it.name.matches(Regex("META-INF/versions/\\d+/module-info.class"))
                            }) {
                                val bytes = jar.getInputStream(entry).use { it.readBytes() }
                                zip.putNextEntry(ZipEntry(entry.name).apply { time = 0 })
                                zip.write(bytes); zip.closeEntry()
                                if (entry.name.endsWith(".class") && !entry.name.startsWith("META-INF/")) {
                                    classNames.add(entry.name.removeSuffix(".class").replace('/', '.'))
                                    moduleClasses.resolve(entry.name).apply { parentFile.mkdirs(); writeBytes(bytes) }
                                }
                            }
                        }
                    }
                    val info = moduleWork.resolve("module-info.java")
                    info.writeText(moduleInfoSource(originalDescriptor, classNames, renamedClasses))
                    val compilePath = modules.values.map { candidate ->
                        output.resolve(processed(candidate).name).takeIf { it.isFile } ?: candidate
                    }
                    tool("javac", listOf("--release", "9", "--module-path", compilePath.joinToString(File.pathSeparator),
                        "-d", moduleClasses.path, info.path))
                    tool("jar", listOf("--update", "--file", staged.path, "-C", moduleClasses.path, "module-info.class"))
                }
            }
        }
        stageDependencies(ModuleFinder.of(modularJar.toPath()).findAll().single().descriptor())
        if (!processedJars.isEmpty) {
            // Obfuscation changes references in Skiko too: analyze against the processed modules.
            val finalDescriptors = work.resolve("processed-descriptors")
            val modulePath = output.listFiles()!!.filter { it != modularJar }.joinToString(File.pathSeparator)
            tool("jdeps", listOf("--multi-release", "base", "--module-path", modulePath,
                "--generate-module-info", finalDescriptors.path, merged.path))
            val finalSource = finalDescriptors.walkTopDown().single { it.name == "module-info.java" }
            tool("javac", listOf("--release", "9", "--module-path", modulePath,
                "-d", classes.path, finalSource.path))
            tool("jar", listOf("--update", "--file", modularJar.path, "-C", classes.path, "module-info.class"))
        }
    }

    private fun tool(name: String, args: List<String>): String {
        val executable = File(javaHome.get(), "bin/$name" + if (System.getProperty("os.name").startsWith("Windows")) ".exe" else "")
        val output = ByteArrayOutputStream()
        val result = execOperations.exec {
            it.executable = executable.path
            it.args(args)
            it.standardOutput = output
            it.errorOutput = output
            it.isIgnoreExitValue = true
        }
        check(result.exitValue == 0) { "$name failed while preparing the Skiko module:\n$output" }
        return output.toString(Charsets.UTF_8.name())
    }
}
