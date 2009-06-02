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

package com.sun.fortress.compiler.disambiguator;

import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.Collections;

import com.sun.fortress.compiler.index.GrammarIndex;
import com.sun.fortress.nodes.Id;
import com.sun.fortress.nodes_util.NodeFactory;

import edu.rice.cs.plt.tuple.Option;

public class LocalVarEnv extends DelegatingNameEnv {
    private Set<Id> _vars;

    public LocalVarEnv(NameEnv parent, Set<Id> vars) {
        super(parent);
        _vars = vars;
    }

    @Override public Set<Id> explicitVariableNames(Id name) {
        for (Id var : _vars) {
            if (var.getText().equals(name.getText())) {
                return Collections.singleton(var);
            }
        }
        return super.explicitVariableNames(name);
    }
    @Override public List<Id> explicitVariableNames() {
        List<Id> result = new LinkedList<Id>();
        result.addAll(_vars);
        result.addAll(_parent.explicitVariableNames());
        return result;
    }

}
