data class Variable(val type: String, val mutable: Boolean)

typealias Scope = HashMap<String, Variable>

class Checker(var nodes: List<AstNode>, val syms: SymbolTable, val otherUnits: List<Translation>)
{
    fun check()
    {
        val defs = mutableListOf<FunctionDef>()
        // Handle all imports first, other nodes may depend on them
        for (node in nodes)
        {
            if (node is Import)
            {
                defs += handleImport(node)
            }
        }

        // Register all symbols beforehand, so that order of declarations doesn't matter
        for (node in nodes)
        {
            when (node)
            {
                is FunctionDecl -> registerFunction(node.proto)
                is FunctionDef  -> registerFunction(node.proto)
                is Import       -> {}
                else            -> throw InternalCompilerException("Unhandled top-level node")
            }
        }

        for (node in nodes)
        {
            if (node is FunctionDecl)
            {
                checkFunction(node)
            }
        }

        nodes += defs
    }

    private fun handleImport(import: Import): List<FunctionDef>
    {
        val defs = mutableListOf<FunctionDef>()

        val unit = otherUnits.find { it.name == import.name + ".n"} ?:
            reportError("check", import.pos, "No file found for module ${import.name}")

        // Recursively check other units. This recursion will go on until a file with no imports is found.
        // This is the bottom of the import tree
        if (!unit.checked)
        {
            unit.check(otherUnits, )
        }

        for (extSym in unit.syms)
        {
            // Only include exported symbols (and not externs)
            if (extSym.value.flags.contains(Flag("export")))
            {
                if (extSym.value.flags.contains(Flag("extern")) || extSym.value.flags.contains(Flag("imported")))
                {
                    continue
                }

                // Make sure imports do not conflict with our symbols
                if (syms[extSym.value.name] != null)
                {
                    reportError(
                        "check",
                        extSym.value.pos,
                        "Multiple definitions of '${extSym.value.name}' with the same parameters is not allowed"
                    )
                }

                // Mark the symbol as an import. This ensures codegen generates it properly
                // and ensures imports don't travel up the import tree
                // Make sure to copy() the externProto, otherwise the original prototype is modified
                val externProto = extSym.value.copy()
                externProto.flags += Flag("imported")

                syms[extSym.value.name] = externProto

                // Defintions can only be added at the end, otherwise they are duplicated by registerFunction()
                defs += FunctionDef(externProto)

            }
        }

        return defs
    }

    private fun registerFunction(proto: Prototype)
    {
        if (syms[proto.name] != null)
        {
            reportError(
                "check",
                proto.pos,
                "Multiple definitions of '${proto.name}' with the same parameters is not allowed"
            )
        }

        if (proto.flags.contains(Flag("extern")))
        {
            syms[proto.name] = proto
            return
        }

        val protoName = if (proto.name == "main")
        {
            "main"
        }
        else
        {
            buildString{
                append("_Z${proto.name}")

                for (arg in proto.args)
                {
                    append("_${arg.type}")
                }
            }
        }

        if (syms[protoName] != null)
        {
            reportError(
                "check",
                proto.pos,
                "Multiple definitions of '${proto.name}' with the same parameters is not allowed"
            )

            return
        }

        proto.name = protoName
        syms[protoName] = proto
    }

    private fun checkFunction(func: FunctionDecl)
    {
        val scope = Scope()

        for (arg in func.proto.args)
        {
            scope[arg.name] = Variable(arg.type, false)
        }

        checkBlock(func.body, func.proto, scope)
    }

    private fun checkBlock(block: Block, proto: Prototype, parentScope: Scope)
    {
        val scope = Scope(parentScope)

        for (stmnt in block.statements)
        {
            when (stmnt)
            {
                is AssignStatement  -> checkAssignStatement(stmnt, scope)
                is DeclareStatement -> checkDeclarationStatement(stmnt, scope)
                is ReturnStatement  -> checkReturnStatement(stmnt, proto, scope)
                is ExprStatement    -> checkExprStatement(stmnt, scope)
                is IfStatement      -> checkIfStatement(stmnt, proto, scope)
                else                -> throw InternalCompilerException("Unchecked statement")
            }
        }
    }

    private fun checkIfStatement(stmnt: IfStatement, proto: Prototype, scope: Scope)
    {
        for (branch in stmnt.branches)
        {
            val exprType = checkExpr(branch.expr, scope)
            if (exprType != "bool")
            {
                reportError("check", branch.expr.pos, "expected boolean expression, found '$exprType'")
            }

            checkBlock(branch.block, proto, scope)
        }

        if (stmnt.elseBlock != null)
        {
            checkBlock(stmnt.elseBlock, proto, scope)
        }
    }

    private fun checkAssignStatement(stmnt: AssignStatement, scope: Scope)
    {
        val lhs = scope[stmnt.name] ?:
            reportError("check", stmnt.expr.pos, "Variable '${stmnt.name}' not declared in this scope")

        if (!lhs.mutable)
        {
            reportError("check", stmnt.expr.pos, "Variable '${stmnt.name}' is immutable")
        }

        val rhs = checkExpr(stmnt.expr, scope)

        if (lhs.type != rhs)
        {
            reportError(
                "check",
                stmnt.expr.pos,
                "Cannot assign value of type '$rhs' to variable '${stmnt.name}' of type '$lhs'"
            )
        }
    }

    private fun checkDeclarationStatement(stmnt: DeclareStatement, scope: Scope)
    {
        val rhs = checkExpr(stmnt.expr, scope)

        // A type was specified during declaration
        if (stmnt.type != null)
        {
            if (stmnt.type != rhs)
            {
                reportError("check", stmnt.expr.pos, "Expected type'${stmnt.type}', but found '$rhs'")
            }
        }
        else
        {
            stmnt.type = rhs
        }

        scope[stmnt.name] = Variable(stmnt.type!!, stmnt.mutable)
    }

    private fun checkExprStatement(stmnt: ExprStatement, scope: Scope)
    {
        checkExpr(stmnt.expr, scope)
    }

    private fun checkReturnStatement(stmnt: ReturnStatement, proto: Prototype, scope: Scope)
    {
        val rhs = checkExpr(stmnt.expr, scope)

        if (rhs != proto.returnType)
        {
            reportError("check", stmnt.expr.pos, "Expected a return-type of '${proto.returnType}', but found '$rhs'")
        }
    }

    private fun checkExpr(expr: Expr, scope: Scope): String
    {
        return when (expr)
        {
            is NumberExpr   -> "int"
            is BooleanExpr  -> "bool"
            is CharExpr     -> "char"
            is StringExpr   -> "string"
            is VariableExpr -> checkVariableExpr(expr, scope)
            is CallExpr     -> checkCallExpr(expr, scope)
            is BinaryExpr   -> checkBinaryExpr(expr, scope)
            else            -> throw InternalCompilerException("Unhandled primary")
        }
    }

    private fun checkBinaryExpr(expr: BinaryExpr, scope: Scope): String
    {
        val lhs = checkExpr(expr.left, scope)
        val rhs = checkExpr(expr.right, scope)

        // We don't allow operations on different types yet
        if (lhs != rhs)
        {
            reportError("check", expr.pos, "Cannot perform '${expr.op}' on values of different types")
        }

        val ops = possibleOps[lhs]
        if (ops == null || ops[expr.op] == null)
        {
            reportError("check", expr.pos, "Cannot perform '${expr.op}' on type '$lhs'")
        }

        return ops[expr.op]!!
    }

    private fun checkCallExpr(expr: CallExpr, scope: Scope): String
    {
        val mangled = buildString {
            append("_Z${expr.callee}")
            expr.args.forEach { arg ->
                val exprType = checkExpr(arg, scope)
                append("_$exprType")
            }
        }

        val paramList = expr.args.joinToString(", ") { checkExpr(it, scope) }

        val proto = syms[mangled] ?: syms[expr.callee] ?:
            reportError(
                "check",
                expr.pos,
                "No matching function '${expr.callee}' found accepting parameters ($paramList)"
            )

        if (syms.containsKey(mangled))
        {
            expr.callee = mangled
        }

        return proto.returnType
    }

    private fun checkVariableExpr(expr: VariableExpr, scope: Scope): String
    {
        val variable = scope[expr.name]?:
            reportError("check", expr.pos, "Variable '${expr.name}' doesn't exist in the current scope")

        return variable.type
    }

    private val possibleOps = mapOf(
        "int" to mapOf(
            "+"  to "int",
            "-"  to "int",
            "*"  to "int",
            "/"  to "int",
            "==" to "bool",
            "!=" to "bool",
            ">"  to "bool",
            "<"  to "bool",
            ">=" to "bool",
            "<=" to "bool",
        ),

        "bool" to mapOf(
            "==" to "bool",
            "!=" to "bool",
            ">"  to "bool",
            "<"  to "bool",
            ">=" to "bool",
            "<=" to "bool",
        ),

        "char" to mapOf(
            "==" to "bool",
            "!=" to "bool",
            ">"  to "bool",
            "<"  to "bool",
            ">=" to "bool",
            "<=" to "bool",
        ),

        "string" to mapOf(
            "==" to "bool",
            "!=" to "bool",
            ">"  to "bool",
            "<"  to "bool",
            ">=" to "bool",
            "<=" to "bool",
        )
    )
}