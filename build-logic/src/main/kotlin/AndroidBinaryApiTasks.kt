import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * AGP 9 built-in Kotlin is not discovered by kotlinx-binary-compatibility-validator 0.18.1.
 * This task provides equivalent binary-signature coverage for Android-only library modules.
 */
@CacheableTask
abstract class GenerateAndroidBinaryApiTask @Inject constructor() : DefaultTask() {
    @get:Classpath
    abstract val classDirectories: ConfigurableFileCollection

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    /** Optional exact bytecode class names that form an Android module's public surface. */
    @get:Input
    abstract val includedClassNames: ListProperty<String>

    init {
        includedClassNames.convention(emptyList())
    }

    @TaskAction
    fun generate() {
        val roots = classDirectories.files.filter(File::exists).sortedBy(File::getAbsolutePath)
        val configuredIncludes = includedClassNames.get().toSet()
        val classNames = roots
            .flatMap { root ->
                root.walkTopDown()
                    .filter { it.isFile && it.extension == "class" }
                    .map { classFile ->
                        classFile.relativeTo(root).invariantSeparatorsPath
                            .removeSuffix(".class")
                            .replace('/', '.')
                    }
                    .toList()
            }
            .distinct()
            .filter { configuredIncludes.isEmpty() || it in configuredIncludes }
            .sorted()

        val destination = outputFile.get().asFile
        destination.parentFile.mkdirs()

        if (classNames.isEmpty()) {
            destination.writeText(HEADER)
            return
        }

        val javaHome = File(System.getProperty("java.home"))
        val javap = javaHome.resolve("bin/javap")
        val classpath = roots.joinToString(File.pathSeparator, transform = File::getAbsolutePath)
        val command = buildList {
            add(javap.absolutePath)
            add("-public")
            add("-s")
            add("-constants")
            add("-classpath")
            add(classpath)
            addAll(classNames)
        }
        val output = ByteArrayOutputStream()
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        process.inputStream.use { it.copyTo(output) }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw GradleException("javap failed with exit code $exitCode:\n${output}")
        }

        destination.writeText(HEADER + output.toString(Charsets.UTF_8))
    }

    private companion object {
        const val HEADER = "# Android binary API (javap -public -s -constants)\n\n"
    }
}

abstract class UpdateAndroidBinaryApiTask @Inject constructor() : DefaultTask() {
    @get:InputFile
    abstract val generatedApiFile: RegularFileProperty

    @get:OutputFile
    abstract val apiFile: RegularFileProperty

    @TaskAction
    fun update() {
        val destination = apiFile.get().asFile
        destination.parentFile.mkdirs()
        generatedApiFile.get().asFile.copyTo(destination, overwrite = true)
    }
}

abstract class CheckAndroidBinaryApiTask @Inject constructor() : DefaultTask() {
    @get:InputFile
    abstract val generatedApiFile: RegularFileProperty

    @get:InputFile
    abstract val apiFile: RegularFileProperty

    @TaskAction
    fun check() {
        val expected = apiFile.get().asFile
        val actual = generatedApiFile.get().asFile
        if (!expected.exists()) {
            throw GradleException("Missing ${expected.path}. Run ./gradlew apiDump.")
        }
        if (expected.readBytes().contentEquals(actual.readBytes())) return

        throw GradleException(
            "Public Android API changed for ${project.path}. " +
                "Review it and run ./gradlew apiDump to accept the new API.",
        )
    }
}
