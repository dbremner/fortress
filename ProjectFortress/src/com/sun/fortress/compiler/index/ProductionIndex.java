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

import java.util.List;

import com.sun.fortress.nodes.APIName;
import com.sun.fortress.nodes.NonterminalDecl;
import com.sun.fortress.nodes.NonterminalDef;
import com.sun.fortress.nodes.QualifiedIdName;
import com.sun.fortress.nodes.SyntaxDef;
import com.sun.fortress.nodes.TraitType;

import edu.rice.cs.plt.tuple.Option;

public abstract class ProductionIndex<T extends NonterminalDecl> {

	private Option<T> ast;
	
	public ProductionIndex(Option<T> ast) {
		this.ast = ast;
	}

	public Option<T> ast() {
		return this.ast;
	}

	public QualifiedIdName getName() {
		if (this.ast().isSome()) {
			return Option.unwrap(this.ast()).getName();
		}
		throw new RuntimeException("Production index without ast and thus no name");
	}

	public List<SyntaxDef> getSyntaxDefs() {
		if (this.ast().isSome()) {
			return Option.unwrap(this.ast()).getSyntaxDefs();
		}
		throw new RuntimeException("Production index without ast and thus no syntax definitions");
	}

	public TraitType getType() {
		if (this.ast().isSome()) {
			Option<TraitType> type = Option.unwrap(this.ast()).getType();
			if (type.isSome()) {
				return Option.unwrap(type);
			}
			throw new RuntimeException("Production index without type, type inference is not implemented yet!");
		}
		throw new RuntimeException("Production index without ast and thus no type");
	}

	public T getAst() {
		if (this.ast().isNone()) {
			throw new RuntimeException("Ast not found.");
		}
		return Option.unwrap(this.ast);
	}

	public boolean isPrivate() {
		return getAst().getModifier().isSome();
	}
	
}
