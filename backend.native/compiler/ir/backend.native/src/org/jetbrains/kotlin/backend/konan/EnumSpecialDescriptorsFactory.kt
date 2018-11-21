/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

package org.jetbrains.kotlin.backend.konan

import org.jetbrains.kotlin.backend.common.descriptors.WrappedClassDescriptor
import org.jetbrains.kotlin.backend.common.descriptors.WrappedFieldDescriptor
import org.jetbrains.kotlin.backend.common.descriptors.WrappedSimpleFunctionDescriptor
import org.jetbrains.kotlin.backend.common.ir.addSimpleDelegatingConstructor
import org.jetbrains.kotlin.backend.common.ir.buildSimpleDelegatingConstructor
import org.jetbrains.kotlin.backend.common.ir.ir2stringWhole
import org.jetbrains.kotlin.backend.konan.descriptors.synthesizedName
import org.jetbrains.kotlin.backend.konan.descriptors.enumEntries
import org.jetbrains.kotlin.backend.konan.serialization.isExported
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.annotations.Annotations
import org.jetbrains.kotlin.descriptors.impl.*
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.*
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.impl.IrClassSymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrFieldSymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrSimpleFunctionSymbolImpl
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.scopes.MemberScope
import org.jetbrains.kotlin.resolve.scopes.receivers.ImplicitClassReceiver
import org.jetbrains.kotlin.storage.LockBasedStorageManager
import org.jetbrains.kotlin.types.*


internal object DECLARATION_ORIGIN_ENUM : IrDeclarationOriginImpl("ENUM")

internal data class LoweredEnum(val implObject: IrClass,
                                val valuesField: IrField,
                                val valuesGetter: IrSimpleFunction,
                                val itemGetterSymbol: IrSimpleFunctionSymbol,
                                val entriesMap: Map<Name, Int>)

internal class EnumSpecialDeclarationsFactory(val context: Context) {
    fun createLoweredEnum(enumClass: IrClass): LoweredEnum {
        val enumClassDescriptor = enumClass.descriptor

        val startOffset = enumClass.startOffset
        val endOffset = enumClass.endOffset

        val implObjectDescriptor = ClassDescriptorImpl(enumClassDescriptor, "OBJECT".synthesizedName, Modality.FINAL,
                ClassKind.OBJECT, listOf(context.builtIns.anyType), SourceElement.NO_SOURCE, false, LockBasedStorageManager.NO_LOCKS)
//
//        val implObject = IrClassImpl(startOffset, endOffset, DECLARATION_ORIGIN_ENUM, implObjectDescriptor).apply {
//            createParameterDeclarations()
//        }
        val implObject = WrappedClassDescriptor().let {
            IrClassImpl(
                    startOffset = startOffset,
                    endOffset = endOffset,
                    origin = DECLARATION_ORIGIN_ENUM,
                    symbol = IrClassSymbolImpl(it),
                    name = "OBJECT".synthesizedName,
                    kind = ClassKind.OBJECT,
                    visibility = Visibilities.PUBLIC,
                    modality = Modality.FINAL,
                    isCompanion = false,
                    isInner = false,
                    isData = false,
                    isExternal = false,
                    isInline = false
            ).apply {
                it.bind(this)
                parent = enumClass
                createParameterDeclarations()
            }
        }

        val valuesType = context.ir.symbols.array.typeWith(enumClass.defaultType)
//        val valuesProperty = createEnumValuesField(enumClassDescriptor, implObjectDescriptor)
//        val valuesField = IrFieldImpl(startOffset, endOffset, DECLARATION_ORIGIN_ENUM, valuesProperty, valuesType)
        val valuesField = WrappedFieldDescriptor().let {
            IrFieldImpl(
                    startOffset,
                    endOffset,
                    DECLARATION_ORIGIN_ENUM,
                    IrFieldSymbolImpl(it),
                    "VALUES".synthesizedName,
                    valuesType,
                    Visibilities.PRIVATE,
                    true,
                    false,
                    false
            ).apply {
                it.bind(this)
                parent = implObject
            }
        }

        val valuesGetter = WrappedSimpleFunctionDescriptor().let {
            IrFunctionImpl(
                    startOffset,
                    endOffset,
                    DECLARATION_ORIGIN_ENUM,
                    IrSimpleFunctionSymbolImpl(it),
                    "get-VALUES".synthesizedName,
                    Visibilities.PUBLIC,
                    Modality.FINAL,
                    false,
                    false,
                    false,
                    false
            ).apply {
                it.bind(this)
                parent = implObject
                returnType = valuesType
            }
        }
        val valuesGetterDescriptor = createValuesGetterDescriptor(enumClassDescriptor, implObjectDescriptor)

        if (valuesGetterDescriptor.isExported() != valuesGetter.descriptor.isExported()) {
            println("BUGBUGBUG: ${valuesGetterDescriptor} ${ir2stringWhole(valuesGetter)}")
        }

//        val valuesGetter = IrFunctionImpl(startOffset, endOffset, DECLARATION_ORIGIN_ENUM, valuesGetterDescriptor).also {
//            it.returnType = valuesType
//            it.parent = implObject
//        }

        val constructorOfAny = context.irBuiltIns.anyClass.owner.constructors.first()
        // TODO: why primary?
//        val constructor = implObject.addSimpleDelegatingConstructor(
//                constructorOfAny,
//                context.irBuiltIns,
//                DECLARATION_ORIGIN_ENUM,
//                true
//        )
        implObject.buildSimpleDelegatingConstructor(
                constructorOfAny,
                context.irBuiltIns,
                true
        )

        implObject.superTypes += context.irBuiltIns.anyType
        implObject.addFakeOverrides()

        //val (itemGetterSymbol, itemGetterDescriptor) = getEnumItemGetter(enumClassDescriptor)

        return LoweredEnum(
                implObject,
                valuesField,
                valuesGetter,
                //itemGetterSymbol,BinaryInterface.kt
                context.ir.symbols.array.functions.single { it.descriptor.name == Name.identifier("get") },
                createEnumEntriesMap(enumClass))
    }

    private fun createValuesGetterDescriptor(enumClassDescriptor: ClassDescriptor, implObjectDescriptor: ClassDescriptor)
            : FunctionDescriptor {
        val returnType = genericArrayType.defaultType.replace(listOf(TypeProjectionImpl(enumClassDescriptor.defaultType)))
        val result = SimpleFunctionDescriptorImpl.create(
                /* containingDeclaration        = */ implObjectDescriptor,
                /* annotations                  = */ Annotations.EMPTY,
                /* name                         = */ "get-VALUES".synthesizedName,
                /* kind                         = */ CallableMemberDescriptor.Kind.SYNTHESIZED,
                /* source                       = */ SourceElement.NO_SOURCE)
        result.initialize(
                /* receiverParameterType        = */ null,
                /* dispatchReceiverParameter    = */ null,
                /* typeParameters               = */ listOf(),
                /* unsubstitutedValueParameters = */ listOf(),
                /* unsubstitutedReturnType      = */ returnType,
                /* modality                     = */ Modality.FINAL,
                /* visibility                   = */ Visibilities.PUBLIC)
        return result
    }

    private fun createEnumValuesField(enumClassDescriptor: ClassDescriptor, implObjectDescriptor: ClassDescriptor): PropertyDescriptor {
        val valuesArrayType = context.builtIns.getArrayType(Variance.INVARIANT, enumClassDescriptor.defaultType)
        val receiver = ReceiverParameterDescriptorImpl(
                implObjectDescriptor,
                ImplicitClassReceiver(implObjectDescriptor, null),
                Annotations.EMPTY
        )
        return PropertyDescriptorImpl.create(implObjectDescriptor, Annotations.EMPTY, Modality.FINAL, Visibilities.PUBLIC,
                false, "VALUES".synthesizedName, CallableMemberDescriptor.Kind.SYNTHESIZED, SourceElement.NO_SOURCE,
                false, false, false, false, false, false).apply {

            this.setType(valuesArrayType, emptyList(), receiver, null)
            this.initialize(null, null)
        }
    }

    private val genericArrayType = context.ir.symbols.array.descriptor

    private fun getEnumItemGetter(enumClassDescriptor: ClassDescriptor): Pair<IrSimpleFunctionSymbol, FunctionDescriptor> {
        val getter = context.ir.symbols.array.functions.single { it.descriptor.name == Name.identifier("get") }

        val typeParameterT = genericArrayType.declaredTypeParameters[0]
        val enumClassType = enumClassDescriptor.defaultType
        val typeSubstitutor = TypeSubstitutor.create(mapOf(typeParameterT.typeConstructor to TypeProjectionImpl(enumClassType)))
        return getter to getter.descriptor.substitute(typeSubstitutor)!!
    }

//    private fun createEnumEntriesMap(enumClassDescriptor: ClassDescriptor): Map<Name, Int> {
//        val map = mutableMapOf<Name, Int>()
//        enumClassDescriptor.enumEntries
//                .sortedBy { it.name }
//                .forEachIndexed { index, entry -> map.put(entry.name, index) }
//        return map
//    }

    private fun createEnumEntriesMap(enumClass: IrClass) =
            enumClass.declarations
                    .filterIsInstance<IrEnumEntry>()
                    .sortedBy { it.name }
                    .withIndex()
                    .associate { it.value.name to it.index }
                    .toMap()
}
