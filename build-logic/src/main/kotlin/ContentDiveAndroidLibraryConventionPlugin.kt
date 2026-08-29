import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project

class ContentDiveAndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
        pluginManager.apply("com.android.library")

        extensions.configure(LibraryExtension::class.java) {
            compileSdk = 37

            defaultConfig {
                minSdk = 24
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }

            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_11
                targetCompatibility = JavaVersion.VERSION_11
            }

        }

        dependencies.add(
            "testImplementation",
            "org.jetbrains.kotlin:kotlin-test-junit:2.2.10",
        )

        configureContentDiveDocumentation()

        val apiBuild = tasks.register(
            "apiBuild",
            GenerateAndroidBinaryApiTask::class.java,
        ) {
            dependsOn("compileReleaseKotlin")
            classDirectories.from(
                layout.buildDirectory.dir(
                    "intermediates/built_in_kotlinc/release/compileReleaseKotlin/classes",
                ),
            )
            outputFile.set(
                layout.buildDirectory.file("api/current/${project.name}.api"),
            )
        }
        val apiDump = tasks.register(
            "apiDump",
            UpdateAndroidBinaryApiTask::class.java,
        ) {
            generatedApiFile.set(apiBuild.flatMap { it.outputFile })
            apiFile.set(layout.projectDirectory.file("api/${project.name}.api"))
        }
        val apiCheck = tasks.register(
            "apiCheck",
            CheckAndroidBinaryApiTask::class.java,
        ) {
            generatedApiFile.set(apiBuild.flatMap { it.outputFile })
            apiFile.set(layout.projectDirectory.file("api/${project.name}.api"))
        }

        tasks.named("check").configure {
            dependsOn(apiCheck)
        }

        rootProject.tasks.matching { it.name == "apiDump" }.configureEach {
            dependsOn(apiDump)
        }
        rootProject.tasks.matching { it.name == "apiCheck" }.configureEach {
            dependsOn(apiCheck)
        }
        }
    }
}
