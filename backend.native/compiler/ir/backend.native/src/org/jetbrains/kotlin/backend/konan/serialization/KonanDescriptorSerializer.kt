/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

package org.jetbrains.kotlin.serialization

import org.jetbrains.kotlin.backend.common.onlyIf
import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.backend.konan.descriptors.needsSerializedIr
import org.jetbrains.kotlin.backend.konan.serialization.IrAwareExtension
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.metadata.ProtoBuf
import org.jetbrains.kotlin.metadata.deserialization.Flags
import org.jetbrains.kotlin.metadata.serialization.Interner
import org.jetbrains.kotlin.metadata.serialization.MutableTypeTable
import org.jetbrains.kotlin.metadata.serialization.MutableVersionRequirementTable
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.DescriptorUtils.isEnumEntry
import org.jetbrains.kotlin.serialization.deserialization.ProtoEnumFlags

class KonanDescriptorSerializer private constructor(
        private val context: Context,
        private val containingDeclaration: DeclarationDescriptor?,
        private val typeParameters: Interner<TypeParameterDescriptor>,
        private val extension: SerializerExtension,
        private val typeTable: MutableTypeTable,
        private val versionRequirementTable: MutableVersionRequirementTable,
        private val serializeTypeTableToFunction: Boolean
): DescriptorSerializer(
        containingDeclaration = containingDeclaration,
        typeParameters = typeParameters,
        extension = extension,
        typeTable = typeTable,
        versionRequirementTable = versionRequirementTable,
        serializeTypeTableToFunction = serializeTypeTableToFunction) {

    override fun createChildSerializer(descriptor: DeclarationDescriptor): KonanDescriptorSerializer =
            KonanDescriptorSerializer(context, descriptor, Interner(typeParameters), extension, typeTable, versionRequirementTable,
                                 serializeTypeTableToFunction = false)

    override fun classProto(classDescriptor: ClassDescriptor): ProtoBuf.Class.Builder {
        val builder = super.classProto(classDescriptor)

        context.ir.classesDelegatedBackingFields[classDescriptor]?.forEach {
            builder.addProperty(propertyProto(it))
        }

        return builder
    }

    override fun propertyProto(descriptor: PropertyDescriptor): ProtoBuf.Property.Builder {
        val builder = super.propertyProto(descriptor)
        val local = createChildSerializer(descriptor)

        /* Konan specific chunk */
        if (extension is IrAwareExtension) {
            descriptor.getter?.onlyIf({needsSerializedIr}) {
                extension.addGetterIR(builder,
                    extension.serializeInlineBody(it, local))
            }
            descriptor.setter?.onlyIf({needsSerializedIr}) {
                extension.addSetterIR(builder,
                    extension.serializeInlineBody(it, local))
            }
        }

        return builder
    }

    override fun functionProto(descriptor: FunctionDescriptor): ProtoBuf.Function.Builder {
        val builder = ProtoBuf.Function.newBuilder()

        val local = createChildSerializer(descriptor)

        val flags = Flags.getFunctionFlags(
            hasAnnotations(descriptor),
            ProtoEnumFlags.visibility(normalizeVisibility(descriptor)),
            ProtoEnumFlags.modality(descriptor.modality),
            ProtoEnumFlags.memberKind(descriptor.kind),
            descriptor.isOperator, descriptor.isInfix, descriptor.isInline, descriptor.isTailrec, descriptor.isExternal,
            descriptor.isSuspend, descriptor.isExpect
        )
        if (flags != builder.flags) {
            builder.flags = flags
        }

        builder.name = getSimpleNameIndex(descriptor.name)

        if (useTypeTable()) {
            builder.returnTypeId = local.typeId(descriptor.returnType!!)
        }
        else {
            builder.setReturnType(local.type(descriptor.returnType!!))
        }

        for (typeParameterDescriptor in descriptor.typeParameters) {
            builder.addTypeParameter(local.typeParameter(typeParameterDescriptor))
        }

        val receiverParameter = descriptor.extensionReceiverParameter
        if (receiverParameter != null) {
            if (useTypeTable()) {
                builder.receiverTypeId = local.typeId(receiverParameter.type)
            }
            else {
                builder.setReceiverType(local.type(receiverParameter.type))
            }
        }

        for (valueParameterDescriptor in descriptor.valueParameters) {
            builder.addValueParameter(local.valueParameter(valueParameterDescriptor))
        }

        if (serializeTypeTableToFunction) {
            val typeTableProto = typeTable.serialize()
            if (typeTableProto != null) {
                builder.typeTable = typeTableProto
            }
        }

        builder.addAllVersionRequirement(serializeVersionRequirements(descriptor))

        if (descriptor.isSuspendOrHasSuspendTypesInSignature()) {
            builder.addVersionRequirement(writeVersionRequirementDependingOnCoroutinesVersion())
        }

        contractSerializer.serializeContractOfFunctionIfAny(descriptor, builder, this)

        extension.serializeFunction(descriptor, builder)

        /* Konan specific chunk */
        if (extension is IrAwareExtension && descriptor.needsSerializedIr) {
            extension.addFunctionIR(builder,
                    extension.serializeInlineBody(descriptor, local))
        }

        return builder
    }

    override fun constructorProto(descriptor: ConstructorDescriptor): ProtoBuf.Constructor.Builder {
        val builder = super.constructorProto(descriptor)
        val local = createChildSerializer(descriptor)

        /* Konan specific chunk */
        if (extension is IrAwareExtension && descriptor.needsSerializedIr) {
            extension.addConstructorIR(builder, 
                extension.serializeInlineBody(descriptor, local))
        }

        return builder
    }

    companion object {
        @JvmStatic
        internal fun createTopLevel(context: Context, extension: SerializerExtension): KonanDescriptorSerializer {
            return KonanDescriptorSerializer(context, null, Interner(), extension, MutableTypeTable(), MutableVersionRequirementTable(),
                                        serializeTypeTableToFunction = false)
        }

        @JvmStatic
        internal fun create(context: Context, descriptor: ClassDescriptor, extension: SerializerExtension): KonanDescriptorSerializer {
            val container = descriptor.containingDeclaration
            val parentSerializer = if (container is ClassDescriptor)
                create(context, container, extension)
            else
                createTopLevel(context, extension)

            // Calculate type parameter ids for the outer class beforehand, as it would've had happened if we were always
            // serializing outer classes before nested classes.
            // Otherwise our interner can get wrong ids because we may serialize classes in any order.
            val serializer = KonanDescriptorSerializer(
                    context,
                    descriptor,
                    Interner(parentSerializer.typeParameters),
                    parentSerializer.extension,
                    MutableTypeTable(),
                    MutableVersionRequirementTable(),
                    serializeTypeTableToFunction = false
            )
            for (typeParameter in descriptor.declaredTypeParameters) {
                serializer.typeParameters.intern(typeParameter)
            }
            return serializer
        }
    }
}
