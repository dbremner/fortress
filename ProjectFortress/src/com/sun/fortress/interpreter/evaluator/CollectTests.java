/*******************************************************************************
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
 ******************************************************************************/

package com.sun.fortress.interpreter.evaluator;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import edu.rice.cs.plt.tuple.Option;

import com.sun.fortress.interpreter.evaluator.values.FunctionClosure;
import com.sun.fortress.interpreter.evaluator.values.Fcn;
import com.sun.fortress.interpreter.evaluator.values.FGenericFunction;
import com.sun.fortress.interpreter.evaluator.values.FValue;
import com.sun.fortress.useful.Debug;
import com.sun.fortress.nodes.*;
import com.sun.fortress.nodes_util.*;

/**
 * This is a very simple visitor which just gathers up all of the test functions/data and adds them to the environment.
 * For now we only add test functions.
 */

public class CollectTests extends NodeDepthFirstVisitor<Boolean> {

    private static List<String> tests;

    public Boolean defaultCase(Node that) {
        return false;
    }

    public void visit(CompilationUnit n) {
        n.accept(this);
    }

    public static List<String> getTests() { return tests; }

    public CollectTests() {
        tests = new ArrayList<String>();
    }

    public Boolean forFnDecl(FnDecl x) {
        Debug.debug( Debug.Type.INTERPRETER, 2, "ForFnDecl ", x);
        List<StaticParam> optStaticParams = NodeUtil.getStaticParams(x);
        String fname = NodeUtil.nameAsMethod(x);

        if (NodeUtil.getMods(x).isTest()) {
            tests.add(fname);
        }
        return false;
    }
}
