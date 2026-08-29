package com.contentdive.compose

import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import com.tschuchort.compiletesting.kspSourcesDir
import java.io.ByteArrayOutputStream
import java.util.ServiceLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi

@OptIn(ExperimentalCompilerApi::class)
internal class ContentDiveSymbolProcessorTest {
    @Test
    fun `generates deterministic String and AnnotatedString projector without reflection`() {
        val compilation = compilation(
            SourceFile.kotlin(
                "GeneratedEvent.kt",
                """
                package generated

                import androidx.compose.ui.text.AnnotatedString
                import com.contentdive.ksp.annotations.*

                @ContentDiveDocument(type = "event")
                data class GeneratedEvent(
                    @ContentDiveId val id: String,
                    @ContentDiveTitle val title: AnnotatedString,
                    @ContentDiveSubtitle
                    @ContentDiveText(field = "location")
                    val location: String?,
                    @ContentDiveText(field = "optional") val optional: AnnotatedString?,
                    @ContentDiveText(field = "body") val body: String,
                )
                """.trimIndent(),
            ),
        )

        val result = compilation.compile()

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val generated = compilation.kspSourcesDir.walkTopDown()
            .single { it.name == "GeneratedEventContentDiveProjector.kt" }
            .readText()
        assertTrue(generated.contains("class GeneratedEventContentDiveProjector"))
        assertTrue(generated.contains("ContentDiveGeneratedIds.itemId(\"event\""))
        assertTrue(generated.contains("toSearchFragment("))
        assertTrue(generated.indexOf("fragmentId(\"title\")") < generated.indexOf("fragmentId(\"body\")"))
        assertTrue(generated.indexOf("fragmentId(\"body\")") < generated.indexOf("fragmentId(\"location\")"))
        assertTrue(generated.indexOf("fragmentId(\"location\")") < generated.indexOf("fragmentId(\"optional\")"))
        assertTrue(generated.contains("subtitle = value.`location`"))
        assertTrue(generated.contains("value.`location`?.let"))
        assertTrue(generated.contains("value.`optional`?.let"))
        assertFalse(generated.contains("java.lang.reflect"))
        assertFalse(generated.contains("kotlin.reflect"))
        assertFalse(generated.contains("Class.forName"))
    }

    @Test
    fun `invalid declarations report every bounded validation diagnostic`() {
        val compilation = compilation(
            SourceFile.kotlin(
                "InvalidDocuments.kt",
                """
                package invalid

                import com.contentdive.ksp.annotations.*

                @ContentDiveDocument(type = "event")
                data class NoId(@ContentDiveTitle val title: String)

                @ContentDiveDocument(type = "event")
                data class NoTitle(@ContentDiveId val id: String)

                @ContentDiveDocument(type = "event")
                data class Multiple(
                    @ContentDiveId val firstId: String,
                    @ContentDiveId val secondId: String,
                    @ContentDiveTitle val firstTitle: String,
                    @ContentDiveTitle val secondTitle: String,
                )

                @ContentDiveDocument(type = "event")
                data class DuplicateField(
                    @ContentDiveId val id: String,
                    @ContentDiveTitle val title: String,
                    @ContentDiveText(field = "title") val body: String,
                )

                @ContentDiveDocument(type = "event")
                data class Unsupported(
                    @ContentDiveId val id: String,
                    @ContentDiveTitle val title: String,
                    @ContentDiveText(field = "count") val count: Int,
                )

                @ContentDiveDocument(type = "event")
                data class Inaccessible(
                    @ContentDiveId val id: String,
                    @ContentDiveTitle val title: String,
                    @ContentDiveText(field = "secret") private val secret: String,
                )

                @ContentDiveDocument(type = " ")
                data class InvalidNames(
                    @ContentDiveId val id: String,
                    @ContentDiveTitle val title: String,
                    @ContentDiveText(field = "bad:field") val body: String,
                )

                data class NotADocument(
                    @ContentDiveId val id: String,
                )
                """.trimIndent(),
            ),
        )

        val result = compilation.compile()

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        val diagnostics = result.messages
        assertTrue(diagnostics.contains("requires exactly one @ContentDiveId property"), diagnostics)
        assertTrue(diagnostics.contains("requires exactly one @ContentDiveTitle property"), diagnostics)
        assertTrue(diagnostics.contains("multiple @ContentDiveId properties"), diagnostics)
        assertTrue(diagnostics.contains("multiple @ContentDiveTitle properties"), diagnostics)
        assertTrue(diagnostics.contains("Generated fragment field 'title' is duplicated"), diagnostics)
        assertTrue(diagnostics.contains("has unsupported type"), diagnostics)
        assertTrue(diagnostics.contains("is inaccessible from generated code"), diagnostics)
        assertTrue(diagnostics.contains("@ContentDiveDocument type ' ' must match"), diagnostics)
        assertTrue(diagnostics.contains("@ContentDiveText field 'bad:field' must match"), diagnostics)
        assertTrue(diagnostics.contains("may only be used on a property inside a @ContentDiveDocument class"), diagnostics)
    }

    private fun compilation(vararg sources: SourceFile): KotlinCompilation = KotlinCompilation().apply {
        this.sources = sources.toList()
        inheritClassPath = true
        configureKsp(useKsp2 = true) {
            symbolProcessorProviders += ServiceLoader.load(SymbolProcessorProvider::class.java)
                .single { it.javaClass.name == CONTENT_DIVE_PROCESSOR_PROVIDER }
        }
        messageOutputStream = ByteArrayOutputStream()
    }

    private companion object {
        const val CONTENT_DIVE_PROCESSOR_PROVIDER =
            "com.contentdive.ksp.processor.ContentDiveSymbolProcessorProvider"
    }
}
