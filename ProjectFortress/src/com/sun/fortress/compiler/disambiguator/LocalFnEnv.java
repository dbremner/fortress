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

package com.sun.fortress.compiler.disambiguator;

import java.util.Set;
import java.util.Collections;

import com.sun.fortress.compiler.index.GrammarIndex;
import com.sun.fortress.nodes.IdOrOpOrAnonymousName;
import com.sun.fortress.nodes.Id;
import com.sun.fortress.nodes.IdOrOp;
import com.sun.fortress.nodes.Op;
import com.sun.fortress.nodes_util.NodeFactory;

import edu.rice.cs.plt.tuple.Option;

public class LocalFnEnv extends DelegatingNameEnv {
    private Set<IdOrOpOrAnonymousName> _fns;

    public LocalFnEnv(NameEnv parent, Set<IdOrOpOrAnonymousName> fns) {
        super(parent);
        _fns = fns;
    }

    @Override public Set<IdOrOp> explicitFunctionNames(IdOrOp name) {
        if (_fns.contains(name)) {
            return Collections.singleton(name);
        }
        else { return super.explicitFunctionNames(name); }
    }

	@Override
	public Set<Id> explicitGrammarNames(String name) {
		return Collections.emptySet();
	}

	@Override
	public boolean hasGrammar(String name) {
		return false;
	}

	@Override
	public boolean hasQualifiedGrammar(Id name) {
		return false;
	}

	@Override
	public Set<Id> onDemandGrammarNames(String name) {
		return Collections.emptySet();
	}

	@Override
	public Option<GrammarIndex> grammarIndex(Id name) {
		return Option.none();
	}

}
