import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

class ContentDiveMavenPublishConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("maven-publish")

        pluginManager.withPlugin("java-library") {
            extensions.configure(JavaPluginExtension::class.java) {
                withSourcesJar()
            }
            extensions.configure(PublishingExtension::class.java) {
                publications.create("maven", MavenPublication::class.java) {
                    from(components.getByName("java"))
                    artifactId = project.name
                    configurePom(project)
                }
            }
        }

        pluginManager.withPlugin("com.android.library") {
            extensions.configure(LibraryExtension::class.java) {
                publishing {
                    singleVariant("release") {
                        withSourcesJar()
                    }
                }
            }
            afterEvaluate {
                extensions.configure(PublishingExtension::class.java) {
                    publications.create("release", MavenPublication::class.java) {
                        from(components.getByName("release"))
                        artifactId = project.name
                        configurePom(project)
                    }
                }
            }
        }
    }
}

private fun MavenPublication.configurePom(project: Project) {
    pom {
        name.set(project.name)
        description.set("ContentDive ${project.name.removePrefix("contentdive-")} module")
        url.set("https://github.com/miroslavhybler/Android-Content-Dive")
        licenses {
            license {
                name.set("Apache License 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        scm {
            connection.set("scm:git:git://github.com/miroslavhybler/Android-Content-Dive.git")
            developerConnection.set("scm:git:ssh://github.com/miroslavhybler/Android-Content-Dive.git")
            url.set("https://github.com/miroslavhybler/Android-Content-Dive")
        }
    }
}
