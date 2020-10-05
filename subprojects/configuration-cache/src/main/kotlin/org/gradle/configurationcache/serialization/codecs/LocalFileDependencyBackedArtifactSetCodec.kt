/*
 * Copyright 2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.configurationcache.serialization.codecs

import org.gradle.api.artifacts.FileCollectionDependency
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.AttributeContainer
import org.gradle.api.file.FileCollection
import org.gradle.api.internal.CollectionCallbackActionDecorator
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.LocalFileDependencyBackedArtifactSet
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvedArtifactSet
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvedVariant
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvedVariantSet
import org.gradle.api.internal.artifacts.transform.ArtifactTransformDependencies
import org.gradle.api.internal.artifacts.transform.DefaultArtifactTransformDependencies
import org.gradle.api.internal.artifacts.transform.ExecutionGraphDependenciesResolver
import org.gradle.api.internal.artifacts.transform.ExtraExecutionGraphDependenciesResolverFactory
import org.gradle.api.internal.artifacts.transform.Transformation
import org.gradle.api.internal.artifacts.transform.TransformationStep
import org.gradle.api.internal.artifacts.transform.TransformedVariantFactory
import org.gradle.api.internal.artifacts.transform.Transformer
import org.gradle.api.internal.artifacts.transform.VariantSelector
import org.gradle.api.internal.artifacts.type.DefaultArtifactTypeRegistry
import org.gradle.api.internal.attributes.AttributeContainerInternal
import org.gradle.api.internal.attributes.AttributesSchemaInternal
import org.gradle.api.internal.attributes.EmptySchema
import org.gradle.api.internal.attributes.ImmutableAttributes
import org.gradle.api.internal.attributes.ImmutableAttributesFactory
import org.gradle.api.internal.file.FileCollectionFactory
import org.gradle.api.internal.file.FileCollectionInternal
import org.gradle.api.internal.tasks.TaskDependencyContainer
import org.gradle.api.specs.Specs
import org.gradle.configurationcache.serialization.Codec
import org.gradle.configurationcache.serialization.ReadContext
import org.gradle.configurationcache.serialization.WriteContext
import org.gradle.configurationcache.serialization.decodePreservingSharedIdentity
import org.gradle.configurationcache.serialization.encodePreservingSharedIdentityOf
import org.gradle.configurationcache.serialization.readCollection
import org.gradle.configurationcache.serialization.readNonNull
import org.gradle.configurationcache.serialization.writeCollection
import org.gradle.internal.Describables
import org.gradle.internal.DisplayName
import org.gradle.internal.Try
import org.gradle.internal.component.local.model.LocalFileDependencyMetadata
import org.gradle.internal.component.model.VariantResolveMetadata
import org.gradle.internal.reflect.Instantiator
import java.io.File


class LocalFileDependencyBackedArtifactSetCodec(
    private val instantiator: Instantiator,
    private val attributesFactory: ImmutableAttributesFactory,
    private val fileCollectionFactory: FileCollectionFactory,
    private val transformedVariantFactory: TransformedVariantFactory
) : Codec<LocalFileDependencyBackedArtifactSet> {
    override suspend fun WriteContext.encode(value: LocalFileDependencyBackedArtifactSet) {
        // TODO - When the set of files is fixed (eg is `gradleApi()` or some hard-coded list of files):
        //   - calculate the attributes for each of the files eagerly rather than writing the mappings
        //   - when the selector would not apply a transform, then write only the files and nothing else
        //   - otherwise, write only the transform and attributes for each file rather than writing the transform registry
        write(value.dependencyMetadata.componentId)
        write(value.dependencyMetadata.files)

        // Write the file extension -> attributes mappings
        // TODO - move this to an encoder
        encodePreservingSharedIdentityOf(value.artifactTypeRegistry) {
            val mappings = value.artifactTypeRegistry.create()!!
            writeCollection(mappings) {
                writeString(it.name)
                write(it.attributes)
            }
        }

        for (artifactTypeDefinition in value.artifactTypeRegistry.create()!!) {
            val sourceAttributes = value.artifactTypeRegistry.mapAttributesFor(File("thing.${artifactTypeDefinition.name}"))
            val recordingSet = RecordingVariantSet(value.dependencyMetadata.files, sourceAttributes)
            value.selector.select(recordingSet, recordingSet)
            println("-> selected for ${artifactTypeDefinition.name}: ${recordingSet.targetAttributes} -> ${recordingSet.transformation?.displayName}")
            write(recordingSet.targetAttributes)
            if (recordingSet.targetAttributes != null) {
                write(recordingSet.transformation)
            }
        }
    }

    override suspend fun ReadContext.decode(): LocalFileDependencyBackedArtifactSet {
        val componentId = read() as ComponentIdentifier?
        val files = readNonNull<FileCollectionInternal>()

        // TODO - use an immutable registry implementation
        val artifactTypeRegistry = decodePreservingSharedIdentity {
            val registry = DefaultArtifactTypeRegistry(instantiator, attributesFactory, CollectionCallbackActionDecorator.NOOP)
            val mappings = registry.create()!!
            readCollection {
                val name = readString()
                val attributes = readNonNull<AttributeContainer>()
                val mapping = mappings.create(name).attributes
                @Suppress("UNCHECKED_CAST")
                for (attribute in attributes.keySet() as Set<Attribute<Any>>) {
                    mapping.attribute(attribute, attributes.getAttribute(attribute) as Any)
                }
            }
            registry
        }

        val transforms = mutableMapOf<ImmutableAttributes, TransformSpec>()
        for (artifactTypeDefinition in artifactTypeRegistry.create()!!) {
            val sourceAttributes = artifactTypeRegistry.mapAttributesFor(File("thing.${artifactTypeDefinition.name}"))
            val targetAttributes = read() as ImmutableAttributes?
            if (targetAttributes == null) {
                continue
            }
            val transformation = readNonNull<Transformation>()
            transforms.put(sourceAttributes, TransformSpec(targetAttributes, transformation))
        }

        val selector = FixedVariantSelector(transforms, fileCollectionFactory, transformedVariantFactory)
        return LocalFileDependencyBackedArtifactSet(FixedFileMetadata(componentId, files), Specs.satisfyAll(), selector, artifactTypeRegistry)
    }
}


private
class RecordingVariantSet(
    private val source: FileCollectionInternal,
    private val attributes: ImmutableAttributes
) : ResolvedVariantSet, ResolvedVariant, VariantSelector.Factory {
    var targetAttributes: ImmutableAttributes? = null
    var transformation: Transformation? = null

    override fun asDescribable(): DisplayName {
        return Describables.of(source)
    }

    override fun getSchema(): AttributesSchemaInternal {
        return EmptySchema.INSTANCE
    }

    override fun getVariants(): Set<ResolvedVariant> {
        return setOf(this)
    }

    override fun getOverriddenAttributes(): ImmutableAttributes {
        return ImmutableAttributes.EMPTY
    }

    override fun getIdentifier(): VariantResolveMetadata.Identifier? {
        return null
    }

    override fun getAttributes(): AttributeContainerInternal {
        return attributes
    }

    override fun getArtifacts(): ResolvedArtifactSet {
        return ResolvedArtifactSet.EMPTY
    }

    override fun asTransformed(sourceVariant: ResolvedVariant, targetAttributes: ImmutableAttributes, transformation: Transformation, dependenciesResolver: ExtraExecutionGraphDependenciesResolverFactory, transformedVariantFactory: TransformedVariantFactory): ResolvedArtifactSet {
        this.transformation = transformation
        this.targetAttributes = targetAttributes
        return sourceVariant.artifacts
    }
}


private
class TransformSpec(val targetAttributes: ImmutableAttributes, val transformation: Transformation)


private
class FixedVariantSelector(
    private val transforms: Map<ImmutableAttributes, TransformSpec>,
    private val fileCollectionFactory: FileCollectionFactory,
    private val transformedVariantFactory: TransformedVariantFactory
) : VariantSelector {
    override fun select(candidates: ResolvedVariantSet, factory: VariantSelector.Factory): ResolvedArtifactSet {
        require(candidates.variants.size == 1)
        val variant = candidates.variants.first()
        val transform = transforms.get(variant.attributes)
        if (transform == null) {
            return variant.artifacts
        }
        return factory.asTransformed(variant, transform.targetAttributes, transform.transformation, EmptyDependenciesResolverFactory(fileCollectionFactory), transformedVariantFactory)
    }
}


private
class FixedFileMetadata(
    private val compId: ComponentIdentifier?,
    private val source: FileCollectionInternal
) : LocalFileDependencyMetadata {
    override fun getComponentId(): ComponentIdentifier? {
        return compId
    }

    override fun getFiles(): FileCollectionInternal {
        return source
    }

    override fun getSource(): FileCollectionDependency {
        throw UnsupportedOperationException("Should not be called")
    }
}


private
class EmptyDependenciesResolverFactory(private val fileCollectionFactory: FileCollectionFactory) : ExtraExecutionGraphDependenciesResolverFactory {
    override fun create(componentIdentifier: ComponentIdentifier): ExecutionGraphDependenciesResolver {
        return object : ExecutionGraphDependenciesResolver {
            override fun computeDependencyNodes(transformationStep: TransformationStep): TaskDependencyContainer {
                TODO("Not yet implemented")
            }

            override fun selectedArtifacts(transformer: Transformer): FileCollection {
                TODO("Not yet implemented")
            }

            override fun computeArtifacts(transformer: Transformer): Try<ArtifactTransformDependencies> {
                return Try.successful(DefaultArtifactTransformDependencies(fileCollectionFactory.empty()))
            }
        }
    }
}
