package com.contentdive.ksp.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSName
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Nullability
import com.google.devtools.ksp.validate

/**
 * Service-loaded KSP entry point for ContentDive's bounded, isolating projector generator.
 *
 * Applications place the processor artifact on the `ksp` configuration only; this implementation
 * class is not a runtime API and generated projectors use no reflection.
 */
internal class ContentDiveSymbolProcessorProvider : SymbolProcessorProvider {
    /** Creates one processor instance for the current KSP compilation. */
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        ContentDiveSymbolProcessor(environment.codeGenerator, environment.logger)
}

private class ContentDiveSymbolProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {
    private val processedDocuments = mutableSetOf<String>()
    private val reportedMisuse = mutableSetOf<String>()

    override fun process(resolver: com.google.devtools.ksp.processing.Resolver): List<KSAnnotated> {
        validateAnnotationPlacement(resolver)
        val deferred = mutableListOf<KSAnnotated>()
        resolver.getSymbolsWithAnnotation(DOCUMENT_ANNOTATION).forEach { symbol ->
            if (!symbol.validate()) {
                deferred += symbol
                return@forEach
            }
            val declaration = symbol as? KSClassDeclaration
            if (declaration == null) {
                logger.error("@ContentDiveDocument can only annotate a class", symbol)
                return@forEach
            }
            val qualifiedName = declaration.qualifiedName?.asString()
            if (qualifiedName == null || !processedDocuments.add(qualifiedName)) return@forEach

            parseDocument(declaration)?.let(::generateProjector)
        }
        return deferred
    }

    private fun validateAnnotationPlacement(resolver: com.google.devtools.ksp.processing.Resolver) {
        PROPERTY_ANNOTATIONS.forEach { annotationName ->
            resolver.getSymbolsWithAnnotation(annotationName).forEach { symbol ->
                val property = symbol as? KSPropertyDeclaration ?: return@forEach
                val document = property.parentDeclaration as? KSClassDeclaration
                if (document?.hasAnnotation(DOCUMENT_ANNOTATION) == true) return@forEach

                val key = "$annotationName:${property.qualifiedName?.asString()}"
                if (reportedMisuse.add(key)) {
                    logger.error(
                        "@${annotationName.substringAfterLast('.')} may only be used on a property " +
                            "inside a @ContentDiveDocument class",
                        property,
                    )
                }
            }
        }
    }

    private fun parseDocument(declaration: KSClassDeclaration): DocumentModel? {
        var valid = true
        fun report(message: String, node: KSNode = declaration) {
            valid = false
            logger.error(message, node)
        }

        if (declaration.classKind != ClassKind.CLASS) {
            report("@ContentDiveDocument can only annotate a class")
        }
        if (declaration.parentDeclaration != null) {
            report("@ContentDiveDocument does not support nested classes in this MVP")
        }
        if (declaration.typeParameters.isNotEmpty()) {
            report("@ContentDiveDocument does not support generic classes in this MVP")
        }
        if (!declaration.isAccessibleFromGeneratedCode()) {
            report("@ContentDiveDocument class '${declaration.simpleName.asString()}' is inaccessible from generated code")
        }

        val documentType = declaration.annotation(DOCUMENT_ANNOTATION)
            ?.stringArgument("type")
            .orEmpty()
        if (!VALID_NAME.matches(documentType)) {
            report(
                "@ContentDiveDocument type '$documentType' must match ${VALID_NAME.pattern}",
            )
        }

        val properties = declaration.declarations
            .filterIsInstance<KSPropertyDeclaration>()
            .toList()
        val annotatedProperties = properties.filter { property ->
            PROPERTY_ANNOTATIONS.any(property::hasAnnotation)
        }
        annotatedProperties.forEach { property ->
            if (!property.isAccessibleFromGeneratedCode()) {
                report(
                    "Annotated property '${property.simpleName.asString()}' is inaccessible from generated code",
                    property,
                )
            }
        }

        val idProperties = properties.filter { it.hasAnnotation(ID_ANNOTATION) }
        val titleProperties = properties.filter { it.hasAnnotation(TITLE_ANNOTATION) }
        val subtitleProperties = properties.filter { it.hasAnnotation(SUBTITLE_ANNOTATION) }
        if (idProperties.isEmpty()) report("@ContentDiveDocument requires exactly one @ContentDiveId property")
        if (idProperties.size > 1) report("@ContentDiveDocument has multiple @ContentDiveId properties")
        if (titleProperties.isEmpty()) report("@ContentDiveDocument requires exactly one @ContentDiveTitle property")
        if (titleProperties.size > 1) report("@ContentDiveDocument has multiple @ContentDiveTitle properties")
        if (subtitleProperties.size > 1) report("@ContentDiveDocument has multiple @ContentDiveSubtitle properties")

        annotatedProperties.forEach { property ->
            val type = property.valueType()
            if (type == null) {
                report(
                    "Annotated property '${property.simpleName.asString()}' has unsupported type " +
                        "'${property.type.resolve()}' (supported: String, String?, AnnotatedString, AnnotatedString?)",
                    property,
                )
            }
        }
        idProperties.singleOrNull()?.let { property ->
            if (property.valueType() != ValueType.STRING) {
                report("@ContentDiveId property '${property.simpleName.asString()}' must be a non-null String", property)
            }
        }
        titleProperties.singleOrNull()?.let { property ->
            if (property.valueType()?.nullable == true) {
                report("@ContentDiveTitle property '${property.simpleName.asString()}' must be non-null", property)
            }
        }

        val textFields = properties.mapNotNull { property ->
            val annotation = property.annotation(TEXT_ANNOTATION) ?: return@mapNotNull null
            val field = annotation.stringArgument("field")
            if (!VALID_NAME.matches(field)) {
                report("@ContentDiveText field '$field' must match ${VALID_NAME.pattern}", property)
            }
            TextField(
                property = property,
                field = field,
                weight = annotation.enumArgument("weight"),
            )
        }
        val titleProperty = titleProperties.singleOrNull()
        val fragmentNames = buildList {
            titleProperty?.let { add(it.simpleName.asString() to it) }
            textFields.forEach { add(it.field to it.property) }
        }
        fragmentNames.groupBy(Pair<String, KSPropertyDeclaration>::first)
            .filterValues { occurrences -> occurrences.size > 1 }
            .forEach { (field, occurrences) ->
                report("Generated fragment field '$field' is duplicated", occurrences.first().second)
            }
        titleProperty?.simpleName?.asString()?.let { titleField ->
            if (!VALID_NAME.matches(titleField)) {
                report("Generated title fragment field '$titleField' must match ${VALID_NAME.pattern}", titleProperty)
            }
        }

        if (!valid) return null
        val idProperty = checkNotNull(idProperties.singleOrNull())
        val requiredTitle = checkNotNull(titleProperty)
        return DocumentModel(
            declaration = declaration,
            documentType = documentType,
            idProperty = idProperty,
            titleProperty = requiredTitle,
            subtitleProperty = subtitleProperties.singleOrNull(),
            textFields = textFields.sortedBy(TextField::field),
        )
    }

    private fun generateProjector(model: DocumentModel) {
        val sourceFile = checkNotNull(model.declaration.containingFile)
        val packageName = model.declaration.packageName.asString()
        val modelName = model.declaration.simpleName.asString()
        val projectorName = "${modelName}ContentDiveProjector"
        codeGenerator.createNewFile(
            dependencies = Dependencies(aggregating = false, sourceFile),
            packageName = packageName,
            fileName = projectorName,
        ).bufferedWriter().use { writer ->
            writer.write(renderProjector(model, modelName, projectorName, packageName))
        }
    }
}

private data class DocumentModel(
    val declaration: KSClassDeclaration,
    val documentType: String,
    val idProperty: KSPropertyDeclaration,
    val titleProperty: KSPropertyDeclaration,
    val subtitleProperty: KSPropertyDeclaration?,
    val textFields: List<TextField>,
)

private data class TextField(
    val property: KSPropertyDeclaration,
    val field: String,
    val weight: String,
)

private data class ValueType(
    val kind: ValueKind,
    val nullable: Boolean,
) {
    companion object {
        val STRING = ValueType(ValueKind.STRING, nullable = false)
    }
}

private enum class ValueKind {
    STRING,
    ANNOTATED_STRING,
}

private fun renderProjector(
    model: DocumentModel,
    modelName: String,
    projectorName: String,
    packageName: String,
): String = buildString {
    if (packageName.isNotEmpty()) {
        appendLine("package $packageName")
        appendLine()
    }
    if (model.usesAnnotatedString()) {
        appendLine("import com.contentdive.compose.toSearchFragment")
        appendLine()
    }
    val visibility = if (Modifier.INTERNAL in model.declaration.modifiers) "internal" else "public"
    appendLine("$visibility class $projectorName(")
    appendLine("    private val scopeProvider: ($modelName) -> com.contentdive.api.SearchScope,")
    appendLine("    private val destinationProvider: ($modelName) -> com.contentdive.api.DestinationRef,")
    appendLine(") : com.contentdive.api.SearchProjector<$modelName> {")
    appendLine("    override fun project(value: $modelName): com.contentdive.api.SearchProjection {")
    appendLine("        val scope = scopeProvider(value)")
    appendLine(
        "        val itemId = com.contentdive.ksp.annotations.ContentDiveGeneratedIds.itemId(" +
            "${model.documentType.quoted()}, value.${model.idProperty.referenceName()})",
    )
    appendLine("        val destination = destinationProvider(value)")
    appendLine("        val fragments = buildList {")
    appendTitleFragment(model)
    model.textFields.forEach(::appendTextFragment)
    appendLine("        }")
    appendLine("        return com.contentdive.api.SearchProjection(")
    appendLine("            item = com.contentdive.api.SearchItem(")
    appendLine("                id = itemId,")
    appendLine("                scope = scope,")
    appendLine("                title = ${model.titleProperty.displayExpression()},")
    appendLine("                destination = destination,")
    appendLine("                subtitle = ${model.subtitleProperty?.displayExpression() ?: "null"},")
    appendLine("            ),")
    appendLine("            fragments = fragments,")
    appendLine("        )")
    appendLine("    }")
    appendLine("}")
}

private fun StringBuilder.appendTitleFragment(model: DocumentModel) {
    val property = model.titleProperty
    val field = property.simpleName.asString()
    val type = checkNotNull(property.valueType())
    if (type.kind == ValueKind.ANNOTATED_STRING) {
        appendLine("            add(")
        appendLine("                value.${property.referenceName()}.toSearchFragment(")
        appendFragmentArguments(field, "com.contentdive.api.SearchFragmentKind.TITLE", "2.0")
        appendLine("                ),")
        appendLine("            )")
    } else {
        appendLine("            add(")
        appendLine("                com.contentdive.api.SearchFragment(")
        appendFragmentArguments(field, "com.contentdive.api.SearchFragmentKind.TITLE", "2.0")
        appendLine("                    text = value.${property.referenceName()},")
        appendLine("                ),")
        appendLine("            )")
    }
}

private fun StringBuilder.appendTextFragment(textField: TextField) {
    val property = textField.property
    val type = checkNotNull(property.valueType())
    appendLine("            value.${property.referenceName()}${if (type.nullable) "?" else ""}.let { text ->")
    val contentExpression = if (type.kind == ValueKind.ANNOTATED_STRING) "text.text" else "text"
    appendLine("                if ($contentExpression.isNotBlank()) {")
    appendLine("                    add(")
    if (type.kind == ValueKind.ANNOTATED_STRING) {
        appendLine("                        text.toSearchFragment(")
        appendFragmentArguments(textField.field, textField.kindExpression(), textField.weightExpression(), 7)
        appendLine("                        ),")
    } else {
        appendLine("                        com.contentdive.api.SearchFragment(")
        appendFragmentArguments(textField.field, textField.kindExpression(), textField.weightExpression(), 7)
        appendLine("                            text = text,")
        appendLine("                        ),")
    }
    appendLine("                    )")
    appendLine("                }")
    appendLine("            }")
}

private fun StringBuilder.appendFragmentArguments(
    field: String,
    kind: String,
    weight: String,
    indentLevel: Int = 5,
) {
    val indent = "    ".repeat(indentLevel)
    appendLine(
        "${indent}id = com.contentdive.ksp.annotations.ContentDiveGeneratedIds.fragmentId(${field.quoted()}),",
    )
    appendLine("${indent}itemId = itemId,")
    appendLine("${indent}scope = scope,")
    appendLine("${indent}kind = $kind,")
    appendLine("${indent}weight = $weight,")
}

private fun TextField.kindExpression(): String = when (weight) {
    "TITLE" -> "com.contentdive.api.SearchFragmentKind.TITLE"
    "HEADING" -> "com.contentdive.api.SearchFragmentKind.HEADING"
    else -> "com.contentdive.api.SearchFragmentKind.BODY"
}

private fun TextField.weightExpression(): String = when (weight) {
    "TITLE" -> "2.0"
    "HEADING" -> "1.25"
    else -> "1.0"
}

private fun DocumentModel.usesAnnotatedString(): Boolean =
    titleProperty.valueType()?.kind == ValueKind.ANNOTATED_STRING ||
        subtitleProperty?.valueType()?.kind == ValueKind.ANNOTATED_STRING ||
        textFields.any { field -> field.property.valueType()?.kind == ValueKind.ANNOTATED_STRING }

private fun KSPropertyDeclaration.displayExpression(): String = when (valueType()?.kind) {
    ValueKind.ANNOTATED_STRING -> "value.${referenceName()}${if (valueType()?.nullable == true) "?" else ""}.text"
    else -> "value.${referenceName()}"
}

private fun KSPropertyDeclaration.valueType(): ValueType? {
    val resolved = type.resolve()
    val kind = when (resolved.declaration.qualifiedName?.asString()) {
        "kotlin.String" -> ValueKind.STRING
        "androidx.compose.ui.text.AnnotatedString" -> ValueKind.ANNOTATED_STRING
        else -> return null
    }
    return ValueType(kind, resolved.nullability == Nullability.NULLABLE)
}

private fun KSDeclaration.isAccessibleFromGeneratedCode(): Boolean =
    Modifier.PRIVATE !in modifiers && Modifier.PROTECTED !in modifiers

private fun KSAnnotated.hasAnnotation(qualifiedName: String): Boolean = annotation(qualifiedName) != null

private fun KSAnnotated.annotation(qualifiedName: String): KSAnnotation? = annotations.firstOrNull {
    it.annotationType.resolve().declaration.qualifiedName?.asString() == qualifiedName
}

private fun KSAnnotation.stringArgument(name: String): String =
    arguments.firstOrNull { it.name?.asString() == name }?.value as? String ?: ""

private fun KSAnnotation.enumArgument(name: String): String {
    val value = arguments.firstOrNull { it.name?.asString() == name }?.value
    return when (value) {
        is KSName -> value.getShortName()
        is KSType -> value.declaration.simpleName.asString()
        else -> value.toString().substringAfterLast('.')
    }
}

private fun KSPropertyDeclaration.referenceName(): String = "`${simpleName.asString()}`"

private fun String.quoted(): String = buildString {
    append('"')
    this@quoted.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '$' -> append("\\$")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(character)
        }
    }
    append('"')
}

private val VALID_NAME = Regex("[A-Za-z][A-Za-z0-9._-]*")
private const val DOCUMENT_ANNOTATION = "com.contentdive.ksp.annotations.ContentDiveDocument"
private const val ID_ANNOTATION = "com.contentdive.ksp.annotations.ContentDiveId"
private const val TITLE_ANNOTATION = "com.contentdive.ksp.annotations.ContentDiveTitle"
private const val SUBTITLE_ANNOTATION = "com.contentdive.ksp.annotations.ContentDiveSubtitle"
private const val TEXT_ANNOTATION = "com.contentdive.ksp.annotations.ContentDiveText"
private val PROPERTY_ANNOTATIONS = listOf(
    ID_ANNOTATION,
    TITLE_ANNOTATION,
    SUBTITLE_ANNOTATION,
    TEXT_ANNOTATION,
)
