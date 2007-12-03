/*******************************************************************************
    Copyright 2007 Sun Microsystems, Inc.,
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

package com.sun.fortress.syntaxabstractions;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import com.sun.fortress.nodes.GrammarDef;
import com.sun.fortress.nodes.IdName;
import com.sun.fortress.nodes.Node;
import com.sun.fortress.nodes.ProductionDef;
import com.sun.fortress.nodes.QualifiedName;
import com.sun.fortress.nodes_util.NodeFactory;
import com.sun.fortress.nodes_util.NodeUtil;
import com.sun.fortress.parser_util.FortressUtil;

public class GrammarIndex {

	private GrammarDef grammar;
	private Map<QualifiedName, ProductionDef> productions;
	private LinkedList<GrammarIndex> extendsGrammars;
	private boolean initialized;
	private boolean isTopLevel;

	public GrammarIndex() {
		this.initialized = false;
	}
	
	public GrammarIndex(GrammarDef grammar, boolean isToplevel) {
		this.setGrammar(grammar, isToplevel);
	}

	public void setGrammar(GrammarDef grammar, boolean isTopLevel) {
		this.isTopLevel = isTopLevel;
		this.grammar = grammar;
		this.productions = new HashMap<QualifiedName, ProductionDef>();
		this.extendsGrammars = new LinkedList<GrammarIndex>();
		for (ProductionDef p: grammar.getProductions()) {
			this.productions.put(p.getName(), p);
		}
		this.initialized = true;
	}

	public boolean isInitialized() {
		return this.initialized;
	}
	
	public boolean isTopLevel() {
		return this.isTopLevel;
	}
	
	public IdName getName() {
		return this.grammar.getName();
	}

	public boolean containsProduction(String productionName) {
		return this.productions.containsKey(NodeFactory.makeIdName(productionName));
	}

	public GrammarDef ast() {
		return this.grammar;
	}
	
	public List<GrammarIndex> getExtendedGrammar() {
		return this.extendsGrammars;
	}

	public Collection<? extends ProductionDef> getProductions() {
		return this.productions.values();
	}

	public void addExtendingGrammar(GrammarIndex grammarIndex) {
		this.extendsGrammars.add(grammarIndex);		
	}

}
