import org.gradle.api.Project
import org.jetbrains.dokka.gradle.DokkaExtension
import org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier

/** Applies the shared rendered-public-API documentation gate to one published module. */
fun Project.configureContentDiveDocumentation() {
    pluginManager.apply("org.jetbrains.dokka")
    extensions.configure(DokkaExtension::class.java) {
        moduleName.set(project.name)
        moduleVersion.set(project.version.toString())
        dokkaPublications.named("html") {
            outputDirectory.set(layout.buildDirectory.dir("dokka/html"))
            failOnWarning.set(true)
            suppressObviousFunctions.set(true)
        }
        dokkaSourceSets.configureEach {
            documentedVisibilities.set(setOf(VisibilityModifier.Public))
            reportUndocumented.set(true)
            skipEmptyPackages.set(true)
            suppressGeneratedFiles.set(true)
        }
    }
}
