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

/*
 * Fortress where clause.
 * Fortress AST node local to the Rats! com.sun.fortress.interpreter.parser.
 */
package com.sun.fortress.parser_util;

import com.sun.fortress.nodes.WhereClause;
import com.sun.fortress.nodes_util.Span;
import edu.rice.cs.plt.tuple.Option;

public class Where extends TraitClause {
    private Option<WhereClause> where = Option.<WhereClause>none();

    public Where(Span span, Option<WhereClause> where) {
        super(span);
        this.where = where;
    }

    public Option<WhereClause> getWhere() {
        return where;
    }

    public String message() {
        return "where";
    }

    public Span span() {
        return span;
    }
}
