// Copyright (c) 2020 Google LLC All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.
package com.google.idea.gn.psi

import com.google.idea.gn.GnKeys
import com.google.idea.gn.completion.CompletionIdentifier
import com.google.idea.gn.psi.builtin.ForwardVariablesFrom
import com.google.idea.gn.psi.builtin.Template
import com.google.idea.gn.psi.scope.BlockScope
import com.google.idea.gn.psi.scope.BuiltinScope
import com.google.idea.gn.psi.scope.Scope
import com.google.idea.gn.psi.scope.TemplateScope
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.util.parentsOfType

class TemplateFunction(override val name: String, val declaration: GnCall, val declarationScope: Scope) : Function() {
  override fun execute(call: GnCall, targetScope: Scope) {
    val declarationBlock = declaration.block ?: return
    val executionScope: BlockScope = TemplateScope(declarationScope, call)

    executionScope.addVariable(
        Variable(Template.TARGET_NAME,
            GnPsiUtil.evaluateFirst(call.exprList, targetScope)))

    call.block?.let { callBlock ->
      val innerScope: Scope = BlockScope(targetScope)
      Visitor(innerScope).visitBlock(callBlock)
      innerScope.consolidateVariables()?.let {
        executionScope
            .addVariable(Variable(Builtin.INVOKER.name, GnValue(it)))
      }
    }

    Visitor(BlockScope(executionScope)).visitBlock(declarationBlock)
  }

  override val variables: Map<String, FunctionVariable> by lazy {
    gatherInvokerVariables() ?: emptyMap()
  }

  override val identifierType: CompletionIdentifier.IdentifierType
    get() = CompletionIdentifier.IdentifierType.TEMPLATE

  override val isBuiltin: Boolean
    get() = false

  private fun gatherInvokerVariables(): Map<String, FunctionVariable>? {
    val block = declaration.block ?: return null
    val variables = mutableMapOf<String, FunctionVariable>()

    block.accept(Visitor(BlockScope(declarationScope), object : Visitor.VisitorDelegate() {
      fun visitForwardVariables(call: GnCall) {
        val exprList = call.exprList.exprList
        if (exprList.size < 2) {
          return
        }
        if (!exprList[0].textMatches(Builtin.INVOKER.name)) {
          return
        }
        // We're evaluating on built-in scope assuming that variables are usually set as literals.
        val forward = GnPsiUtil.evaluate(exprList[1], BuiltinScope)
        forward?.list?.let { vars ->
          val varMap = vars.mapNotNull {
            it.string?.let { s -> Pair(s, FunctionVariable(s)) }
          }
          variables.putAll(varMap)
          return
        }
        forward?.string?.let { star ->
          if (star == ForwardVariablesFrom.STAR) {
            val parent = call.parent.parentsOfType(GnCall::class.java).firstOrNull() ?: return
            if (parent != declaration) {
              // Forward all variables from the resolved function.
              parent.getUserData(GnKeys.CALL_RESOLVED_FUNCTION)?.let { f ->
                variables.putAll(f.variables)
              }
            }
          }
          return
        }

      }

      override fun resolveCall(call: GnCall, function: Function?): Visitor.CallAction =
          when (function) {
            is Template -> Visitor.CallAction.EXECUTE
            is ForwardVariablesFrom -> {
              visitForwardVariables(call)
              Visitor.CallAction.SKIP
            }
            else -> Visitor.CallAction.VISIT_BLOCK
          }


      override fun shouldExecuteExpr(expr: GnExpr): Boolean {
        expr.accept(object : PsiElementVisitor() {
          override fun visitElement(element: PsiElement?) {
            if (element !is GnScopeAccess) {
              element?.acceptChildren(this)
              return
            }
            if (element.idList.size < 2) {
              return
            }
            val first = element.idList[0]
            if (!first.textMatches(Builtin.INVOKER.name)) {
              return
            }
            val second = element.idList[1].text
            variables[second] = FunctionVariable(second)
          }
        })
        return false
      }

      override val observeConditions: Boolean
        get() = false
    }))

    return variables
  }
}