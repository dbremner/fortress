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
import edu.rice.cs.plt.tuple.Option;
import java.util.*;

import static edu.rice.cs.plt.tuple.Option.*;

class LValueTypeEnv extends TypeEnv {
    private LValueBind[] entries;
    private TypeEnv parent;

    LValueTypeEnv(LValueBind[] _entries, TypeEnv _parent) {
        entries = _entries;
        parent = _parent;
    }

    LValueTypeEnv(List<LValueBind> _entries, TypeEnv _parent) {
        entries = _entries.toArray(new LValueBind[_entries.size()]);
        parent = _parent;
    }

    /**
     * Return a BindingLookup that binds the given IdOrOpOrAnonymousName to a type
     * (if the given IdOrOpOrAnonymousName is in this type environment).
     */
    public Option<BindingLookup> binding(IdOrOpOrAnonymousName var) {
        for (LValueBind entry : entries) {
            if (var.equals(entry.getName())) {
                return some(new BindingLookup(entry));
            }
        }
        if (var instanceof Id) {
            Id _var = (Id)var;
            if (_var.getApi().isSome())
                return binding(new Id(_var.getSpan(), _var.getText()));
        }
        return parent.binding(var);
    }

    @Override
    public List<BindingLookup> contents() {
        List<BindingLookup> result = new ArrayList<BindingLookup>();
        for (LValueBind entry : entries) {
            result.add(new BindingLookup(entry));
        }
        result.addAll(parent.contents());
        return result;
    }
}