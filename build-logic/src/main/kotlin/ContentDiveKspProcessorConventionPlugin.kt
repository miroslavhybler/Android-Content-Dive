import org.gradle.api.Plugin
import org.gradle.api.Project

class ContentDiveKspProcessorConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
        pluginManager.apply("contentdive.kotlin-library")
        dependencies.add(
            "implementation",
            "com.google.devtools.ksp:symbol-processing-api:2.2.10-2.0.2",
        )
        }
    }
}
