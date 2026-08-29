package com.contentdive.serialization.kotlinx

import com.contentdive.api.AnchorRef
import com.contentdive.api.DestinationRef
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Converts application-owned destination payloads to and from opaque [DestinationRef] values.
 *
 * @param T application payload used to identify or navigate to an entity.
 */
public interface DestinationCodec<T> {
    /** Stable schema name written to and required from [DestinationRef.type]. */
    public val type: String

    /** Positive schema version written to and required from [DestinationRef.version]. */
    public val version: Int

    /** Encodes [value] into a reference that ContentDive can preserve without interpreting. */
    public fun encode(value: T): DestinationRef

    /**
     * Validates the reference type and version, then decodes its payload.
     *
     * @throws IllegalArgumentException if the reference uses another type or version.
     */
    public fun decode(reference: DestinationRef): T
}

/**
 * Converts application-owned block or focus payloads to and from opaque [AnchorRef] values.
 *
 * @param T application payload identifying a location within a destination.
 */
public interface AnchorCodec<T> {
    /** Stable schema name written to and required from [AnchorRef.type]. */
    public val type: String

    /** Positive schema version written to and required from [AnchorRef.version]. */
    public val version: Int

    /** Encodes [value] into an optional-location reference preserved with a fragment. */
    public fun encode(value: T): AnchorRef

    /**
     * Validates the reference type and version, then decodes its payload.
     *
     * @throws IllegalArgumentException if the reference uses another type or version.
     */
    public fun decode(reference: AnchorRef): T
}

/**
 * JSON [DestinationCodec] backed by a caller-supplied kotlinx.serialization serializer.
 *
 * The encoded payload is application navigation metadata, not authoritative entity data. Keep
 * [type] stable and increment [version] when the serialized schema becomes incompatible.
 *
 * @param T serializable application destination payload.
 * @property type non-blank destination schema name.
 * @property version positive destination schema version.
 * @param serializer serializer for [T]; it is used directly without reflection.
 * @param json JSON configuration used for both encoding and decoding.
 * @throws IllegalArgumentException if [type] is blank or [version] is not positive.
 */
public class KotlinxDestinationCodec<T>(
    override val type: String,
    override val version: Int,
    private val serializer: KSerializer<T>,
    private val json: Json = Json,
) : DestinationCodec<T> {
    init {
        require(type.isNotBlank()) { "Destination codec type must not be blank" }
        require(version > 0) { "Destination codec version must be positive" }
    }

    /**
     * Encodes [value] with the configured serializer and schema metadata.
     *
     * @throws SerializationException if [value] cannot be serialized.
     */
    override fun encode(value: T): DestinationRef = DestinationRef(
        type = type,
        version = version,
        payload = json.encodeToString(serializer, value),
    )

    /**
     * Validates schema metadata before decoding the JSON payload.
     *
     * @throws IllegalArgumentException if [reference] has another type or version.
     * @throws SerializationException if its payload cannot be decoded as [T].
     */
    override fun decode(reference: DestinationRef): T {
        require(reference.type == type) {
            "Expected destination type '$type', received '${reference.type}'"
        }
        require(reference.version == version) {
            "Expected destination version $version, received ${reference.version}"
        }
        return json.decodeFromString(serializer, reference.payload)
    }
}

/**
 * JSON [AnchorCodec] backed by a caller-supplied kotlinx.serialization serializer.
 *
 * Keep [type] stable and increment [version] when the serialized block/focus schema becomes
 * incompatible.
 *
 * @param T serializable application anchor payload.
 * @property type non-blank anchor schema name.
 * @property version positive anchor schema version.
 * @param serializer serializer for [T]; it is used directly without reflection.
 * @param json JSON configuration used for both encoding and decoding.
 * @throws IllegalArgumentException if [type] is blank or [version] is not positive.
 */
public class KotlinxAnchorCodec<T>(
    override val type: String,
    override val version: Int,
    private val serializer: KSerializer<T>,
    private val json: Json = Json,
) : AnchorCodec<T> {
    init {
        require(type.isNotBlank()) { "Anchor codec type must not be blank" }
        require(version > 0) { "Anchor codec version must be positive" }
    }

    /**
     * Encodes [value] with the configured serializer and schema metadata.
     *
     * @throws SerializationException if [value] cannot be serialized.
     */
    override fun encode(value: T): AnchorRef = AnchorRef(
        type = type,
        version = version,
        payload = json.encodeToString(serializer, value),
    )

    /**
     * Validates schema metadata before decoding the JSON payload.
     *
     * @throws IllegalArgumentException if [reference] has another type or version.
     * @throws SerializationException if its payload cannot be decoded as [T].
     */
    override fun decode(reference: AnchorRef): T {
        require(reference.type == type) {
            "Expected anchor type '$type', received '${reference.type}'"
        }
        require(reference.version == version) {
            "Expected anchor version $version, received ${reference.version}"
        }
        return json.decodeFromString(serializer, reference.payload)
    }
}
