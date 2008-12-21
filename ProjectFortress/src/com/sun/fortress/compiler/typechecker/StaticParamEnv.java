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

package com.sun.fortress.compiler.typechecker;

import com.sun.fortress.nodes.*;
import com.sun.fortress.nodes_util.NodeFactory;
import com.sun.fortress.nodes_util.Span;
import edu.rice.cs.plt.tuple.Option;

import java.util.Arrays;
import java.util.List;

public abstract class StaticParamEnv {
    public static StaticParamEnv make(StaticParam... params) {
        return EmptyStaticParamEnv.ONLY.extend(params);
    }

    public static StaticParamEnv make(List<StaticParam> params) {
        return EmptyStaticParamEnv.ONLY.extend(params);
    }

    public abstract Option<StaticParam> binding(IdOrOpOrAnonymousName name);

    public Option<StaticParam> binding(Span span, String name) {
        return binding(NodeFactory.makeId(span, name));
    }

    public Option<StaticParam> opBinding(Span span, String name) {
        return binding(NodeFactory.makeOp(span, name));
    }

    public StaticParamEnv extend(StaticParam... params) {
        return this.extend(Arrays.asList(params));
    }

    public StaticParamEnv extend(List<StaticParam> params) {
        if (params.size() == 0) { return this; }
        else { return new NonEmptyStaticParamEnv(params, this); }
    }

    public StaticParamEnv extend(List<StaticParam> params, Option<WhereClause> whereClause) {
        if ( whereClause.isSome() ) {
            // For now, only bindings of hidden type variables are supported (not constraints).
            List<WhereBinding> whereBindings = whereClause.unwrap().getBindings();
            if (params.size() == 0 && whereBindings.size() == 0) { return this; }
            else { return new WhereClauseEnv(params, whereClause.unwrap(), this); }
        } else {
            if (params.size() == 0) { return this; }
            else { return new NonEmptyStaticParamEnv(params, this); }
        }
    }
}
