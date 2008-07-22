/********************************************************************************
  Copyright 2008 Sun Microsystems, Inc.,
  4150 Network Circle, Santa Clara, California 95054, U.S.A.
  All rights reserved.

  U.S. Government Rights - Commercial software.
  Government users are subject to the Sun Microsystems, Inc. standard
  license agreement and applicable provisions of the FAR and its supplements.

  Use is subject to license terms.

  This distribution may include materials developed by third parties.

  Sun, Sun Microsystems, the Sun logo and Java are trademarks or registered
  trademarks of Sun Microsystems, Inc. in the U.S. and other countries.
  ********************************************************************************/

package com.sun.fortress.interpreter.evaluator.tasks;

import java.io.IOException;


import com.sun.fortress.interpreter.evaluator.Evaluator;
import com.sun.fortress.interpreter.evaluator.Environment;
import com.sun.fortress.interpreter.evaluator.values.FValue;
import com.sun.fortress.nodes.Expr;

public class TupleTask extends BaseTask {
    Evaluator eval;
    Expr expr;
    FValue res;

    public TupleTask(Expr ex, Evaluator ev) {
		super(FortressTaskRunner.getTask());
        expr = ex;
        eval = ev;
    }

    public void compute() {
		FortressTaskRunner.setCurrentTask(this);
		try {
			Environment inner = eval.e.extendAt(expr);
			Evaluator e = new Evaluator(inner);
			res = expr.accept(e);
		} catch (NullPointerException e) {
			throw e;
		} catch (Exception e) {
			causedException = true;
			err = e;
		} finally {
			/* Null out fields so they are not retained by GC after termination. */
			eval = null;
			expr = null;
		}
    }

    public void print() {
        System.out.println("Tuple Task: eval = " + eval +
                           "\n\t Expr = " + expr +
                           "\n\t Res = " + res +
                           "\n\t Thread = " + Thread.currentThread());
    }

    public String toString() {
		return "[TupleTask" + name() +  ":" + taskState() + "]" ;
    }

    public FValue getRes() { return res;}
}
