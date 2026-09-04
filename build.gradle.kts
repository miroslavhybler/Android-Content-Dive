import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

plugins {
    base
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.binary.compatibility.validator)
    alias(libs.plugins.dokka)
}

val publishedGroupProvider = providers.gradleProperty("GROUP")
val sharedVersion = providers.gradleProperty("VERSION_NAME")

allprojects {
    group = publishedGroupProvider.get()
    version = sharedVersion.get()
}

apiValidation {
    ignoredProjects += "app-example"
}

val consumerPublishedModules = listOf(
    "contentdive-api",
    "contentdive-fuzzy",
    "contentdive-backend-memory",
    "contentdive-backend-appsearch",
    "contentdive-compose",
    "contentdive-serialization-kotlinx",
    "contentdive-navigation3",
    "contentdive-ksp-annotations",
    "contentdive-ksp-processor",
)
val transitivePublishedModules = listOf(
    "contentdive-spi",
    "contentdive-engine",
)
val publishedModules = consumerPublishedModules + transitivePublishedModules
val documentedModules = publishedModules

val publishPublicModulesToMavenLocal by tasks.registering {
    group = "publishing"
    description =
        "Publishes all consumer-facing ContentDive modules and their required transitive artifacts to Maven Local."
    dependsOn(publishedModules.map { module -> ":$module:publishToMavenLocal" })
}

dependencies {
    documentedModules.forEach { module ->
        dokka(project(":$module"))
    }
}

dokka {
    moduleName.set("ContentDive")
    moduleVersion.set(sharedVersion)
    dokkaPublications.html {
        outputDirectory.set(layout.buildDirectory.dir("dokka/html"))
        failOnWarning.set(true)
        suppressObviousFunctions.set(true)
    }
}

val composeFreeModules = listOf(
    "contentdive-api",
    "contentdive-fuzzy",
    "contentdive-spi",
    "contentdive-engine",
    "contentdive-backend-memory",
    "contentdive-backend-appsearch",
)
val composeFreeSources = files(
    composeFreeModules.map { module ->
        fileTree(module) {
            include("src/main/**/*.kt")
            include("src/main/**/*.java")
            include("api/*.api")
        }
    },
)

abstract class CheckForbiddenTextTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFiles: ConfigurableFileCollection

    @get:Input
    abstract val forbiddenText: Property<String>

    @TaskAction
    fun verify() {
        val needle = forbiddenText.get()
        val leaks = sourceFiles.files
            .filter { file -> file.isFile && needle in file.readText() }
            .map { file -> file.invariantSeparatorsPath }
            .sorted()

        check(leaks.isEmpty()) {
            "Forbidden public-boundary text '$needle' found in: ${leaks.joinToString()}"
        }
    }
}

abstract class CheckReadmeTask : DefaultTask() {
    @get:InputFile
    abstract val readmeFile: RegularFileProperty

    @get:InputFile
    abstract val settingsFile: RegularFileProperty

    @get:Input
    abstract val repositoryRoot: Property<String>

    @get:Input
    abstract val publishedModules: ListProperty<String>

    @get:Input
    abstract val documentedDependencies: ListProperty<String>

    @get:Input
    abstract val publicationGroup: Property<String>

    @get:Input
    abstract val publicationVersion: Property<String>

    @TaskAction
    fun verify() {
        val readme = readmeFile.get().asFile.readText()
        val settings = settingsFile.get().asFile.readText()
        val root = java.io.File(repositoryRoot.get())
        val requiredSections = listOf(
            "## What ContentDive is",
            "## Supported inputs",
            "## Core flow",
            "## Module overview",
            "## Installation",
            "## Minimal working example",
            "## Manual versus KSP projectors",
            "## Backend choice",
            "## Destinations and anchors",
            "## Current limitations",
        )
        val sectionOffsets = requiredSections.map(readme::indexOf)
        check(sectionOffsets.none { it < 0 } && sectionOffsets == sectionOffsets.sorted()) {
            "README onboarding sections are missing or out of order"
        }

        publishedModules.get().forEach { module ->
            check("[$module]($module/)" in readme || "[`$module`]($module/)" in readme) {
                "README module overview is missing a working link for $module"
            }
            check("\":$module\"" in settings) { "$module is documented but absent from settings" }
            check(root.resolve("$module/build.gradle.kts").isFile) {
                "$module is documented but has no build file"
            }
        }

        val coordinatePattern = Regex(
            "${Regex.escape(publicationGroup.get())}:([A-Za-z0-9._-]+):" +
                Regex.escape(publicationVersion.get()),
        )
        val documentedArtifacts = coordinatePattern.findAll(readme)
            .map { match -> match.groupValues[1] }
            .toSet()
        check(documentedArtifacts == documentedDependencies.get().toSet()) {
            "README dependency artifacts differ from the expected published consumer artifacts: " +
                documentedArtifacts.sorted()
        }

        val localLinkPattern = Regex("""\[[^]]*]\(([^)]+)\)""")
        val missingLinks = localLinkPattern.findAll(readme)
            .map { match -> match.groupValues[1].substringBefore('#') }
            .filter { target ->
                target.isNotBlank() && !target.startsWith("#") && "://" !in target
            }
            .filterNot { target -> root.resolve(target).exists() }
            .toList()
        check(missingLinks.isEmpty()) {
            "README contains missing local links: ${missingLinks.joinToString()}"
        }

        check("HTML, Markdown, and every other input format are\nunsupported" in readme) {
            "README must explicitly state that HTML and Markdown inputs are unsupported"
        }
        listOf(
            "createMemoryContentDive()",
            "createAppSearchContentDive(",
            "contentDive.replace(",
            "contentDive.search(",
            "match.destination",
            "contentDive.close()",
        ).forEach { expected ->
            check(expected in readme) { "README working flow is missing '$expected'" }
        }
    }
}

val checkComposeTypeIsolation by tasks.registering(CheckForbiddenTextTask::class) {
    group = "verification"
    description = "Checks that Compose types remain isolated to adapter and application modules."
    sourceFiles.from(composeFreeSources)
    forbiddenText.set("androidx.compose")
}

val fuzzyModuleSurface = files(
    fileTree("contentdive-fuzzy") {
        include("src/main/**/*.kt")
        include("src/main/**/*.java")
        include("api/*.api")
        include("build.gradle.kts")
    },
)

val forbiddenFuzzyDependencies = mapOf(
    "ContentDiveApi" to "com.contentdive.api",
    "Android" to "android.",
    "AndroidX" to "androidx.",
    "Coroutines" to "kotlinx.coroutines",
    "Serialization" to "kotlinx.serialization",
    "Storage" to "androidx.appsearch",
)

val fuzzyIsolationChecks = forbiddenFuzzyDependencies.map { (suffix, needle) ->
    tasks.register<CheckForbiddenTextTask>("checkFuzzyWithout$suffix") {
        group = "verification"
        description = "Checks that the standalone fuzzy matcher remains dependency-free."
        sourceFiles.from(fuzzyModuleSurface)
        forbiddenText.set(needle)
    }
}

val checkReadme by tasks.registering(CheckReadmeTask::class) {
    group = "verification"
    description = "Checks README structure, module/artifact names, examples, and local links."
    readmeFile.set(layout.projectDirectory.file("README.md"))
    settingsFile.set(layout.projectDirectory.file("settings.gradle.kts"))
    repositoryRoot.set(layout.projectDirectory.asFile.absolutePath)
    publishedModules.set(documentedModules)
    publicationGroup.set(publishedGroupProvider)
    publicationVersion.set(sharedVersion)
    documentedDependencies.set(
        listOf(
            "contentdive-backend-memory",
            "contentdive-backend-appsearch",
            "contentdive-fuzzy",
            "contentdive-compose",
            "contentdive-navigation3",
            "contentdive-serialization-kotlinx",
            "contentdive-ksp-annotations",
            "contentdive-ksp-processor",
        ),
    )
}

val standardConsumerSources = files(
    fileTree("app-example/src") {
        include("**/*.kt")
        include("**/*.java")
    },
    fileTree("contentdive-compose/src/test") {
        include("**/*.kt")
        include("**/*.java")
    },
)

val forbiddenStandardConsumerReferences = mapOf(
    "SpiPackage" to "com.contentdive.spi",
    "ExperimentalSpi" to "ExperimentalContentDiveSpi",
    "EngineFactory" to "ContentDiveEngine",
    "RawMemoryFactory" to "createMemorySearchBackend",
    "RawAppSearchFactory" to "createAppSearchBackend",
)

val standardConsumerBoundaryChecks = forbiddenStandardConsumerReferences.map { (suffix, needle) ->
    tasks.register<CheckForbiddenTextTask>("checkStandardConsumer$suffix") {
        group = "verification"
        description = "Checks that standard consumers do not use internal ContentDive setup APIs."
        sourceFiles.from(standardConsumerSources)
        forbiddenText.set(needle)
    }
}

val coreApiSurface = files(
    fileTree("contentdive-api") {
        include("src/main/**/*.kt")
        include("src/main/**/*.java")
        include("api/*.api")
    },
)

val forbiddenCoreApiTypes = mapOf(
    "Android" to "android.",
    "AndroidX" to "androidx.",
    "Serialization" to "kotlinx.serialization",
    "Spi" to "com.contentdive.spi",
)

val coreApiBoundaryChecks = forbiddenCoreApiTypes.map { (suffix, needle) ->
    tasks.register<CheckForbiddenTextTask>("checkCoreApiWithout$suffix") {
        group = "verification"
        description = "Checks that the platform-neutral core API remains isolated."
        sourceFiles.from(coreApiSurface)
        forbiddenText.set(needle)
    }
}

val regularPublicApiSurface = files(
    fileTree(projectDir) {
        include("contentdive-*/api/*.api")
        exclude("contentdive-spi/api/*.api")
    },
)

val forbiddenImplementationModels = listOf(
    "PreparedProjection",
    "PreparedTextChunk",
    "BackendTermCandidate",
    "GenericDocument",
)

val implementationBoundaryChecks = forbiddenImplementationModels.map { modelName ->
    tasks.register<CheckForbiddenTextTask>("checkPublicApiWithout$modelName") {
        group = "verification"
        description = "Checks that implementation models are absent from regular public APIs."
        sourceFiles.from(regularPublicApiSurface)
        forbiddenText.set(modelName)
    }
}

val integrationTypeChecks = listOf(
    Triple("AnnotatedString", "contentdive-compose/api/*.api", "AnnotatedString"),
    Triple("NavKey", "contentdive-navigation3/api/*.api", "NavKey"),
    Triple("KotlinxSerialization", "contentdive-serialization-kotlinx/api/*.api", "kotlinx.serialization"),
).map { (suffix, allowedPattern, needle) ->
    tasks.register<CheckForbiddenTextTask>("check${suffix}ApiIsolation") {
        group = "verification"
        description = "Checks that integration-specific types stay in their integration API."
        sourceFiles.from(
            fileTree(projectDir) {
                include("contentdive-*/api/*.api")
                exclude(allowedPattern)
            },
        )
        forbiddenText.set(needle)
    }
}

tasks.named("check") {
    dependsOn(checkReadme)
    dependsOn(checkComposeTypeIsolation)
    dependsOn(fuzzyIsolationChecks)
    dependsOn(standardConsumerBoundaryChecks)
    dependsOn(coreApiBoundaryChecks)
    dependsOn(implementationBoundaryChecks)
    dependsOn(integrationTypeChecks)
    dependsOn(":app-example:checkStandardConsumerClasspath")
    dependsOn("dokkaGenerate")
}
