/*******************************************************************************
    Copyright 2009 Sun Microsystems, Inc.,
    4150 Network Circle, Santa Clara, California 95054, U.S.A.
    All rights reserved.

    U.S. Government Rights - Commercial software.
    Government users are subject to the Sun Microsystems, Inc. standard
    license agreement and applicable provisions of the FAR and its supplements.

    Use is subject to license terms.

    This distribution may include materials developed by third parties.

    Sun, Sun Microsystems, the Sun logo and Java are trademarks or registered
    trademarks of Sun Microsystems, Inc. in the U.S. and other countries.
******************************************************************************/

package com.sun.fortress.compiler.codegen

import com.sun.fortress.exceptions.CompilerError
import com.sun.fortress.nodes._
import com.sun.fortress.useful.Debug
import com.sun.fortress.scala_src.nodes._
import com.sun.fortress.scala_src.useful.Lists._
import com.sun.fortress.scala_src.useful.Sets._
import scala.collection.mutable.Set

class SParallelismAnalyzer extends Walker {
    private val ARG_THRESHOLD = 2
    private val worthy = Set[ASTNode]()

    private def isComputeIntensiveArg(e: Expr) = {
        // A FnRef should not be parallelized itself. But, as an argument,
        // it supports the case for parallelizing the enclosing application.
      e match {
        case _: FnRef => true
        case _ => worthParallelizing(e)
      }
    }

    private def debug(message: String) = {
        Debug.debug(Debug.Type.CODEGEN,1, "ParallelismAnalyzer: " + "::" + message)
    }

    private def tallyArgs(args: List[Expr]) = {
        var count = 0

        for (e <- args) {
            if (isComputeIntensiveArg(e)) count += 1
        }
        count >= ARG_THRESHOLD
    }

    def worthParallelizing(n: ASTNode) = worthy.contains(n)

    def printTable() = {
        for (node <- worthy)
            debug("Parallelizable table has entry " + node)
    }

    override def walk(x: Any) = {
        x match {
          case sop@SOpExpr(_, op, args) => {
               if (tallyArgs(args)) worthy += sop
               walk(args)
          }
          case fnApp@S_RewriteFnApp(_, fun, STupleExpr(_, exprs, varargs, keywords, _)) => {
               if (tallyArgs(exprs)) worthy += fnApp
          }
          case _ => super.walk(x)
        }
    }
}
