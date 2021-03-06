/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.types.infer

import com.intellij.psi.PsiElement
import org.rust.ide.utils.isNullOrEmpty
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.*
import org.rust.lang.core.resolve.ImplLookup
import org.rust.lang.core.resolve.StdKnownItems
import org.rust.lang.core.resolve.ref.resolveFieldLookupReferenceWithReceiverType
import org.rust.lang.core.resolve.ref.resolveMethodCallReferenceWithReceiverType
import org.rust.lang.core.types.ty.*
import org.rust.lang.core.types.type
import org.rust.utils.forEachChild

fun inferTypesIn(fn: RsFunction): RsInferenceContext =
    RsInferenceContext().apply { recursionGuard { infer(fn) } }

class RsInferenceContext {
    private val bindings: MutableMap<RsPatBinding, Ty> = mutableMapOf()
    private val exprTypes: MutableMap<RsExpr, Ty> = mutableMapOf()

    fun infer(fn: RsFunction) {
        extractParameterBindings(fn)

        val block = fn.block
        if (block != null) {
            val items = StdKnownItems.relativeTo(fn)
            RsFnInferenceContext(this, ImplLookup(fn.project, items), items).walkBlock(block)
        }
    }

    private fun extractParameterBindings(fn: RsFunction) {
        for (param in fn.valueParameters) {
            extractBindings(param.pat, param.typeReference?.type ?: TyUnknown)
        }
    }

    fun getExprType(expr: RsExpr): Ty {
        return exprTypes[expr] ?: TyUnknown
    }

    fun getBindingType(binding: RsPatBinding): Ty {
        return bindings[binding] ?: TyUnknown
    }

    fun writeTy(psi: RsExpr, ty: Ty) {
        exprTypes[psi] = ty
    }

    fun extractBindings(pattern: RsPat?, baseType: Ty) {
        if (pattern != null) bindings += collectBindings(pattern, baseType)
    }

    override fun toString(): String {
        return "RsInferenceContext(bindings=$bindings, exprTypes=$exprTypes)"
    }
}

private class RsFnInferenceContext(
    private val ctx: RsInferenceContext,
    private val lookup: ImplLookup,
    private val items: StdKnownItems
) {
    private val RsExpr.ty: Ty get() = ctx.getExprType(this)
    private val RsBlock.ty: Ty get() = expr?.ty ?: TyUnit
    private val RsStructLiteralField.type: Ty get() = resolveToDeclaration?.typeReference?.type ?: TyUnknown

    fun walkBlock(block: RsBlock) {
        for (stmt in block.stmtList) walk(stmt)
        walkNullable(block.expr)
    }

    private fun walkNullable(psi: PsiElement?) {
        if (psi != null) walk(psi)
    }

    private fun walkChildren(psi: PsiElement) {
        psi.forEachChild(this::walk)
    }

    private fun walk(psi: PsiElement) {
        when (psi) {
            is RsLetDecl -> {
                walkNullable(psi.expr)
                ctx.extractBindings(psi.pat, psi.typeReference?.type ?: psi.expr?.ty ?: TyUnknown)
            }
            is RsCondition -> {
                walkNullable(psi.expr)
                ctx.extractBindings(psi.pat, psi.expr.ty)
            }
            is RsMatchArm -> {
                for (pat in psi.patList) {
                    ctx.extractBindings(pat, psi.parentOfType<RsMatchExpr>()?.expr?.ty ?: TyUnknown)
                }
                walkChildren(psi)
            }
            is RsForExpr -> {
                walkNullable(psi.expr)
                ctx.extractBindings(psi.pat, lookup.findIteratorItemType( psi.expr?.ty ?: TyUnknown))
                psi.block?.let { walkBlock(it) }
                ctx.writeTy(psi, TyUnit)
            }
            is RsLambdaExpr -> {
                val params = psi.valueParameterList.valueParameterList
                for (p in params) {
                    p.typeReference?.type?.let { ctx.extractBindings(p.pat, it) }
                }
                val ty = inferTypeForLambdaExpr(psi)
                if (ty is TyFunction) {
                    params.zip(ty.paramTypes).forEach { (p, t) -> ctx.extractBindings(p.pat, t) }
                }
                walkNullable(psi.expr)
                ctx.writeTy(psi, if (ty is TyFunction) TyFunction(ty.paramTypes, psi.expr?.ty ?: ty.retType) else TyUnknown)
            }
            is RsExpr -> {
                walkChildren(psi)
                ctx.writeTy(psi, inferExpressionType(psi))
            }
            else -> {
                walkChildren(psi)
            }
        }
    }

    private fun inferExpressionType(expr: RsExpr) = when (expr) {
        is RsPathExpr -> inferPathExprType(expr)
        is RsStructLiteral -> inferStructLiteralType(expr)
        is RsTupleExpr -> TyTuple(expr.exprList.map { it.ty })
        is RsParenExpr -> expr.expr.ty
        is RsUnitExpr -> TyUnit
        is RsCastExpr -> expr.typeReference.type
        is RsCallExpr -> inferCallExprType(expr)
        is RsDotExpr -> {
            val methodCall = expr.methodCall
            val fieldLookup = expr.fieldLookup
            when {
                methodCall != null -> inferMethodCallExprType(methodCall)
                fieldLookup != null ->inferFieldExprType(fieldLookup)
                else -> TyUnknown
            }
        }
        is RsLitExpr -> inferLiteralExprType(expr)
        is RsBlockExpr -> expr.block.ty
        is RsIfExpr -> if (expr.elseBranch == null) TyUnit else (expr.block?.ty ?: TyUnknown)
        is RsLoopExpr -> inferLoopExprType(expr)
        is RsWhileExpr -> TyUnit
        is RsMatchExpr -> inferMatchExprType(expr)
        is RsUnaryExpr -> inferUnaryExprType(expr)
        is RsBinaryExpr -> inferBinaryExprType(expr)
        is RsTryExpr -> inferTryExprType(expr)
        is RsArrayExpr -> inferArrayType(expr)
        is RsRangeExpr -> inferRangeType(expr)
        is RsIndexExpr -> inferIndexExprType(expr)
        is RsMacroExpr -> inferMacroExprType(expr)
        else -> TyUnknown
    }

    private fun inferPathExprType(expr: RsPathExpr): Ty {
        val (element, subst) = expr.path.reference.advancedResolve()?.downcast<RsNamedElement>() ?: return TyUnknown
        val type = if (element is RsPatBinding) ctx.getBindingType(element) else inferDeclarationType(element)
        return ((element as? RsFieldsOwner)?.tupleFields?.let {
            TyFunction(it.tupleFieldDeclList.map { it.typeReference.type }, type)
        } ?: type).substitute(subst)
    }

    private fun inferStructLiteralType(expr: RsStructLiteral): Ty {
        val (element, subst) = expr.path.reference.advancedResolve() ?: return TyUnknown
        return when (element) {
            is RsStructItem -> element.type
                .substitute(subst)
                .substitute(inferStructTypeArguments(expr))
            is RsEnumVariant -> element.parentEnum.type
                .substitute(subst)
                .substitute(inferStructTypeArguments(expr))
            else -> TyUnknown
        }
    }

    private fun inferCallExprType(expr: RsCallExpr): Ty {
        val fn = expr.expr
        val ty = fn.ty
        // `struct S; S();`
        if (ty is TyStructOrEnumBase && expr.valueArgumentList.exprList.isEmpty()) return ty

        val calleeType = lookup.asTyFunction(ty) ?: return TyUnknown
        return calleeType.retType.substitute(mapTypeParameters(calleeType.paramTypes, expr.valueArgumentList.exprList))
    }

    private fun inferMethodCallExprType(methodCall: RsMethodCall): Ty {
        val receiver = methodCall.receiver.ty
        val (method, subst) = resolveMethodCallReferenceWithReceiverType(lookup, receiver, methodCall)
            .firstOrNull()?.downcast<RsFunction>() ?: return TyUnknown

        val returnType = (method.retType?.typeReference?.type ?: TyUnit)
            .substitute(subst)
            .substitute(mapOf(TyTypeParameter.self() to receiver))

        val methodType = method.type as? TyFunction ?: return returnType
        // drop first element of paramTypes because it's `self` param
        // and it doesn't have value in `methodCall.valueArgumentList.exprList`
        return returnType.substitute(mapTypeParameters(methodType.paramTypes.drop(1), methodCall.valueArgumentList.exprList))
    }

    private fun inferFieldExprType(fieldLookup: RsFieldLookup): Ty {
        val receiver = fieldLookup.receiver
        val boundField = resolveFieldLookupReferenceWithReceiverType(lookup, receiver.ty, fieldLookup).firstOrNull()
        if (boundField == null) {
            val type = receiver.ty as? TyTuple ?: return TyUnknown
            val fieldIndex = fieldLookup.integerLiteral?.text?.toIntOrNull() ?: return TyUnknown
            return type.types.getOrElse(fieldIndex) { TyUnknown }
        }
        val field = boundField.element
        val raw = when (field) {
            is RsFieldDecl -> field.typeReference?.type
            is RsTupleFieldDecl -> field.typeReference.type
            else -> null
        } ?: TyUnknown
        return raw.substitute(boundField.subst)
    }

    private fun inferLoopExprType(expr: RsLoopExpr): Ty {
        val returningTypes = mutableListOf<Ty>()
        val label = expr.labelDecl?.name

        fun collectReturningTypes(element: PsiElement, matchOnlyByLabel: Boolean) {
            element.forEachChild { child ->
                when (child) {
                    is RsBreakExpr -> {
                        collectReturningTypes(child, matchOnlyByLabel)
                        if (!matchOnlyByLabel && child.label == null || child.label?.referenceName == label) {
                            returningTypes += child.expr?.ty ?: TyUnit
                        }
                    }
                    is RsLabeledExpression -> {
                        if (label != null) {
                            collectReturningTypes(child, true)
                        }
                    }
                    else -> collectReturningTypes(child, matchOnlyByLabel)
                }
            }
        }

        collectReturningTypes(expr, false)
        return getMoreCompleteType(returningTypes)
    }

    private fun inferMatchExprType(expr: RsMatchExpr): Ty =
        expr.matchBody?.matchArmList.orEmpty().asSequence()
            .mapNotNull { it.expr?.ty }
            .firstOrNull { it !is TyUnknown }
            ?: TyUnknown

    private fun inferUnaryExprType(expr: RsUnaryExpr): Ty {
        val base = expr.expr?.ty ?: return TyUnknown
        return when (expr.operatorType) {
            UnaryOperator.REF -> TyReference(base, mutable = false)
            UnaryOperator.REF_MUT -> TyReference(base, mutable = true)
            UnaryOperator.DEREF -> when (base) {
                is TyReference -> base.referenced
                is TyPointer -> base.referenced
                else -> TyUnknown
            }
            UnaryOperator.MINUS -> base
            UnaryOperator.NOT -> TyBool
            UnaryOperator.BOX -> TyUnknown
        }
    }

    private fun inferBinaryExprType(expr: RsBinaryExpr): Ty {
        val op = expr.operatorType
        return when (op) {
            is BoolOp -> TyBool
            is ArithmeticOp -> inferArithmeticBinaryExprType(expr, op)
            is AssignmentOp -> TyUnit
        }
    }

    private fun inferTryExprType(expr: RsTryExpr): Ty {
        // See RsMacroExpr where we handle the try! macro in a similar way
        val base = expr.expr.ty

        return if (base is TyEnum && base.item == items.findResultItem())
            base.typeArguments.firstOrNull() ?: TyUnknown
        else
            TyUnknown
    }

    private fun inferRangeType(expr: RsRangeExpr): Ty {
        val el = expr.exprList
        val dot2 = expr.dotdot
        val dot3 = expr.dotdotdot

        val (rangeName, indexType) = when {
            dot2 != null && el.size == 0 -> "RangeFull" to null
            dot2 != null && el.size == 1 -> {
                val e = el[0]
                if (e.startOffsetInParent < dot2.startOffsetInParent) {
                    "RangeFrom" to e.ty
                } else {
                    "RangeTo" to e.ty
                }
            }
            dot2 != null && el.size == 2 -> {
                "Range" to getMoreCompleteType(el[0].ty, el[1].ty)
            }
            dot3 != null && el.size == 1 -> {
                val e = el[0]
                if (e.startOffsetInParent < dot3.startOffsetInParent) {
                    return TyUnknown
                } else {
                    "RangeToInclusive" to e.ty
                }
            }
            dot3 != null && el.size == 2 -> {
                "RangeInclusive" to getMoreCompleteType(el[0].ty, el[1].ty)
            }

            else -> error("Unrecognized range expression")
        }

        return items.findRangeTy(rangeName, indexType)
    }

    private fun inferIndexExprType(expr: RsIndexExpr): Ty {
        val containerType = expr.containerExpr?.ty ?: return TyUnknown
        val indexType = expr.indexExpr?.ty ?: return TyUnknown
        return lookup.findIndexOutputType( containerType, indexType)
    }

    private fun inferMacroExprType(expr: RsMacroExpr): Ty {
        val vecArg = expr.macroCall.vecMacroArgument
        if (vecArg != null) {
            val elementTypes = vecArg.exprList.map { it.ty }
            val elementType = getMoreCompleteType(elementTypes)
            return items.findVecForElementTy(elementType)
        }

        val tryArg = expr.macroCall.tryMacroArgument
        if (tryArg != null) {
            // See RsTryExpr where we handle the ? expression in a similar way
            val base = tryArg.expr.ty
            return if (base is TyEnum && base.item == items.findResultItem())
                base.typeArguments.firstOrNull() ?: TyUnknown
            else
                TyUnknown
        }

        val name = expr.macroCall.macroName?.text ?: return TyUnknown
        return when {
            "print" in name || "assert" in name -> TyUnit
            name == "format" -> items.findStringTy()
            name == "format_args" -> items.findArgumentsTy()
            expr.macroCall.formatMacroArgument != null || expr.macroCall.logMacroArgument != null -> TyUnit
            else -> TyUnknown
        }
    }

    private fun inferTypeForLambdaExpr(lambdaExpr: RsLambdaExpr): Ty {
        val fallback = TyFunction(emptyList(), TyUnknown)
        val parent = lambdaExpr.parent as? RsValueArgumentList ?: return fallback
        val callExpr = parent.parent
        val containingFunctionType = when (callExpr) {
            is RsCallExpr -> callExpr.expr.ty
            is RsMethodCall -> {
                val fn = resolveMethodCallReferenceWithReceiverType(lookup, callExpr.receiver.ty, callExpr)
                    .firstOrNull()
                    ?.downcast<RsFunction>()
                    ?: return fallback
                fn.element.type.substitute(fn.subst)
            }
            else -> null
        } as? TyFunction
            ?: return fallback

        val lambdaArgumentPosition = parent.exprList.indexOf(lambdaExpr) + (if (callExpr is RsMethodCall) 1 else 0)

        val param = containingFunctionType.paramTypes.getOrNull(lambdaArgumentPosition)
            ?: return fallback

        return lookup.asTyFunction(param)?.substitute(containingFunctionType.typeParameterValues) ?: fallback
    }

    private fun inferArrayType(expr: RsArrayExpr): Ty {
        val (elementType, size) = if (expr.semicolon != null) {
            val elementType = expr.initializer?.ty ?: return TySlice(TyUnknown)
            val size = calculateArraySize(expr.sizeExpr) ?: return TySlice(elementType)
            elementType to size
        } else {
            val elementTypes = expr.arrayElements?.map { it.ty }
            if (elementTypes.isNullOrEmpty()) return TySlice(TyUnknown)

            // '!!' is safe here because we've just checked that elementTypes isn't null
            val elementType = getMoreCompleteType(elementTypes!!)
            elementType to elementTypes.size
        }
        return TyArray(elementType, size)
    }

    private fun getMoreCompleteType(types: List<Ty>): Ty {
        if (types.isEmpty()) return TyUnknown
        return types.reduce { acc, ty -> getMoreCompleteType(acc, ty) }
    }

    private fun inferStructTypeArguments(literal: RsStructLiteral): Substitution =
        inferFieldTypeArguments(literal.structLiteralBody.structLiteralFieldList)

    private fun inferFieldTypeArguments(fieldExprs: List<RsStructLiteralField>): Substitution =
        UnifyResult.mergeAll(
            fieldExprs.mapNotNull { field -> field.expr?.let { expr -> field.type.unifyWith(expr.ty, lookup) } }
        ).substitution().orEmpty()

    private fun mapTypeParameters(argDefs: Iterable<Ty>, argExprs: Iterable<RsExpr>): Substitution {
        val subst = UnifyResult.mergeAll(
            argDefs.zip(argExprs).map { (type, expr) -> type.unifyWith(expr.ty, lookup) }
        ).substitution().orEmpty()
        return subst.substituteInValues(subst) // TODO multiple times?
    }

    private fun inferArithmeticBinaryExprType(expr: RsBinaryExpr, op: ArithmeticOp): Ty {
        val lhsType = expr.left.ty
        val rhsType = expr.right?.ty ?: TyUnknown
        return lookup.findArithmeticBinaryExprOutputType(lhsType, rhsType, op)
    }
}

private val threadLocalGuard: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }

/**
 * This function asserts that code inside a lambda don't call itself recursively.
 */
private inline fun recursionGuard(action: () -> Unit) {
    if (threadLocalGuard.get()) error("Can not run nested type inference")
    threadLocalGuard.set(true)
    try {
        action()
    } finally {
        threadLocalGuard.set(false)
    }
}
