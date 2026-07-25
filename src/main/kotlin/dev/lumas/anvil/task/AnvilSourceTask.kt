package dev.lumas.anvil.task

import dev.lumas.anvil.internal.PatchLayout
import org.gradle.api.DefaultTask
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal

abstract class AnvilSourceTask : DefaultTask() {

    @get:Internal
    abstract val generatedDir: DirectoryProperty

    @get:Internal
    abstract val patchesDir: DirectoryProperty

    @get:Internal
    abstract val packageMappings: MapProperty<String, Directory>

    @get:Internal
    abstract val resourceMappings: MapProperty<String, Directory>

    @get:Internal
    abstract val javaSourceRoot: Property<String>

    @get:Internal
    abstract val resourceRoot: Property<String>

    internal fun patchLayout(): PatchLayout = PatchLayout(
        generatedDir = generatedDir.get().asFile,
        patchesDir = patchesDir.get().asFile,
        packageMappings = packageMappings.get().mapValues { it.value.asFile },
        resourceMappings = resourceMappings.get().mapValues { it.value.asFile },
        javaRoot = javaSourceRoot.get(),
        resourceRoot = resourceRoot.get(),
    )
}
