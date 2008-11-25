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

package com.sun.fortress.compiler.index;

import java.util.Collections;
import java.util.List;

import com.sun.fortress.nodes.ArrowType;
import com.sun.fortress.nodes.BaseType;
import com.sun.fortress.nodes.Expr;
import com.sun.fortress.nodes.FnDecl;
import com.sun.fortress.nodes.Id;
import com.sun.fortress.nodes.Node;
import com.sun.fortress.nodes.NodeDepthFirstVisitor;
import com.sun.fortress.nodes.NodeUpdateVisitor;
import com.sun.fortress.nodes.Param;
import com.sun.fortress.nodes.StaticArg;
import com.sun.fortress.nodes.StaticParam;
import com.sun.fortress.nodes.Type;
import com.sun.fortress.useful.NI;

import edu.rice.cs.plt.tuple.Option;

/**
 * Note that this is a {@link Function}, not a {@link Method}, despite the name
 * (methods have distinct receivers).
 */
public class FunctionalMethod extends Function {

    private final FnDecl _ast;
    private final Id _declaringTrait;

    public FunctionalMethod(FnDecl ast, Id declaringTrait) {
        _ast = ast;
        _declaringTrait = declaringTrait;
    }

    public FnDecl ast() { return _ast; }

	@Override
	public Option<Expr> body() {
		return _ast.accept(new NodeDepthFirstVisitor<Option<Expr>>(){
			@Override
			public Option<Expr> defaultCase(Node that) {
				return Option.none();
			}
			@Override
			public Option<Expr> forFnDecl(FnDecl that) {
                            return that.getBody();
			}
		});
	}


	@Override
	public List<Param> parameters() {
		return _ast.getParams();
	}

	@Override
	public List<StaticParam> staticParameters() {
		return _ast.getStaticParams();
	}

	@Override
	public Iterable<BaseType> thrownTypes() {
		if(  _ast.getThrowsClause().isSome() )
			return Collections.emptyList();
		else
			return Collections.unmodifiableList(_ast.getThrowsClause().unwrap());
	}

	@Override
	public Functional instantiate(List<StaticParam> params, List<StaticArg> args) {
		// TODO Auto-generated method stub
		return NI.nyi();
	}

	@Override
	public Type getReturnType() {
		return _ast.getReturnType().unwrap();
	}

	@Override
	public Functional acceptNodeUpdateVisitor(NodeUpdateVisitor visitor) {
		return new FunctionalMethod((FnDecl)this.ast().accept(visitor), this._declaringTrait);
	}
}
