import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

class ContentDiveKotlinLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("java-library")
        pluginManager.apply("org.jetbrains.kotlin.jvm")

        extensions.configure(JavaPluginExtension::class.java) {
            sourceCompatibility = JavaVersion.VERSION_11
            targetCompatibility = JavaVersion.VERSION_11
        }

        extensions.configure(KotlinJvmProjectExtension::class.java) {
            explicitApi()
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_11)
                freeCompilerArgs.add("-Xjsr305=strict")
            }
        }

        dependencies.add(
            "testImplementation",
            "org.jetbrains.kotlin:kotlin-test-junit:2.2.10",
        )

        tasks.withType(Test::class.java).configureEach {
            maxParallelForks = 1
        }

        configureContentDiveDocumentation()
    }
}
