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

import java.util.Collection;
import java.util.LinkedList;
import java.util.List;

import com.sun.fortress.nodes.GrammarMemberDecl;
import com.sun.fortress.nodes.NonterminalDecl;
import com.sun.fortress.nodes.NonterminalDef;
import com.sun.fortress.nodes.NonterminalExtensionDef;
import edu.rice.cs.plt.tuple.Option;

public class ProductionExtendIndex extends GrammarNonterminalIndex<NonterminalExtensionDef> {

	private Collection<ProductionIndex<? extends GrammarMemberDecl>> extend;
	
	public ProductionExtendIndex(Option<NonterminalExtensionDef> ast) {
		super(ast);
		this.extend = new LinkedList<ProductionIndex<? extends GrammarMemberDecl>>();
	}

	public Collection<ProductionIndex<? extends GrammarMemberDecl>> getExtends() {
		return this.extend;
	}

	public void addExtendedNonterminal(ProductionIndex<? extends GrammarMemberDecl> ext) {
		this.extend.add(ext);
	}
	
	public void addExtendedNonterminals(Collection<ProductionIndex<? extends GrammarMemberDecl>> ext) {
		this.extend.addAll(ext);
	}
}
