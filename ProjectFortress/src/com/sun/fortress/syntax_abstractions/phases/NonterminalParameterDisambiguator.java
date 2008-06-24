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

package com.sun.fortress.syntax_abstractions.phases;

import java.util.Collection;
import java.util.LinkedList;
import java.util.List;

import com.sun.fortress.compiler.GlobalEnvironment;
import com.sun.fortress.compiler.disambiguator.NonterminalEnv;
import com.sun.fortress.compiler.disambiguator.NonterminalNameDisambiguator;
import com.sun.fortress.compiler.index.ApiIndex;
import com.sun.fortress.compiler.index.GrammarIndex;
import com.sun.fortress.exceptions.StaticError;
import com.sun.fortress.nodes.APIName;
import com.sun.fortress.nodes.GrammarDef;
import com.sun.fortress.nodes.Id;
import com.sun.fortress.nodes.Node;
import com.sun.fortress.nodes.NodeUpdateVisitor;
import com.sun.fortress.nodes.NonterminalHeader;
import com.sun.fortress.nodes.NonterminalParameter;
import com.sun.fortress.useful.HasAt;
import com.sun.fortress.useful.Pair;

import edu.rice.cs.plt.tuple.Option;

public class NonterminalParameterDisambiguator extends NodeUpdateVisitor {

    private Collection<StaticError> _errors;
    private GlobalEnvironment _globalEnv;
    private GrammarIndex _currentGrammarIndex;

    public NonterminalParameterDisambiguator(GlobalEnvironment env) {
        this._errors = new LinkedList<StaticError>();
        this._globalEnv = env;
    }

    private void error(String msg, HasAt loc) {
        this._errors.add(StaticError.make(msg, loc));
    }

    public Option<GrammarIndex> grammarIndex(Id name) {
        if (name.getApi().isSome()) {
            APIName api = name.getApi().unwrap();
            if (this._globalEnv.definesApi(api)) {
                return Option.some(_globalEnv.api(api).grammars().get(name.getText()));
            }
            else {
                return Option.none();
            }
        }
        throw new RuntimeException("Non-disambiguated grammar: "+name);
    }

    @Override
    public Node forGrammarDef(GrammarDef that) {
        Option<GrammarIndex> index = this.grammarIndex(that.getName());
        if (index.isSome()) {
            this._currentGrammarIndex = index.unwrap();
        }
        else {
            error("Grammar "+that.getName()+" not found", that);
        }
        return super.forGrammarDef(that);
    }

    // Disambiguate the parameters
    @Override
    public Node forNonterminalHeader(NonterminalHeader that) {
        List<NonterminalParameter> params = new LinkedList<NonterminalParameter>();
        for (NonterminalParameter p: that.getParams()) {
            NonterminalNameDisambiguator pnd = new NonterminalNameDisambiguator(this._globalEnv);
            Option<Id> n = pnd.handleNonterminalName(new NonterminalEnv(this._currentGrammarIndex), p.getType());
            this._errors.addAll(pnd.errors());
            if (n.isSome()) {
                params.add(new NonterminalParameter(p.getName(), n.unwrap()));
            }
        }
        return new NonterminalHeader(that.getSpan(), that.getModifier(), that.getName(), params, that.getType(), that.getWhereClause());
    }

    public Collection<? extends StaticError> errors() {
        return this._errors;
    }
}
